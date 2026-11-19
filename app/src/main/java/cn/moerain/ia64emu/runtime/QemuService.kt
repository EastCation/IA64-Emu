package cn.moerain.ia64emu.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import cn.moerain.ia64emu.MainActivity
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.bootstrap.Bootstrap
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.data.VmStore
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class QemuService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { VmStore(this) }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var runningVmId: String? = null

    @Volatile
    private var runningName: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val vmId = intent.getStringExtra(EXTRA_VM_ID)
                if (vmId.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (process?.isAlive == true) {
                    return START_STICKY
                }
                runningVmId = vmId
                runningName = store.get(vmId)?.name ?: "IA-64 VM"
                startForeground(NOTIFICATION_ID, buildNotification("正在启动 $runningName…", false))
                acquireWakeLock()
                stopRequested = false
                worker = Thread { runVm(vmId) }.also { it.start() }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun runVm(vmId: String) {
        val config = store.get(vmId)
        if (config == null) {
            fail(vmId, "", "找不到虚拟机配置")
            return
        }
        try {
            if (!Bootstrap.isInstalled(this)) {
                fail(vmId, config.name, "尚未安装 QEMU 引导包，请先在设置中安装")
                return
            }
            Bootstrap.ensureProot(this).getOrElse { throw IOException("PRoot 初始化失败: ${it.message}") }
            val proot = Bootstrap.prootExecutable(this) ?: throw IOException("PRoot 不可用")
            val manifest = Bootstrap.manifest(this) ?: throw IOException("引导包清单损坏")

            val vmDir = store.vmDir(vmId).also { it.mkdirs() }
            store.importsDir(vmId)
            store.qmpSocket(vmId).delete()
            store.monitorSocket(vmId).delete()
            store.serialFile(vmId).delete()
            vmDir.listFiles { file -> file.name == "serial.log" || file.name == "qemu.log" }
                ?.forEach { it.delete() }

            if (stopRequested) {
                QemuRuntime.post(RunState(RunStatus.IDLE, vmId, config.name))
                return
            }

            val display = findFreeDisplay()
            val spec = QemuArgs.build(this, store, config, display)
            val command = buildCommand(proot, vmId, manifest.entry, spec.args)

            ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(spec.qemuLog)
                .apply {
                    environment().putAll(Bootstrap.prootEnvironment(this@QemuService))
                }
                .start()
                .also { process = it }

            val startedAt = System.currentTimeMillis()
            updateState(
                RunState(
                    status = RunStatus.STARTING,
                    vmId = vmId,
                    vmName = config.name,
                    vncPort = spec.vncPort,
                    message = "正在启动 QEMU…",
                )
            )
            main.post {
                updateNotification("正在启动 ${config.name}…", false)
            }

            val deadline = startedAt + START_TIMEOUT_MS
            var ready = false
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                val p = process ?: break
                if (!p.isAlive) break
                if (canConnect(spec.vncPort)) {
                    ready = true
                    break
                }
                Thread.sleep(400)
            }

            if (!ready) {
                if (!stopRequested) {
                    val p = process
                    val code = runCatching { p?.exitValue() }.getOrNull()
                    val reason = if (p == null || !p.isAlive) {
                        "QEMU 启动失败${code?.let { "（退出码 $it）" } ?: ""}"
                    } else {
                        "VNC 未在 ${START_TIMEOUT_MS / 1000} 秒内就绪"
                    }
                    fail(vmId, config.name, "$reason\n${tail(spec.qemuLog, 4000)}")
                    process?.destroy()
                    return
                }
                return
            }

            updateState(
                RunState(
                    status = RunStatus.RUNNING,
                    vmId = vmId,
                    vmName = config.name,
                    vncPort = spec.vncPort,
                    message = if (config.exposeVnc) "运行中（VNC 已对局域网开放）" else "运行中",
                )
            )
            main.post {
                val text = if (config.exposeVnc) {
                    "VNC: ${lanAddress()}:${spec.vncPort}"
                } else {
                    "本机 VNC: 127.0.0.1:${spec.vncPort}"
                }
                updateNotification("${config.name} · $text", true)
            }

            val p = process
            val exit = p?.waitFor() ?: -1
            if (stopRequested) {
                QemuRuntime.post(RunState(RunStatus.IDLE, vmId, config.name))
            } else {
                val tail = tail(spec.qemuLog, 3000)
                QemuRuntime.post(
                    RunState(
                        RunStatus.ERROR,
                        vmId,
                        config.name,
                        spec.vncPort,
                        "QEMU 已退出（退出码 $exit）\n$tail",
                    )
                )
            }
        } catch (e: Exception) {
            fail(vmId, config.name, e.message ?: e.javaClass.simpleName)
        } finally {
            process?.destroy()
            process = null
            runningVmId = null
            releaseWakeLock()
            main.post {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun buildCommand(
        proot: File,
        vmId: String,
        entry: String,
        qemuArgs: List<String>,
    ): List<String> {
        val rootfs = Bootstrap.rootfs(this)
        val vmDir = store.vmDir(vmId)
        val tmp = Bootstrap.tmpDir(this)
        val command = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r",
            rootfs.absolutePath,
        )
        val binds = mutableListOf(
            "/dev",
            "/proc",
            "/sys",
            "/storage",
        )
        binds.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }
        val external = android.os.Environment.getExternalStorageDirectory()
        if (external != null && external.exists()) {
            command += "-b"
            command += "${external.absolutePath}:/sdcard"
        }
        command += "-b"
        command += "${filesDir.absolutePath}:/app"
        command += "-b"
        command += "${vmDir.absolutePath}:/vm"
        command += "-b"
        command += "${tmp.absolutePath}:/tmp"
        command += "-w"
        command += "/root"
        command += entry
        command += qemuArgs
        return command
    }

    private fun requestStop() {
        stopRequested = true
        val vmId = runningVmId
        val name = runningName
        if (vmId != null) {
            QemuRuntime.post(
                RunState(
                    RunStatus.STOPPING,
                    vmId,
                    name,
                    message = "正在停止…",
                )
            )
        }
        updateNotification("正在停止 $name…", false)
        if (process == null) {
            if (vmId != null) QemuRuntime.post(RunState(RunStatus.IDLE, vmId, name))
            main.post {
                stopForeground(true)
                stopSelf()
            }
            return
        }
        Thread {
            qmpQuit(vmId)
            val p = process
            if (p != null) {
                if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroy()
                    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                    }
                }
            }
        }.start()
    }

    private fun qmpQuit(vmId: String?) {
        if (vmId == null) return
        val socketFile = store.qmpSocket(vmId)
        if (!socketFile.exists()) return
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(
                    socketFile.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                )
            )
            socket.soTimeout = 1500
            socket.outputStream.write(
                "{\"execute\":\"qmp_capabilities\"}\n{\"execute\":\"quit\"}\n".toByteArray()
            )
            socket.outputStream.flush()
            Thread.sleep(150)
        } catch (_: Exception) {
            // Fall back to killing the process.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun fail(vmId: String, vmName: String, message: String) {
        QemuRuntime.post(RunState(RunStatus.ERROR, vmId, vmName, message = message))
    }

    private fun updateState(state: RunState) {
        QemuRuntime.post(state)
    }

    private fun canConnect(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 250)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findFreeDisplay(): Int {
        for (display in 0..59) {
            try {
                ServerSocket(5900 + display, 1, InetAddress.getByName("127.0.0.1")).use { }
                return display
            } catch (_: Exception) {
                // occupied, try the next one
            }
        }
        return 0
    }

    private fun tail(file: File, maxBytes: Int): String {
        if (!file.isFile) return ""
        return runCatching {
            val length = file.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                buffer.toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IA64Emu:qemu").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun lanAddress(): String {
        return runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "0.0.0.0"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "虚拟机运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示 QEMU 虚拟机的运行状态"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, showStop: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("IA64-Emu")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vm)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
        if (showStop) {
            val stopIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, QemuService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "停止",
                    stopIntent,
                ).build()
            )
        }
        return builder.build()
    }

    private fun updateNotification(text: String, showStop: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, showStop))
    }

    override fun onDestroy() {
        stopRequested = true
        process?.destroy()
        process = null
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "cn.moerain.ia64emu.action.START"
        const val ACTION_STOP = "cn.moerain.ia64emu.action.STOP"
        const val EXTRA_VM_ID = "vm_id"
        private const val CHANNEL_ID = "qemu_runtime"
        private const val NOTIFICATION_ID = 42
        private const val START_TIMEOUT_MS = 120_000L
    }
}
