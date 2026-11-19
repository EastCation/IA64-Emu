package cn.moerain.ia64emu.runtime

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import cn.moerain.ia64emu.data.VmStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Minimal QMP client for runtime media changes.
 *
 * The VM monitor uses HMP on monitor.sock, while QMP is used here because it
 * returns structured errors and has an explicit blockdev-change-medium
 * command.
 */
class QmpClient(
    private val context: Context,
    private val store: VmStore,
) {

    private val main = Handler(Looper.getMainLooper())

    fun changeMedium(vmId: String, hostPath: String, callback: (Result<Unit>) -> Unit) {
        val guest = QemuArgs.guestPath(context, store, vmId, hostPath)
        val args = JSONObject().apply {
            put("device", CDROM_ID)
            put("filename", guest)
            put("format", QemuArgs.diskFormat(hostPath))
            put("read-only-mode", "read-only")
        }
        execute(
            vmId,
            JSONObject().put("execute", "blockdev-change-medium").put("arguments", args),
            callback,
        )
    }

    fun eject(vmId: String, callback: (Result<Unit>) -> Unit) {
        val args = JSONObject().apply {
            put("device", CDROM_ID)
            put("force", true)
        }
        execute(vmId, JSONObject().put("execute", "eject").put("arguments", args), callback)
    }

    private fun execute(vmId: String, command: JSONObject, callback: (Result<Unit>) -> Unit) {
        val socketFile = store.qmpSocket(vmId)
        if (!socketFile.exists()) {
            callback(Result.failure(IOException("QMP 套接字不存在，虚拟机可能未运行")))
            return
        }
        Thread {
            val socket = LocalSocket()
            try {
                socket.connect(
                    LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))

                reader.readLine() // QMP greeting
                send(writer, JSONObject().put("execute", "qmp_capabilities"))
                expectSucceeded(reader)
                send(writer, command)
                expectSucceeded(reader)

                main.post { callback(Result.success(Unit)) }
            } catch (e: Exception) {
                main.post {
                    callback(
                        Result.failure(
                            e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName)
                        )
                    )
                }
            } finally {
                runCatching { socket.close() }
            }
        }.start()
    }

    private fun send(writer: BufferedWriter, json: JSONObject) {
        writer.write(json.toString())
        writer.write("\n")
        writer.flush()
    }

    private fun expectSucceeded(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: throw IOException("QMP 连接已关闭")
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.has("error")) {
                val error = json.optJSONObject("error")
                throw IOException(error?.optString("desc")?.ifBlank { "QMP 命令失败" } ?: "QMP 命令失败")
            }
            if (json.has("return")) return
        }
    }

    companion object {
        const val CDROM_ID = "cdrom0"
    }
}
