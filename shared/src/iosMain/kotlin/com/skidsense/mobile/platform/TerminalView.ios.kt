package com.skidsense.mobile.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * TODO(iOS): a `WKWebView` with the same xterm.js page and a script message
 * handler for input/resize. Until then the terminal says it is unavailable
 * rather than showing a terminal that does nothing.
 */
@Composable
actual fun TerminalWebView(
    cols: Int,
    rows: Int,
    onReady: (TerminalHost) -> Unit,
    modifier: Modifier
) {
    LaunchedEffect(Unit) {
        onReady(object : TerminalHost {
            override fun write(data: String) = Unit
            override var onSizeChange: ((Int, Int) -> Unit)? = null
            override var onInput: ((String) -> Unit)? = null
        })
    }
    Box(modifier) { Text("iOS 端的终端尚未实现") }
}
