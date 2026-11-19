package cn.moerain.ia64emu.vnc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var statusListener: ((String) -> Unit)? = null

    private var client: RfbClient? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressFired = false
    private var multiTouch = false
    private var multiLastY = 0f

    private val longPress = Runnable {
        if (!dragging && !multiTouch) {
            longPressFired = true
            val p = mapPoint(downX, downY) ?: return@Runnable
            client?.sendPointer(4, p.first, p.second)
            client?.sendPointer(0, p.first, p.second)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xff000000.toInt())
    }

    fun connect(host: String, port: Int) {
        disconnect()
        val rfb = RfbClient(host, port, object : RfbClient.Listener {
            override fun onConnected(width: Int, height: Int, name: String) {
                statusListener?.invoke("")
                postInvalidate()
            }

            override fun onFrame() {
                postInvalidate()
            }

            override fun onClosed(message: String) {
                statusListener?.invoke(message)
            }
        })
        client = rfb
        rfb.connect()
        requestFocus()
    }

    fun disconnect() {
        client?.stop()
        client = null
        handler.removeCallbacks(longPress)
        statusListener?.invoke("")
        postInvalidate()
    }

    fun sendKey(keysym: Int, down: Boolean) {
        client?.sendKey(keysym, down)
    }

    fun sendKeyTap(keysym: Int) {
        client?.sendKeyTap(keysym)
    }

    fun sendCombo(vararg keysyms: Int) {
        client?.sendCombo(*keysyms)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rfb = client ?: return
        synchronized(rfb.bitmapLock) {
            val bmp = rfb.bitmap ?: return
            updateDstRect(bmp)
            canvas.drawBitmap(bmp, null, dst, paint)
        }
    }

    private fun updateDstRect(bmp: Bitmap) {
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dst.set(left, top, left + w, top + h)
    }

    private fun mapPoint(x: Float, y: Float): Pair<Int, Int>? {
        val rfb = client ?: return null
        val bmp = synchronized(rfb.bitmapLock) { rfb.bitmap } ?: return null
        if (dst.width() <= 0f || dst.height() <= 0f) return null
        val rx = ((x - dst.left) / dst.width() * bmp.width).toInt()
        val ry = ((y - dst.top) / dst.height() * bmp.height).toInt()
        return rx.coerceIn(0, bmp.width - 1) to ry.coerceIn(0, bmp.height - 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rfb = client ?: return false
        val bmp = synchronized(rfb.bitmapLock) { rfb.bitmap } ?: return false
        if (dst.width() <= 0f) updateDstRect(bmp)

        if (event.pointerCount >= 2) {
            handler.removeCallbacks(longPress)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    dragging = false
                    multiLastY = averageY(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    val y = averageY(event)
                    val delta = multiLastY - y
                    if (abs(delta) >= 18f) {
                        val point = mapPoint(event.x, event.y) ?: return true
                        val mask = if (delta > 0) 8 else 16
                        rfb.sendPointer(mask, point.first, point.second)
                        rfb.sendPointer(0, point.first, point.second)
                        multiLastY = y
                    }
                }
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    multiTouch = false
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                longPressFired = false
                multiTouch = false
                handler.postDelayed(longPress, 550)
                mapPoint(event.x, event.y)?.let { rfb.sendPointer(0, it.first, it.second) }
                requestFocus()
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) return true
                if (!dragging &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (dragging) {
                    mapPoint(event.x, event.y)?.let { rfb.sendPointer(1, it.first, it.second) }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val point = mapPoint(event.x, event.y)
                if (point != null) {
                    when {
                        longPressFired -> rfb.sendPointer(0, point.first, point.second)
                        dragging -> {
                            rfb.sendPointer(1, point.first, point.second)
                            rfb.sendPointer(0, point.first, point.second)
                        }
                        else -> {
                            rfb.sendPointer(1, point.first, point.second)
                            rfb.sendPointer(0, point.first, point.second)
                        }
                    }
                }
                dragging = false
                longPressFired = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                dragging = false
                longPressFired = false
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val value = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (value != 0f) {
                val point = mapPoint(event.x, event.y) ?: return true
                val mask = if (value > 0) 8 else 16
                client?.sendPointer(mask, point.first, point.second)
                client?.sendPointer(0, point.first, point.second)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            val keysym = KeyMap.forKeyEvent(event)
            if (keysym != null) {
                client?.sendKey(keysym, true)
                return true
            }
        } else {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = KeyMap.forKeyEvent(event)
        if (keysym != null) {
            client?.sendKey(keysym, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / max(1, event.pointerCount)
    }
}
