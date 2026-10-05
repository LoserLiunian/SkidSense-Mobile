package com.skidsense.mobile.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skidsense.mobile.app.AndroidEnvironment
import com.skidsense.mobile.transport.CarrierTarget
import com.skidsense.mobile.transport.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Does the **app's own** socket client open a carrier?
 *
 * `AndroidEnvironment` builds the one `HttpClient` both carriers use. It once
 * configured the WebSockets plugin with `maxFrameSize`, which Ktor's OkHttp
 * session rejects on every connection — so the upgrade succeeded and the
 * carrier then failed, on every route, on Android only. Nothing in the JVM
 * tests could see it: they run over an in-memory pipe, never this client.
 *
 * The failure happens after the HTTP upgrade, so a test needs something that
 * completes one. This starts a minimal WebSocket endpoint on the device's own
 * loopback — enough of RFC 6455 to answer the upgrade — and opens a carrier
 * to it with the real environment.
 */
@RunWith(AndroidJUnit4::class)
class AppEnvironmentCarrierTest {
    private fun upgradeOnce(server: ServerSocket) = thread(isDaemon = true) {
        server.accept().use { socket ->
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            var key = ""
            while (true) {
                val line = input.readLine() ?: return@thread
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1))
            )
            socket.getOutputStream().write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            )
            socket.getOutputStream().flush()
            Thread.sleep(2_000)
        }
    }

    @Test
    fun theAppsOwnSocketClientOpensACarrier () = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        upgradeOnce(server)
        val env = AndroidEnvironment(InstrumentationRegistry.getInstrumentation().targetContext)
        var failure: Throwable? = null
        try {
            env.carriers.open(Route.Lan("127.0.0.1", server.localPort), CarrierTarget("probe", "probe")).close("done")
        } catch (error: Throwable) {
            failure = error
        } finally {
            env.close()
            server.close()
        }
        println("SKIDSENSE_APPENV ${failure?.let { "${it::class.simpleName}: ${it.message}" } ?: "载体已打开"}")
        assertNull("app 的 socketClient 打不开载体：${failure?.message}", failure)
    }
}
