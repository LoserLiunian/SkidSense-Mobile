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
 * The captcha WebView, with JavaScript on (the SDK needs it) and nothing else:
 * no file access, no universal access from file URLs, and the only bridge is
 * a one-way string out. The page is loaded from memory rather than a URL, so a
 * redirect cannot take the bridge somewhere else.
 *
 * `addJavascriptInterface` is what makes the result readable; the interface is
 * stripped of context so a compromised page finds nothing to call but
 * `SKIDSENSE_CAPTCHA(String)`.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
actual fun CaptchaWebView(html: String, onResult: (String?) -> Unit, modifier: Modifier) {
    val bridge = remember { CaptchaBridge() }
    bridge.onResult = onResult
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                webViewClient = WebViewClient()
                addJavascriptInterface(bridge, "SKIDSENSE_CAPTCHA")
                loadDataWithBaseURL("https://static.geetest.com/", html, "text/html", "utf-8", null)
            }
        },
        update = { it.loadDataWithBaseURL("https://static.geetest.com/", html, "text/html", "utf-8", null) }
    )
}

private class CaptchaBridge {
    var onResult: ((String?) -> Unit)? = null

    @JavascriptInterface
    fun invoke(value: String) {
        onResult?.invoke(value.ifBlank { null })
    }
}
