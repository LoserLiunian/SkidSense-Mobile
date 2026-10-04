package com.skidsense.mobile.platform

import androidx.compose.runtime.Composable

/**
 * Picking a file from the phone, for the composer's attachment button.
 *
 * Reads the bytes directly rather than exposing a path: the desktop's upload
 * protocol takes the content, and the platform's own document picker may hand
 * back a `content://` URI with no readable path at all.
 */
fun interface FilePicker {
    /**
     * Let the user pick a file. [onPicked] receives the bytes and the name, or
     * null when the user backed out. [onError] carries a message for the UI.
     */
    fun pick(onPicked: (PickedFile) -> Unit, onError: (String) -> Unit)
}

data class PickedFile(val name: String, val mimeType: String?, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PickedFile && name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = name.hashCode() * 31 + bytes.contentHashCode()
}

@Composable
expect fun rememberFilePicker(): FilePicker?
