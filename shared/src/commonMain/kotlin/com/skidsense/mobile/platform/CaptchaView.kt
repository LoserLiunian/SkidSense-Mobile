package com.skidsense.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The name the bridge object is injected under. */
const val CAPTCHA_BRIDGE = "SKIDSENSE_CAPTCHA"

/**
 * How a page hands its result over: the bridge is an *object* with an
 * `invoke(String)` method on Android (`addJavascriptInterface` injects a
 * plain object, never a callable) and a message handler on iOS. Calling it
 * as a function — `window.SKIDSENSE_CAPTCHA(value)` — threw `TypeError` in a
 * real WebView, so no captcha ever completed and signing in to a server with
 * GeeTest on was impossible. Every page uses this one function.
 */
const val CAPTCHA_SEND_JS = "function skidsenseSend(value) { window.$CAPTCHA_BRIDGE.invoke(value); }"

/**
 * A WebView that loads [html] and reports one string back through
 * [CAPTCHA_SEND_JS] — the GeeTest validate JSON, a Turnstile token, or ''
 * when the user closed the widget.
 *
 * It exists for exactly this: the two captcha SDKs the backend supports are
 * browser scripts, and reimplementing either natively would be a second,
 * divergent implementation of somebody else's proof-of-work.
 */
@Composable
expect fun CaptchaWebView(html: String, onResult: (String?) -> Unit, modifier: Modifier, baseUrl: String = "https://static.geetest.com/")
