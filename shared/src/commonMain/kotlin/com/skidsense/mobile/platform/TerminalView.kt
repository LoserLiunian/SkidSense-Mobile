package com.skidsense.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * xterm.js in a platform WebView, bridged both ways.
 *
 * [onOutput] is what the page has already drawn is not the contract here: the
 * caller pushes output in with the returned sink, so the terminal is fed by the
 * same `tui.data` events the rest of the app sees. [onInput] receives raw
 * keystrokes, which go back to the host as `tui.input`.
 */
interface TerminalHost {
    /** Write to the terminal's screen. */
    fun write(data: String)

    /** The page's fitted size changed. */
    var onSizeChange: ((cols: Int, rows: Int) -> Unit)?

    /** A keystroke (or a paste). */
    var onInput: ((data: String) -> Unit)?
}

@Composable
expect fun TerminalWebView(
    cols: Int,
    rows: Int,
    onReady: (TerminalHost) -> Unit,
    modifier: Modifier
)
