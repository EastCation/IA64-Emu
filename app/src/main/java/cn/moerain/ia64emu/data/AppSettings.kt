package cn.moerain.ia64emu.data

import android.content.Context

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var bootstrapUrl: String
        get() = prefs.getString(KEY_BOOTSTRAP_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOOTSTRAP_URL, value.trim()).apply()

    var lastBootstrapCheck: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    companion object {
        private const val KEY_BOOTSTRAP_URL = "bootstrap_url"
        private const val KEY_LAST_CHECK = "last_bootstrap_check"
    }
}
