package cn.moerain.ia64emu.data

import android.content.Context
import org.json.JSONObject
import java.io.File

class VmStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "vms").also { if (!it.exists()) it.mkdirs() }

    fun list(): List<VmConfig> = synchronized(this) {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { readConfig(File(dir, CONFIG_NAME)) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun get(id: String): VmConfig? = synchronized(this) {
        val file = File(vmDir(id), CONFIG_NAME)
        if (!file.isFile) null else runCatching { readConfig(file) }.getOrNull()
    }

    fun save(config: VmConfig): VmConfig = synchronized(this) {
        val dir = vmDir(config.id)
        if (!dir.exists()) dir.mkdirs()
        val updated = config.copy(updatedAt = System.currentTimeMillis())
        val tmp = File(dir, "$CONFIG_NAME.tmp")
        tmp.writeText(updated.toJson().toString(2))
        val target = File(dir, CONFIG_NAME)
        if (target.exists() && !target.delete()) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        } else if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        updated
    }

    fun delete(id: String): Boolean = synchronized(this) {
        vmDir(id).deleteRecursively()
    }

    fun vmDir(id: String): File = File(root, id)

    fun nvramFile(id: String): File = File(vmDir(id), "nvram")

    fun serialFile(id: String): File = File(vmDir(id), "serial.log")

    fun qemuLogFile(id: String): File = File(vmDir(id), "qemu.log")

    fun qmpSocket(id: String): File = File(vmDir(id), "qmp.sock")

    fun monitorSocket(id: String): File = File(vmDir(id), "monitor.sock")

    fun importsDir(id: String): File = File(vmDir(id), "imports").also { it.mkdirs() }

    fun diskUsageBytes(): Long {
        val dir = File(context.filesDir, "vms")
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun readConfig(file: File): VmConfig =
        VmConfig.fromJson(JSONObject(file.readText()))

    companion object {
        private const val CONFIG_NAME = "config.json"
    }
}
