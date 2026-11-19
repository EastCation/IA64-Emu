package cn.moerain.ia64emu.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.vnc.KeyMap
import cn.moerain.ia64emu.vnc.VncView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ExtraKeysDialog {

    private data class Key(val label: String, val keysym: Int)

    fun show(context: Context, vncView: VncView) {
        val density = context.resources.displayMetrics.density
        val pad = (6 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
        }

        val hint = TextView(context).apply {
            text = context.getString(R.string.modifier_hint)
            textSize = 12f
            setPadding(0, 0, 0, pad)
        }
        root.addView(hint)

        val grid = GridLayout(context).apply {
            columnCount = 4
        }
        root.addView(grid)

        val modifiers = LinkedHashMap<Int, MaterialButton>()
        fun addButton(label: String, onClick: () -> Unit, checkable: Boolean = false): MaterialButton {
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setPadding(pad, pad, pad, pad)
                isCheckable = checkable
                setOnClickListener { onClick() }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(pad / 2, pad / 2, pad / 2, pad / 2)
            }
            grid.addView(button, params)
            return button
        }

        fun sendWithModifiers(keysym: Int) {
            val pressed = modifiers.filterValues { it.isChecked }.keys.toList()
            pressed.forEach { vncView.sendKey(it, true) }
            vncView.sendKeyTap(keysym)
            pressed.reversed().forEach { vncView.sendKey(it, false) }
            modifiers.values.forEach { it.isChecked = false }
        }

        modifiers[KeyMap.XK_CONTROL_L] = addButton(
            label = "Ctrl",
            onClick = {},
            checkable = true,
        )
        modifiers[KeyMap.XK_ALT_L] = addButton(
            label = "Alt",
            onClick = {},
            checkable = true,
        )
        modifiers[KeyMap.XK_SHIFT_L] = addButton(
            label = "Shift",
            onClick = {},
            checkable = true,
        )
        modifiers[KeyMap.XK_SUPER_L] = addButton(
            label = "Super",
            onClick = {},
            checkable = true,
        )

        val keys = listOf(
            Key("Esc", KeyMap.XK_ESCAPE),
            Key("Tab", KeyMap.XK_TAB),
            Key("Enter", KeyMap.XK_RETURN),
            Key("Back", KeyMap.XK_BACKSPACE),
            Key("Del", KeyMap.XK_DELETE),
            Key("Ins", KeyMap.XK_INSERT),
            Key("Home", KeyMap.XK_HOME),
            Key("End", KeyMap.XK_END),
            Key("PgUp", KeyMap.XK_PAGE_UP),
            Key("PgDn", KeyMap.XK_PAGE_DOWN),
            Key("←", KeyMap.XK_LEFT),
            Key("↑", KeyMap.XK_UP),
            Key("↓", KeyMap.XK_DOWN),
            Key("→", KeyMap.XK_RIGHT),
        ) + (0..11).map { index ->
            Key("F${index + 1}", KeyMap.XK_F1 + index)
        }

        keys.forEach { key ->
            addButton(
                label = key.label,
                onClick = { sendWithModifiers(key.keysym) },
            )
        }
        addButton(
            label = "Ctrl+Alt+Del",
            onClick = {
                vncView.sendCombo(KeyMap.XK_CONTROL_L, KeyMap.XK_ALT_L, KeyMap.XK_DELETE)
            },
        )

        val scroll = ScrollView(context).apply {
            addView(root)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.extra_keys_title)
            .setView(scroll)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }
}
