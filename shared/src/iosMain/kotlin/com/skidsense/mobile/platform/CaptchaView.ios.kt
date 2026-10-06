package com.skidsense.mobile.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * TODO(iOS): a `WKWebView` with a `WKScriptMessageHandler`, plus a user script
 * that defines `window.SKIDSENSE_CAPTCHA = { invoke: v =>
 * webkit.messageHandlers.SKIDSENSE_CAPTCHA.postMessage(v) }` — the pages call
 * [CAPTCHA_SEND_JS], i.e. `.invoke(value)`, on both platforms. Until then the
 * login screen says so rather than pretending a challenge was solved.
 */
@Composable
actual fun CaptchaWebView(html: String, onResult: (String?) -> Unit, modifier: Modifier, baseUrl: String) {
    Box(modifier) {
        Text("iOS 端的人机验证尚未实现")
    }
}
