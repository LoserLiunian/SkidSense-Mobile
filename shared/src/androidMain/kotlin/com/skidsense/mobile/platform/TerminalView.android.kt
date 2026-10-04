package com.skidsense.mobile.platform

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * xterm.js in a WebView. The page is loaded from memory, file access is off,
 * and the only bridge exposes three methods — input, resize, ready — so a page
 * that somehow got replaced finds nothing else to call.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
actual fun TerminalWebView(
    cols: Int,
    rows: Int,
    onReady: (TerminalHost) -> Unit,
    modifier: Modifier
) {
    val host = remember(cols, rows) { AndroidTerminalHost() }
    host.onReady = onReady
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                addJavascriptInterface(host.bridge, "SKIDSENSE_TERM")
                host.attach(this)
                loadDataWithBaseURL("https://cdn.jsdelivr.net/", host.page(cols, rows), "text/html", "utf-8", null)
            }
        }
    )
}

private class AndroidTerminalHost : TerminalHost {
    var onReady: ((TerminalHost) -> Unit)? = null
    private var view: WebView? = null

    val bridge = object {
        @JavascriptInterface
        fun input(data: String) {
            onInput?.invoke(data)
        }

        @JavascriptInterface
        fun resize(cols: Int, rows: Int) {
            onSizeChange?.invoke(cols, rows)
        }

        @JavascriptInterface
        fun ready() {
            onReady?.invoke(this@AndroidTerminalHost)
        }
    }

    fun attach(view: WebView) {
        this.view = view
    }

    override var onSizeChange: ((Int, Int) -> Unit)? = null
    override var onInput: ((String) -> Unit)? = null

    override fun write(data: String) {
        val escaped = data
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        view?.evaluateJavascript("window.skidsenseWrite('$escaped')", null)
    }

    fun page(cols: Int, rows: Int): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
        <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.min.css">
        <script src="https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.min.js"></script>
        <script src="https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8.0/lib/xterm-addon-fit.min.js"></script>
        <style>html,body{margin:0;height:100%;background:#101216}#t{height:100%}</style>
        </head><body><div id="t"></div>
        <script>
          var term = new Terminal({ cols: $cols, rows: $rows, fontSize: 12, scrollback: 2000 });
          var fit = new FitAddon.FitAddon();
          term.loadAddon(fit);
          term.open(document.getElementById('t'));
          fit.fit();
          term.onData(function (data) { SKIDSENSE_TERM.input(data); });
          window.skidsenseWrite = function (text) { term.write(text); };
          function announce() { SKIDSENSE_TERM.resize(term.cols, term.rows); }
          window.addEventListener('resize', function () { fit.fit(); announce(); });
          setTimeout(function () { fit.fit(); announce(); SKIDSENSE_TERM.ready(); }, 50);
        </script>
        </body></html>
    """.trimIndent()
}
