package com.skidsense.mobile.platform

import androidx.compose.runtime.Composable

/**
 * TODO(iOS): `UIDocumentPickerViewController` with
 * `asCopy`/`startAccessingSecurityScopedResource` to read the bytes. The shared
 * UI hides the attach button while this is null.
 */
@Composable
actual fun rememberFilePicker(): FilePicker? = null
