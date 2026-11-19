package cn.moerain.ia64emu.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import cn.moerain.ia64emu.R
import cn.moerain.ia64emu.data.VmConfig
import cn.moerain.ia64emu.runtime.QemuService
import cn.moerain.ia64emu.runtime.RunState
import cn.moerain.ia64emu.runtime.RunStatus
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object Util {

    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun statusText(context: Context, status: RunStatus): String = when (status) {
        RunStatus.IDLE -> context.getString(R.string.status_stopped)
        RunStatus.STARTING -> context.getString(R.string.status_starting)
        RunStatus.RUNNING -> context.getString(R.string.status_running)
        RunStatus.STOPPING -> context.getString(R.string.status_stopping)
        RunStatus.ERROR -> context.getString(R.string.status_error)
    }

    fun statusColor(context: Context, status: RunStatus): Int = when (status) {
        RunStatus.RUNNING -> ContextCompat.getColor(context, R.color.status_running)
        RunStatus.STARTING, RunStatus.STOPPING ->
            ContextCompat.getColor(context, R.color.status_starting)
        RunStatus.ERROR -> ContextCompat.getColor(context, R.color.status_error)
        RunStatus.IDLE -> ContextCompat.getColor(context, R.color.on_surface)
    }

    fun startVm(context: Context, vmId: String) {
        val intent = Intent(context, QemuService::class.java)
            .setAction(QemuService.ACTION_START)
            .putExtra(QemuService.EXTRA_VM_ID, vmId)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopVm(context: Context) {
        val intent = Intent(context, QemuService::class.java)
            .setAction(QemuService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }

    fun vncEndpoint(config: VmConfig, state: RunState): Pair<String, Int>? {
        val address = config.vncAddress.trim()
        if (address.isNotEmpty()) {
            val colon = address.lastIndexOf(':')
            return if (colon > 0) {
                val host = address.substring(0, colon)
                val port = address.substring(colon + 1).toIntOrNull() ?: 5900
                host to port
            } else {
                address to 5900
            }
        }
        if (state.status != RunStatus.RUNNING || state.vmId != config.id) return null
        return "127.0.0.1" to state.vncPort
    }

    fun vncAddressString(config: VmConfig, state: RunState): String? {
        val endpoint = vncEndpoint(config, state) ?: return null
        return if (config.exposeVnc && config.vncAddress.isBlank()) {
            "${lanAddress()}:${endpoint.second}"
        } else {
            "${endpoint.first}:${endpoint.second}"
        }
    }

    fun lanAddress(): String {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: "0.0.0.0"
    }

    fun copyText(context: Context, label: String, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(context, context.getString(R.string.toast_copied))
    }

    fun openExternalVnc(context: Context, address: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnc://$address"))
        runCatching { context.startActivity(intent) }
            .onFailure { toast(context, context.getString(R.string.toast_no_external_app)) }
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(appIntent) }
            .onFailure {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
    }
}
