package cn.moerain.ia64emu.vnc

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class RfbClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(width: Int, height: Int, name: String)
        fun onFrame()
        fun onClosed(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val writeLock = Any()

    val bitmapLock = Any()

    @Volatile
    var bitmap: Bitmap? = null
        private set

    @Volatile
    private var running = true

    @Volatile
    private var socket: Socket? = null

    private var output: DataOutputStream? = null
    private var thread: Thread? = null

    fun connect() {
        if (thread != null) return
        thread = Thread({ run() }, "rfb-client").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
        thread = null
    }

    fun sendPointer(mask: Int, x: Int, y: Int) {
        val out = output ?: return
        try {
            synchronized(writeLock) {
                out.writeByte(5)
                out.writeByte(mask)
                out.writeShort(x.coerceIn(0, 0xffff))
                out.writeShort(y.coerceIn(0, 0xffff))
                out.flush()
            }
        } catch (_: IOException) {
            closeQuietly()
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        if (keysym == 0) return
        val out = output ?: return
        try {
            synchronized(writeLock) {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeByte(0)
                out.writeInt(keysym)
                out.flush()
            }
        } catch (_: IOException) {
            closeQuietly()
        }
    }

    fun sendKeyTap(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    fun sendCombo(vararg keysyms: Int) {
        keysyms.forEach { sendKey(it, true) }
        keysyms.reversed().forEach { sendKey(it, false) }
    }

    private fun run() {
        var socket: Socket? = null
        try {
            socket = connectSocket()
            this.socket = socket
            socket.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            output = out

            val version = ByteArray(12)
            input.readFully(version)
            val versionText = String(version, Charsets.US_ASCII)
            if (!versionText.startsWith("RFB ")) {
                throw IOException("不是 VNC 服务: $versionText")
            }
            val serverMinor = versionText.substring(8, 11).toIntOrNull() ?: 8
            val minor = serverMinor.coerceIn(3, 8)
            out.write("RFB 003.00$minor\n".toByteArray(Charsets.US_ASCII))
            out.flush()

            if (minor >= 7) {
                val count = input.readUnsignedByte()
                if (count == 0) {
                    throw IOException("VNC 拒绝连接: ${readReason(input)}")
                }
                val types = IntArray(count) { input.readUnsignedByte() }
                if (types.contains(1)) {
                    out.writeByte(1)
                } else if (types.contains(2)) {
                    throw IOException("该 VNC 服务器要求密码，当前版本暂不支持")
                } else {
                    throw IOException("不支持的 VNC 认证类型: ${types.joinToString()}")
                }
                out.flush()
                // RFB 3.8 sends a SecurityResult even for the None type.
                // QEMU does this as well; consuming it is required before
                // ClientInit, otherwise ServerInit is misaligned.
                if (minor >= 8) {
                    val result = input.readInt()
                    if (result != 0) {
                        throw IOException("VNC 认证失败: ${readReason(input)}")
                    }
                }
            } else {
                val type = input.readInt()
                if (type != 1) {
                    throw IOException("不支持的 VNC 认证类型: $type")
                }
            }

            out.writeByte(1) // shared
            out.flush()

            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            val serverPf = ByteArray(16)
            input.readFully(serverPf)
            val nameLength = input.readInt().coerceIn(0, 4096)
            val nameBytes = ByteArray(nameLength)
            input.readFully(nameBytes)
            val name = String(nameBytes, Charsets.ISO_8859_1)

            resize(width, height)
            sendSetPixelFormat()
            sendSetEncodings()
            sendFramebufferUpdateRequest(incremental = false, width, height)
            Log.i(TAG, "connected ${width}x$height name=$name")

            main.post { listener.onConnected(width, height, name) }

            var frames = 0
            while (running) {
                val type = input.readUnsignedByte()
                when (type) {
                    0 -> {
                        input.readUnsignedByte()
                        val count = input.readUnsignedShort()
                        repeat(count) {
                            val x = input.readUnsignedShort()
                            val y = input.readUnsignedShort()
                            val w = input.readUnsignedShort()
                            val h = input.readUnsignedShort()
                            val encoding = input.readInt()
                            when (encoding) {
                                0 -> readRaw(input, x, y, w, h)
                                1 -> readCopyRect(input, x, y, w, h)
                                -223 -> {
                                    resize(w, h)
                                    sendFramebufferUpdateRequest(false, w, h)
                                    return@repeat
                                }
                                else -> throw IOException("服务器使用了未请求的编码: $encoding")
                            }
                        }
                        frames++
                        if (frames == 1 || frames % 120 == 0) {
                            Log.d(TAG, "framebuffer update #$frames")
                        }
                        main.post { listener.onFrame() }
                        val bmp = bitmap
                        sendFramebufferUpdateRequest(
                            incremental = true,
                            width = bmp?.width ?: width,
                            height = bmp?.height ?: height,
                        )
                    }
                    1 -> {
                        input.readUnsignedByte()
                        val count = input.readUnsignedShort()
                        input.skipBytes(count * 6)
                    }
                    2 -> Unit
                    3 -> {
                        input.readUnsignedByte()
                        input.readUnsignedByte()
                        input.readUnsignedByte()
                        val length = input.readInt().coerceAtLeast(0)
                        input.skipBytes(length)
                    }
                    else -> throw IOException("未知的 VNC 消息: $type")
                }
            }
        } catch (e: Exception) {
            if (running) {
                val message = when (e) {
                    is EOFException -> "VNC 连接已关闭"
                    is java.net.ConnectException -> "无法连接 VNC: ${e.message ?: host}"
                    else -> e.message ?: e.javaClass.simpleName
                }
                Log.w(TAG, "VNC closed: $message", e)
                main.post { listener.onClosed(message) }
            }
        } finally {
            closeQuietly()
        }
    }

    private fun connectSocket(): Socket {
        var last: IOException? = null
        repeat(4) { attempt ->
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2500)
                return socket
            } catch (e: IOException) {
                last = e
                if (attempt < 3) Thread.sleep(700)
            }
        }
        throw last ?: IOException("无法连接 $host:$port")
    }

    private fun readReason(input: DataInputStream): String {
        val length = runCatching { input.readInt() }.getOrDefault(0).coerceIn(0, 4096)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun readRaw(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val pixels = w.toLong() * h.toLong()
        if (pixels <= 0L) return
        if (pixels > 8L * 1024L * 1024L) {
            throw IOException("VNC 画面过大: ${w}x$h")
        }
        val bytesNeeded = (pixels * 4L).toInt()
        val byteBuffer = ByteArray(bytesNeeded)
        val intBuffer = IntArray(pixels.toInt())
        input.readFully(byteBuffer, 0, bytesNeeded)
        for (i in 0 until pixels.toInt()) {
            val b = byteBuffer[i * 4].toInt() and 0xff
            val g = byteBuffer[i * 4 + 1].toInt() and 0xff
            val r = byteBuffer[i * 4 + 2].toInt() and 0xff
            intBuffer[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        synchronized(bitmapLock) {
            val bmp = bitmap ?: return
            val cw = minOf(w, bmp.width - x)
            val ch = minOf(h, bmp.height - y)
            if (cw <= 0 || ch <= 0) return
            bmp.setPixels(intBuffer, 0, w, x, y, cw, ch)
        }
    }

    private fun readCopyRect(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        synchronized(bitmapLock) {
            val bmp = bitmap ?: return
            if (srcX + w > bmp.width || srcY + h > bmp.height) return
            if (x + w > bmp.width || y + h > bmp.height) return
            val tmp = IntArray(w * h)
            bmp.getPixels(tmp, 0, w, srcX, srcY, w, h)
            bmp.setPixels(tmp, 0, w, x, y, w, h)
        }
    }

    private fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(bitmapLock) {
            val old = bitmap
            if (old != null && old.width == width && old.height == height) return
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun sendSetPixelFormat() {
        val out = output ?: return
        synchronized(writeLock) {
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(32)
            out.writeByte(24)
            out.writeByte(0)
            out.writeByte(1)
            out.writeShort(255)
            out.writeShort(255)
            out.writeShort(255)
            out.writeByte(16)
            out.writeByte(8)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.flush()
        }
    }

    private fun sendSetEncodings() {
        val out = output ?: return
        synchronized(writeLock) {
            out.writeByte(2)
            out.writeByte(0)
            out.writeShort(3)
            out.writeInt(0) // Raw
            out.writeInt(1) // CopyRect
            out.writeInt(-223) // DesktopSize
            out.flush()
        }
    }

    private fun sendFramebufferUpdateRequest(incremental: Boolean, width: Int, height: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            out.writeByte(3)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(width.coerceIn(0, 0xffff))
            out.writeShort(height.coerceIn(0, 0xffff))
            out.flush()
        }
    }

    private fun closeQuietly() {
        running = false
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    companion object {
        private const val TAG = "IA64VNC"
    }
}
