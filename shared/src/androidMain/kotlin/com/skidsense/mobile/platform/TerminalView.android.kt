package com.skidsense.mobile.platform

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * xterm.js in a WebView.
 *
 * The renderer (xterm.js, its CSS and the fit addon) ships in the app's own
 * assets (`assets/terminal`, see `THIRD_PARTY_NOTICES.txt` there), loaded from
 * memory against a local placeholder origin: a CDN copy used to leave the
 * terminal blank offline, answer to a third party as the page's origin, and —
 * its xterm 5.3.0 called `Element.replaceChildren` — stay a black rectangle
 * on any WebView older than Chromium 86 (N03/G01). The assets dir also carries
 * the polyfill the page installs first.
 *
 * The WebViewClient blocks every navigation away from the page: the terminal
 * content is untrusted agent output, so a link or redirect it contains must
 * not replace the page that holds the JS bridge.
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
                webViewClient = object : WebViewClient() {
                    // Nothing but the in-memory page itself may navigate here.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                addJavascriptInterface(host.bridge, "SKIDSENSE_TERM")
                host.attach(this)
                loadDataWithBaseURL(TERMINAL_ORIGIN, host.page(context, cols, rows), "text/html", "utf-8", null)
            }
        }
    )
}

/** The placeholder origin the terminal page claims: local, ours, and no CDN's. */
private const val TERMINAL_ORIGIN = "https://terminal.skidsense.local/"

/** Reads one asset once; the page then carries the bytes inline. */
private fun assetText(context: android.content.Context, name: String): String =
    context.assets.open("terminal/$name").bufferedReader().use { it.readText() }

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

    fun page(context: android.content.Context, cols: Int, rows: Int): String {
        val xtermJs = assetText(context, "xterm.js")
        val fitJs = assetText(context, "addon-fit.js")
        val xtermCss = assetText(context, "xterm.css")
        return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
        <style>$xtermCss</style>
        <style>html,body{margin:0;height:100%;background:#101216}#t{height:100%}</style>
        <script>
          $POLYFILL
        </script>
        <script>$xtermJs
        </script>
        <script>$fitJs
        </script>
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

    companion object {
        /**
         * Chromium 86's `Element.replaceChildren`, for the WebViews minSdk
         * allows (N03): xterm 5.3 died on `fit.fit()` without it, and the
         * failure had no UI at all. Runs before xterm loads, always.
         */
        private const val POLYFILL = """
          if (!Element.prototype.replaceChildren) {
            Element.prototype.replaceChildren = function () {
              while (this.lastChild) this.removeChild(this.lastChild);
              for (var i = 0; i < arguments.length; i++) {
                var node = arguments[i];
                this.appendChild(typeof node === 'string' ? document.createTextNode(node) : node);
              }
            };
          }
        """
    }
}
