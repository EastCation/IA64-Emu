package cn.moerain.ia64emu.vnc

import android.view.KeyEvent

data class CharKey(val keysym: Int, val shift: Boolean)

object KeyMap {

    const val XK_SHIFT_L = 0xffe1
    const val XK_CONTROL_L = 0xffe3
    const val XK_ALT_L = 0xffe9
    const val XK_SUPER_L = 0xffeb
    const val XK_RETURN = 0xff0d
    const val XK_ESCAPE = 0xff1b
    const val XK_TAB = 0xff09
    const val XK_BACKSPACE = 0xff08
    const val XK_DELETE = 0xffff
    const val XK_INSERT = 0xff63
    const val XK_HOME = 0xff50
    const val XK_END = 0xff57
    const val XK_PAGE_UP = 0xff55
    const val XK_PAGE_DOWN = 0xff56
    const val XK_LEFT = 0xff51
    const val XK_UP = 0xff52
    const val XK_RIGHT = 0xff53
    const val XK_DOWN = 0xff54
    const val XK_F1 = 0xffbe

    fun forKeyEvent(event: KeyEvent): Int? =
        forKeyCode(event.keyCode, event.unicodeChar)

    /**
     * Maps a character to an X11 keysym plus the Shift modifier that a US
     * keyboard would need. Used by the text-input dialog.
     */
    fun forChar(ch: Char): CharKey? {
        when (ch) {
            '\r' -> return null
            '\n' -> return CharKey(XK_RETURN, false)
            '\t' -> return CharKey(XK_TAB, false)
            '\b' -> return CharKey(XK_BACKSPACE, false)
            ' ' -> return CharKey(0x20, false)
        }
        if (ch in 'a'..'z') return CharKey(ch.code, false)
        if (ch in 'A'..'Z') return CharKey(ch.lowercaseChar().code, true)
        if (ch in '0'..'9') return CharKey(ch.code, false)

        val shifted = mapOf(
            '!' to '1', '@' to '2', '#' to '3', '$' to '4', '%' to '5',
            '^' to '6', '&' to '7', '*' to '8', '(' to '9', ')' to '0',
            '_' to '-', '+' to '=', '{' to '[', '}' to ']', '|' to '\\',
            ':' to ';', '"' to '\'', '<' to ',', '>' to '.', '?' to '/',
            '~' to '`',
        )
        shifted[ch]?.let { return CharKey(it.code, true) }

        val unshifted = "`-=[]\\;',./"
        if (ch in unshifted) return CharKey(ch.code, false)
        if (ch.code in 0x20..0x7e) return CharKey(ch.code, false)
        if (ch.code in 0x80..0xff) return CharKey(ch.code, false)
        return CharKey(0x01000000 or ch.code, false)
    }

    fun forKeyCode(keyCode: Int, unicodeChar: Int = 0): Int? {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && unicodeChar == 0) return null
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return 0x61 + (keyCode - KeyEvent.KEYCODE_A)
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return 0x30 + (keyCode - KeyEvent.KEYCODE_0)
        }
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            return XK_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> return 0x20
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> return XK_RETURN
            KeyEvent.KEYCODE_ESCAPE -> return XK_ESCAPE
            KeyEvent.KEYCODE_TAB -> return XK_TAB
            KeyEvent.KEYCODE_DEL -> return XK_BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> return XK_DELETE
            KeyEvent.KEYCODE_INSERT -> return XK_INSERT
            KeyEvent.KEYCODE_MOVE_HOME -> return XK_HOME
            KeyEvent.KEYCODE_MOVE_END -> return XK_END
            KeyEvent.KEYCODE_PAGE_UP -> return XK_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> return XK_PAGE_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> return XK_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> return XK_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> return XK_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> return XK_DOWN
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT -> return XK_SHIFT_L
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT -> return XK_CONTROL_L
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT -> return XK_ALT_L
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT -> return XK_SUPER_L
            KeyEvent.KEYCODE_CAPS_LOCK -> return 0xffe5
            KeyEvent.KEYCODE_NUM_LOCK -> return 0xff7f
            KeyEvent.KEYCODE_SYSRQ -> return 0xff61
            KeyEvent.KEYCODE_MENU -> return 0xff67
            KeyEvent.KEYCODE_AT -> return 0x40
            KeyEvent.KEYCODE_POUND -> return 0x23
            KeyEvent.KEYCODE_BREAK -> return 0xff6b
            KeyEvent.KEYCODE_SCROLL_LOCK -> return 0xff14
            KeyEvent.KEYCODE_COMMA -> return 0x2c
            KeyEvent.KEYCODE_PERIOD -> return 0x2e
            KeyEvent.KEYCODE_SLASH -> return 0x2f
            KeyEvent.KEYCODE_BACKSLASH -> return 0x5c
            KeyEvent.KEYCODE_SEMICOLON -> return 0x3b
            KeyEvent.KEYCODE_APOSTROPHE -> return 0x27
            KeyEvent.KEYCODE_LEFT_BRACKET -> return 0x5b
            KeyEvent.KEYCODE_RIGHT_BRACKET -> return 0x5d
            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> return 0x2d
            KeyEvent.KEYCODE_EQUALS -> return 0x3d
            KeyEvent.KEYCODE_NUMPAD_ADD -> return 0x2b
            KeyEvent.KEYCODE_GRAVE -> return 0x60
            KeyEvent.KEYCODE_STAR -> return 0x2a
            KeyEvent.KEYCODE_PLUS -> return 0x2b
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> return 0x2f
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> return 0x2a
            KeyEvent.KEYCODE_NUMPAD_DOT -> return 0x2e
            KeyEvent.KEYCODE_NUMPAD_0 -> return 0x30
            KeyEvent.KEYCODE_NUMPAD_1 -> return 0x31
            KeyEvent.KEYCODE_NUMPAD_2 -> return 0x32
            KeyEvent.KEYCODE_NUMPAD_3 -> return 0x33
            KeyEvent.KEYCODE_NUMPAD_4 -> return 0x34
            KeyEvent.KEYCODE_NUMPAD_5 -> return 0x35
            KeyEvent.KEYCODE_NUMPAD_6 -> return 0x36
            KeyEvent.KEYCODE_NUMPAD_7 -> return 0x37
            KeyEvent.KEYCODE_NUMPAD_8 -> return 0x38
            KeyEvent.KEYCODE_NUMPAD_9 -> return 0x39
        }
        if (unicodeChar in 0x20..0x7e) return unicodeChar
        if (unicodeChar in 0xa0..0xff) return unicodeChar
        if (unicodeChar > 0xff) return 0x01000000 or unicodeChar
        return null
    }
}
