package com.skidsense.mobile.rc

import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skidsense.mobile.platform.CAPTCHA_BRIDGE
import com.skidsense.mobile.platform.CAPTCHA_SEND_JS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Does a captcha page's result reach the app, in a real Android WebView?
 *
 * The bridge `CaptchaView.android.kt` injects is an object with one
 * `@JavascriptInterface` method, `invoke`. The pages once called the object
 * itself — `window.SKIDSENSE_CAPTCHA(value)` — which throws `TypeError` in a
 * WebView, so a solved GeeTest never came back and signing in to a server with
 * it on was impossible. This runs the production [CAPTCHA_SEND_JS] against a
 * bridge registered the same way (the production class is private), and the
 * old call form beside it as a control that must still fail.
 */
@RunWith(AndroidJUnit4::class)
class CaptchaBridgeContractTest {
    class Bridge(private val sink: MutableList<String>) {
        @JavascriptInterface
        fun invoke(value: String) { sink.add(value) }
    }

    @Test
    fun theProductionSendReachesTheBridge () {
        val received = CopyOnWriteArrayList<String>()
        val console = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        val page = """
            <!doctype html><html><body><script>
              $CAPTCHA_SEND_JS
              try { skidsenseSend('production'); } catch (e) { console.log('send threw: ' + e); }
              try { window.$CAPTCHA_BRIDGE('as-function'); } catch (e) { console.log('function form threw: ' + e); }
              console.log('DONE');
            </script></body></html>
        """.trimIndent()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        console.add(message.message())
                        if (message.message() == "DONE") finished.countDown()
                        return true
                    }
                }
                addJavascriptInterface(Bridge(received), CAPTCHA_BRIDGE)
                loadDataWithBaseURL("https://static.geetest.com/", page, "text/html", "utf-8", null)
            }
        }
        finished.await(15, TimeUnit.SECONDS)
        Thread.sleep(500)
        println("SKIDSENSE_CAPTCHA received=$received console=$console")
        assertEquals("产品里的 skidsenseSend 把结果送到了桥接", listOf("production"), received.filter { it == "production" })
        assertEquals("对照：把桥接对象当函数调用确实送不到", false, received.contains("as-function"))
    }
}
