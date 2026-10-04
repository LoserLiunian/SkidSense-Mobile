package com.skidsense.mobile.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * TODO(iOS): a `WKWebView` with a `WKScriptMessageHandler` named
 * `SKIDSENSE_CAPTCHA` — the same page contract as Android. Until then the
 * login screen says so rather than pretending a challenge was solved.
 */
@Composable
actual fun CaptchaWebView(html: String, onResult: (String?) -> Unit, modifier: Modifier) {
    Box(modifier) {
        Text("iOS 端的人机验证尚未实现")
    }
}
