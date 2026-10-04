package com.skidsense.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A WebView that loads [html] and reports one string back through the page's
 * `SKIDSENSE_CAPTCHA(value)` global — the GeeTest validate JSON, a Turnstile
 * token, or '' when the user closed the widget.
 *
 * It exists for exactly this: the two captcha SDKs the backend supports are
 * browser scripts, and reimplementing either natively would be a second,
 * divergent implementation of somebody else's proof-of-work.
 */
@Composable
expect fun CaptchaWebView(html: String, onResult: (String?) -> Unit, modifier: Modifier)
