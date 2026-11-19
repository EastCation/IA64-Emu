package cn.moerain.ia64emu.vnc

import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText

/**
 * Invisible EditText that routes the Android soft keyboard (IME) to the guest.
 *
 * Committed text is forwarded as X11 keysyms. IME composition is deliberately
 * not forwarded, so a Chinese/Japanese IME only sends the committed characters
 * instead of leaking pinyin/romaji into the guest.
 */
class VncInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    interface Sink {
        fun sendKey(keysym: Int, down: Boolean)
        fun sendText(text: String)
    }

    var sink: Sink? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isCursorVisible = false
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.TRANSPARENT)
        setHintTextColor(Color.TRANSPARENT)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    Log.i(TAG, "commitText length=${text.length}")
                    sink?.sendText(text.toString())
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Keep composition local to the IME; only committed text is sent.
                return true
            }

            override fun finishComposingText(): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 128)) {
                    sink?.sendKey(KeyMap.XK_BACKSPACE, true)
                    sink?.sendKey(KeyMap.XK_BACKSPACE, false)
                }
                repeat(afterLength.coerceIn(0, 128)) {
                    sink?.sendKey(KeyMap.XK_DELETE, true)
                    sink?.sendKey(KeyMap.XK_DELETE, false)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val keysym = KeyMap.forKeyEvent(event) ?: return true
                Log.d(TAG, "sendKeyEvent key=${event.keyCode} action=${event.action} keysym=$keysym")
                sink?.sendKey(keysym, event.action == KeyEvent.ACTION_DOWN)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                sink?.sendKey(KeyMap.XK_RETURN, true)
                sink?.sendKey(KeyMap.XK_RETURN, false)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = KeyMap.forKeyEvent(event)
        if (keysym != null) {
            Log.d(TAG, "onKeyDown key=$keyCode keysym=$keysym")
            if (event.repeatCount == 0) sink?.sendKey(keysym, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = KeyMap.forKeyEvent(event)
        if (keysym != null) {
            sink?.sendKey(keysym, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun showIme() {
        requestFocus()
        setText("")
        val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIme() {
        val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleIme() {
        val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isActive(this) && hasFocus()) {
            hideIme()
        } else {
            showIme()
        }
    }

    companion object {
        private const val TAG = "IA64IME"
    }
}
