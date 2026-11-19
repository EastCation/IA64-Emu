package cn.moerain.ia64emu.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import cn.moerain.ia64emu.data.VmStore
import java.io.File
import java.io.FileOutputStream

object FileImporter {

    private val main = Handler(Looper.getMainLooper())

    fun importAsync(
        context: Context,
        store: VmStore,
        vmId: String,
        uri: Uri,
        callback: (Result<String>) -> Unit,
    ) {
        val appContext = context.applicationContext
        Thread {
            val result = runCatching {
                val direct = resolveDirectPath(appContext, uri)
                if (direct != null && File(direct).canRead()) {
                    direct
                } else {
                    copyToVm(appContext, store, vmId, uri)
                }
            }
            main.post { callback(result) }
        }.start()
    }

    fun displayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun copyToVm(context: Context, store: VmStore, vmId: String, uri: Uri): String {
        val dir = store.importsDir(vmId)
        val name = sanitize(displayName(context, uri) ?: "import.bin")
        var dest = File(dir, name)
        var index = 1
        while (dest.exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            dest = File(dir, "$base-$index$ext")
            index++
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法打开所选文件")
        input.use { source ->
            FileOutputStream(dest).use { output ->
                source.copyTo(output, 256 * 1024)
            }
        }
        return dest.absolutePath
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return cleaned.ifBlank { "import.bin" }.take(120)
    }

    private fun resolveDirectPath(context: Context, uri: Uri): String? {
        return when (uri.scheme?.lowercase()) {
            "file" -> uri.path
            "content" -> resolveContent(context, uri)
            else -> null
        }
    }

    private fun resolveContent(context: Context, uri: Uri): String? {
        val authority = uri.authority ?: return queryData(context, uri)
        return when (authority) {
            "com.android.externalstorage.documents" -> {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    ?: return null
                val parts = docId.split(':', limit = 2)
                if (parts.size != 2) return null
                val volume = parts[0]
                val relative = parts[1]
                when (volume) {
                    "primary" -> "/storage/emulated/0/$relative"
                    "home" -> "/storage/emulated/0/Documents/$relative"
                    else -> "/storage/$volume/$relative"
                }
            }
            "com.android.providers.downloads.documents" -> {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    ?: return queryData(context, uri)
                when {
                    docId.startsWith("raw:") -> docId.removePrefix("raw:")
                    docId.startsWith("msf:") -> {
                        val id = docId.removePrefix("msf:")
                        queryData(
                            context,
                            Uri.parse("content://downloads/public_downloads/$id"),
                        ) ?: queryData(context, uri)
                    }
                    else -> queryData(context, uri)
                }
            }
            "com.android.providers.media.documents" -> {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    ?: return queryData(context, uri)
                val parts = docId.split(':', limit = 2)
                if (parts.size != 2) return queryData(context, uri)
                val type = parts[0]
                val id = parts[1]
                val collection = when (type) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                queryData(context, Uri.withAppendedPath(collection, id))
            }
            else -> queryData(context, uri)
        }
    }

    private fun queryData(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getString(0)
                    if (value.isNullOrBlank()) null else value
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
