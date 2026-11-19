package cn.moerain.ia64emu.bootstrap

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipFile

data class BootstrapManifest(
    val format: Int,
    val abi: String,
    val qemuVersion: String,
    val builtAt: String,
    val entry: String,
    val datadir: String,
    val firmware: String,
) {
    companion object {
        fun parse(text: String): BootstrapManifest {
            val json = JSONObject(text)
            return BootstrapManifest(
                format = json.optInt("format", 0),
                abi = json.optString("abi"),
                qemuVersion = json.optString("qemuVersion", "unknown"),
                builtAt = json.optString("builtAt", ""),
                entry = json.optString("entry", "/usr/local/bin/qemu-system-ia64"),
                datadir = json.optString("datadir", "/usr/local/share/qemu"),
                firmware = json.optString("firmware", "/usr/local/share/qemu/ia64-firmware.bin"),
            )
        }
    }
}

interface BootstrapListener {
    fun onProgress(message: String, fraction: Int)
    fun onSuccess(manifest: BootstrapManifest)
    fun onError(error: Exception)
}

object Bootstrap {

    const val FORMAT = 1
    private val main = Handler(Looper.getMainLooper())

    fun supportedAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }

    fun bootstrapDir(context: Context): File = File(context.filesDir, "bootstrap")

    fun prootDir(context: Context): File = File(context.filesDir, "proot")

    fun tmpDir(context: Context): File =
        File(context.filesDir, "tmp").also { it.mkdirs() }

    fun manifest(context: Context): BootstrapManifest? {
        val file = File(bootstrapDir(context), "manifest.json")
        if (!file.isFile) return null
        return runCatching { BootstrapManifest.parse(file.readText()) }.getOrNull()
    }

    fun isInstalled(context: Context): Boolean {
        val manifest = manifest(context) ?: return false
        return File(rootfs(context), manifest.entry.trimStart('/')).isFile
    }

    fun rootfs(context: Context): File = File(bootstrapDir(context), "rootfs")

    fun qemuBinary(context: Context): File? {
        val manifest = manifest(context) ?: return null
        val file = File(rootfs(context), manifest.entry.trimStart('/'))
        return if (file.isFile) file else null
    }

    fun installedSize(context: Context): Long {
        val dir = bootstrapDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun isProotInstalled(context: Context): Boolean {
        val abi = supportedAbi() ?: return false
        val dir = File(prootDir(context), abi)
        return File(dir, "proot").isFile &&
            File(dir, "libtalloc.so.2").isFile &&
            File(dir, "libexec/loader").isFile
    }

    fun ensureProot(context: Context, force: Boolean = false): Result<Unit> {
        val abi = supportedAbi()
            ?: return Result.failure(IOException("仅支持 arm64-v8a / x86_64 设备"))
        val dest = File(prootDir(context), abi)
        if (isProotInstalled(context) && !force) return Result.success(Unit)
        return runCatching {
            if (dest.exists()) dest.deleteRecursively()
            if (!dest.exists() && !dest.mkdirs()) throw IOException("无法创建 ${dest.path}")
            copyAssetTree(context, "proot/$abi", dest)
            File(dest, "proot").setExecutable(true, false)
            File(dest, "libexec/loader").setExecutable(true, false)
            File(dest, "libexec/loader32").setExecutable(true, false)
            if (!isProotInstalled(context)) throw IOException("PRoot 文件不完整")
        }
    }

    fun delete(context: Context) {
        bootstrapDir(context).deleteRecursively()
    }

    fun deleteProot(context: Context) {
        prootDir(context).deleteRecursively()
    }

    fun prootExecutable(context: Context): File? {
        val abi = supportedAbi() ?: return null
        val file = File(File(prootDir(context), abi), "proot")
        return if (file.isFile) file else null
    }

    fun prootEnvironment(context: Context): Map<String, String> {
        val abi = supportedAbi() ?: return emptyMap()
        val dir = File(prootDir(context), abi)
        return mapOf(
            "PROOT_TMP_DIR" to tmpDir(context).absolutePath,
            "PROOT_LOADER" to File(dir, "libexec/loader").absolutePath,
            "PROOT_LOADER_32" to File(dir, "libexec/loader32").absolutePath,
            "PROOT_USE_LOADER_FOR_QEMU" to "1",
            "PROOT_NO_SECCOMP" to "1",
            // The first entry is for PRoot's own libtalloc on the Android
            // side. The remaining entries are guest paths and are consumed by
            // the glibc loader inside rootfs.
            "LD_LIBRARY_PATH" to listOf(
                dir.absolutePath,
                "/usr/local/lib",
                "/usr/lib",
                "/usr/lib64",
                "/lib",
                "/lib64",
                "/lib/aarch64-linux-gnu",
                "/usr/lib/aarch64-linux-gnu",
                "/lib/x86_64-linux-gnu",
                "/usr/lib/x86_64-linux-gnu",
            ).joinToString(":"),
            "HOME" to "/root",
            "USER" to "root",
            "TMPDIR" to "/tmp",
            "LANG" to "C",
            "TERM" to "xterm-256color",
        )
    }

    fun installFromUrlAsync(context: Context, url: String, listener: BootstrapListener) {
        val appContext = context.applicationContext
        Thread {
            val cache = File(appContext.cacheDir, "bootstrap-download.zip")
            try {
                if (url.isBlank()) throw IOException("请填写引导包下载地址")
                post { listener.onProgress("正在连接…", -1) }
                download(url, cache) { read, total ->
                    val fraction = if (total > 0) ((read * 100) / total).toInt() else -1
                    post { listener.onProgress("正在下载… ${humanSize(read)}", fraction) }
                }
                verifyChecksum(url, cache)
                installZip(appContext, cache, listener)
            } catch (e: Exception) {
                post { listener.onError(e) }
            } finally {
                cache.delete()
            }
        }.start()
    }

    fun installFromUriAsync(context: Context, uri: Uri, listener: BootstrapListener) {
        val appContext = context.applicationContext
        Thread {
            val cache = File(appContext.cacheDir, "bootstrap-local.zip")
            try {
                post { listener.onProgress("正在读取 ZIP…", -1) }
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cache).use { output -> input.copyTo(output, 64 * 1024) }
                } ?: throw IOException("无法读取所选文件")
                installZip(appContext, cache, listener)
            } catch (e: Exception) {
                post { listener.onError(e) }
            } finally {
                cache.delete()
            }
        }.start()
    }

    private fun installZip(context: Context, zipFile: File, listener: BootstrapListener) {
        if (!zipFile.isFile || zipFile.length() == 0L) throw IOException("ZIP 文件为空")
        val target = bootstrapDir(context)
        val staging = File(context.filesDir, "bootstrap.staging")
        val backup = File(context.filesDir, "bootstrap.old")
        if (staging.exists()) staging.deleteRecursively()
        backup.deleteRecursively()
        staging.mkdirs()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val total = entries.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
            var done = 0L
            entries.forEachIndexed { index, entry ->
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.isEmpty()) return@forEachIndexed
                val outFile = File(staging, name)
                val canonical = outFile.canonicalFile
                if (!canonical.path.startsWith(staging.canonicalFile.path)) {
                    throw IOException("ZIP 包含非法路径: ${entry.name}")
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(canonical).use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                    canonical.setExecutable(true, false)
                    done += entry.size.coerceAtLeast(0L)
                    val fraction = if (total > 0) ((done * 100) / total).toInt() else index
                    post { listener.onProgress("正在解压… ${entry.name}", fraction) }
                }
            }
        }

        val manifestFile = File(staging, "manifest.json")
        if (!manifestFile.isFile) throw IOException("ZIP 中缺少 manifest.json")
        val manifest = BootstrapManifest.parse(manifestFile.readText())
        val abi = supportedAbi() ?: throw IOException("设备 ABI 不受支持")
        if (manifest.format != FORMAT) throw IOException("引导包格式不兼容: ${manifest.format}")
        if (manifest.abi != abi) throw IOException("引导包 ABI (${manifest.abi}) 与设备 ($abi) 不匹配")
        val entry = File(File(staging, "rootfs"), manifest.entry.trimStart('/'))
        if (!entry.isFile) throw IOException("引导包缺少 QEMU: ${manifest.entry}")
        val firmware = File(File(staging, "rootfs"), manifest.firmware.trimStart('/'))
        if (!firmware.isFile) throw IOException("引导包缺少 IA-64 固件: ${manifest.firmware}")

        if (target.exists() && !target.renameTo(backup)) {
            throw IOException("无法替换旧引导包")
        }
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        backup.deleteRecursively()
        post { listener.onSuccess(manifest) }
    }

    private fun download(url: String, target: File, progress: (Long, Long) -> Unit) {
        var current = URL(url)
        var redirects = 0
        var connection: HttpURLConnection? = null
        while (redirects < 8) {
            connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "IA64Emu/1.0")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: break
                current = URL(current, location)
                connection.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${connection.responseMessage}")
            }
            break
        }
        val conn = connection ?: throw IOException("无法建立连接")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                var readTotal = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    readTotal += count
                    progress(readTotal, total)
                }
            }
        }
        conn.disconnect()
    }

    private fun verifyChecksum(url: String, file: File) {
        val expected = fetchText("$url.sha256")
            ?.let { Regex("[0-9a-fA-F]{64}").find(it)?.value?.lowercase() }
            ?: return
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        if (actual != expected) {
            throw IOException("SHA-256 校验失败（期望 $expected，实际 $actual）")
        }
    }

    private fun fetchText(url: String): String? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IA64Emu/1.0")
            }
            try {
                if (connection.responseCode != 200) return null
                connection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    val count = input.read(buffer)
                    if (count <= 0) null else String(buffer, 0, count, Charsets.US_ASCII)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output, 64 * 1024) }
            }
        } else {
            dest.mkdirs()
            children.forEach { child ->
                copyAssetTree(context, "$assetPath/$child", File(dest, child))
            }
        }
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.size - 1) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}
