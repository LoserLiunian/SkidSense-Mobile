package com.skidsense.mobile.platform

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The system document picker. It grants temporary read access to whatever the
 * user chose, so the bytes are read immediately on the calling coroutine and
 * the URI is not kept.
 */
@Composable
actual fun rememberFilePicker(): FilePicker? {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val holder = remember { PickerHolder() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = holder.pending
        holder.pending = null
        if (uri == null || callback == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    val name = displayName(context, uri) ?: "附件"
                    val mime = context.contentResolver.getType(uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取这个文件")
                    PickedFile(name, mime, bytes)
                }
                callback.onPicked(resolved)
            } catch (error: Throwable) {
                callback.onError(error.message ?: "无法读取这个文件")
            }
        }
    }
    return remember(launcher, context) {
        FilePicker { onPicked, onError ->
            holder.pending = PickerCallback(onPicked, onError)
            try {
                launcher.launch(arrayOf("*/*"))
            } catch (error: android.content.ActivityNotFoundException) {
                // A slim/managed ROM without DocumentsUI must not take the app
                // down (N05): tell the user instead of crashing.
                holder.pending = null
                onError("这台设备上没有文件选择器，无法添加附件")
            }
        }
    }
}

private class PickerHolder {
    var pending: PickerCallback? = null
}

private class PickerCallback(val onPicked: (PickedFile) -> Unit, val onError: (String) -> Unit)

private fun displayName(context: android.content.Context, uri: android.net.Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}
