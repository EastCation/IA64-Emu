package cn.moerain.ia64emu.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

/**
 * HMP (QEMU monitor) client over a Unix socket.
 */
class MonitorClient(
    private val socketFile: File,
    private val listener: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val writeLock = Any()

    @Volatile
    private var running = true

    private var socket: LocalSocket? = null
    @Volatile
    private var output: BufferedOutputStream? = null
    private var thread: Thread? = null
    private var writerThread: Thread? = null
    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    fun connect() {
        if (thread != null) return
        thread = Thread({ run() }, "qemu-monitor").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun send(command: String) {
        if (output == null) {
            listener("监控器尚未连接。\n")
            return
        }
        val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
        writeQueue.offer(bytes)
        startWriter()
    }

    private fun startWriter() {
        if (writerThread != null) return
        writerThread = Thread({
            while (running) {
                val data = try {
                    writeQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    synchronized(writeLock) {
                        val out = output ?: break
                        out.write(data)
                        out.flush()
                    }
                } catch (e: IOException) {
                    listener("\n发送失败: ${e.message}\n")
                    break
                }
            }
        }, "qemu-monitor-writer").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun close() {
        running = false
        writerThread?.interrupt()
        writerThread = null
        writeQueue.clear()
        runCatching { socket?.close() }
        thread = null
        socket = null
        output = null
    }

    private fun run() {
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
            )
            this.socket = socket
            output = BufferedOutputStream(socket.outputStream)
            val input = socket.inputStream
            val buffer = ByteArray(8192)
            while (running) {
                val count = input.read(buffer)
                if (count < 0) break
                val text = String(buffer, 0, count, Charsets.UTF_8)
                main.post { listener(text) }
            }
        } catch (e: Exception) {
            if (running) {
                main.post { listener("\n无法连接 QEMU Monitor: ${e.message ?: e.javaClass.simpleName}\n") }
            }
        } finally {
            runCatching { socket.close() }
            output = null
        }
    }
}
