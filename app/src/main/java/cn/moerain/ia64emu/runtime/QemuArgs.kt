package cn.moerain.ia64emu.runtime

import android.content.Context
import cn.moerain.ia64emu.bootstrap.Bootstrap
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.data.VmStore
import java.io.File

data class LaunchSpec(
    val args: List<String>,
    val vncPort: Int,
    val qmpSocket: File,
    val serialLog: File,
    val qemuLog: File,
)

object QemuArgs {

    fun build(
        context: Context,
        store: VmStore,
        config: VmConfig,
        vncDisplay: Int,
    ): LaunchSpec {
        val manifest = Bootstrap.manifest(context)
        val datadir = manifest?.datadir ?: "/usr/local/share/qemu"
        val firmware = manifest?.firmware ?: "/usr/local/share/qemu/ia64-firmware.bin"
        val args = mutableListOf<String>()

        args += "-name"
        args += config.name

        val nvram = "/vm/nvram"
        val machine = buildString {
            append(config.machine)
            append(",nvram=")
            append(nvram)
            // firmware-console is defined by the virtual PC machines only.
            if (config.serialConsole && config.machine.contains("vpc")) {
                append(",firmware-console=serial")
            }
        }
        args += "-machine"
        args += machine

        args += "-bios"
        args += firmware

        if (config.cpu.isNotBlank()) {
            args += "-cpu"
            args += config.cpu
        }

        args += "-m"
        args += "${config.memoryMb}M"

        args += "-smp"
        args += config.cpus.toString()

        if (config.cpus > 1) {
            args += "-accel"
            args += "tcg,thread=multi"
        }

        when (config.vga.lowercase()) {
            "quadro2", "nvidia" -> {
                args += "-vga"
                args += "quadro2"
            }
            "none" -> {
                args += "-vga"
                args += "none"
            }
            else -> {
                args += "-vga"
                args += "ati"
                args += "-global"
                args += "ati-vga.model=${config.atiModel}"
                args += "-global"
                args += "ati-vga.xres=${alignWidth(config.xres)}"
                args += "-global"
                args += "ati-vga.yres=${config.yres}"
                args += "-global"
                args += "ati-vga.vgamem_mb=32"
            }
        }

        fun addDrive(path: String, optical: Boolean, id: String) {
            if (path.isBlank() && !optical) return
            val options = mutableListOf<String>()
            if (path.isNotBlank()) {
                val guest = guestPath(context, store, config.id, path)
                options += "file=${escape(guest)}"
                options += "format=${detectFormat(path)}"
            }
            if (optical) {
                options += "media=cdrom"
                options += "readonly=on"
            }
            options += "id=$id"
            when (config.storageBus) {
                "ide" -> options += "if=ide"
                "scsi" -> options += "if=scsi"
            }
            args += "-drive"
            args += options.joinToString(",")
        }

        addDrive(config.disk1, optical = false, id = "hd0")
        addDrive(config.disk2, optical = false, id = "hd1")
        // Keep an empty CD-ROM around even without media so the Monitor and
        // the media dialog can insert an ISO at runtime.
        addDrive(config.cdrom, optical = true, id = "cdrom0")

        if (config.nicEnabled) {
            args += "-nic"
            args += "user,model=${config.nicModel}"
        } else {
            args += "-nic"
            args += "none"
        }

        if (config.cdrom.isNotBlank() && config.disk1.isBlank()) {
            args += "-boot"
            args += "order=d"
        }

        val bind = if (config.exposeVnc) "0.0.0.0" else "127.0.0.1"
        args += "-display"
        args += "vnc=$bind:$vncDisplay"

        // Keep the process (and the VNC display) alive when the guest halts or
        // powers off, so the last screen and serial log remain inspectable.
        args += "-no-shutdown"

        args += "-serial"
        args += "file:/vm/serial.log"

        args += "-monitor"
        args += "unix:/vm/monitor.sock,server,nowait"

        args += "-qmp"
        args += "unix:/vm/qmp.sock,server,nowait"

        args += "-rtc"
        args += "base=localtime"

        args += "-L"
        args += datadir

        args += tokenize(config.extraArgs)

        return LaunchSpec(
            args = args,
            vncPort = 5900 + vncDisplay,
            qmpSocket = store.qmpSocket(config.id),
            serialLog = store.serialFile(config.id),
            qemuLog = store.qemuLogFile(config.id),
        )
    }

    fun guestPath(context: Context, store: VmStore, vmId: String, path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "/vm" || trimmed.startsWith("/vm/")) return trimmed
        if (trimmed == "/sdcard" || trimmed.startsWith("/sdcard/")) return trimmed
        if (trimmed == "/app" || trimmed.startsWith("/app/")) return trimmed

        val vmDir = runCatching { store.vmDir(vmId).canonicalFile }.getOrElse { store.vmDir(vmId) }
        val filesDir = runCatching { context.filesDir.canonicalFile }.getOrElse { context.filesDir }
        val file = runCatching { File(trimmed).canonicalFile }.getOrElse { File(trimmed) }
        val vmPrefix = vmDir.path + File.separator
        val filesPrefix = filesDir.path + File.separator
        return when {
            file.path == vmDir.path -> "/vm"
            file.path.startsWith(vmPrefix) -> "/vm/" + file.path.removePrefix(vmPrefix)
            file.path == filesDir.path -> "/app"
            file.path.startsWith(filesPrefix) -> "/app/" + file.path.removePrefix(filesPrefix)
            file.path == "/storage/emulated/0" -> "/sdcard"
            file.path.startsWith("/storage/emulated/0/") ->
                "/sdcard/" + file.path.removePrefix("/storage/emulated/0/")
            else -> file.path
        }
    }

    fun alignWidth(width: Int): Int = (width / 8) * 8

    fun diskFormat(path: String): String = detectFormat(path)

    private fun detectFormat(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".qcow2") || lower.endsWith(".qcow") -> "qcow2"
            lower.endsWith(".vmdk") -> "vmdk"
            lower.endsWith(".vdi") -> "vdi"
            lower.endsWith(".vhdx") -> "vhdx"
            else -> "raw"
        }
    }

    private fun escape(value: String): String = value.replace(",", ",,")

    fun tokenize(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var hasToken = false
        for (ch in input) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                    hasToken = true
                }
                ch == '\\' -> {
                    escaped = true
                    hasToken = true
                }
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                    hasToken = true
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    hasToken = true
                }
                ch.isWhitespace() -> {
                    if (hasToken) {
                        result += current.toString()
                        current.clear()
                        hasToken = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasToken = true
                }
            }
        }
        if (escaped) current.append('\\')
        if (hasToken) result += current.toString()
        return result
    }
}
