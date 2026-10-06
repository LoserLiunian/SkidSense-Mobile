package com.skidsense.mobile

import com.skidsense.mobile.platform.CappedSocketFactory
import com.skidsense.mobile.platform.OkHttpGuards
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.net.SocketFactory
import java.io.DataInputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pre-handshake DoS guard (S30): whoever answers at a LAN address before
 * any keys exist may not pour unbounded bytes into the app's heap. The
 * listener OkHttp itself feeds is the one through `CappedWebSocket` — and it
 * must reject an oversized message *without delivering it*, where OkHttp's
 * default would have buffered it whole before `KtorCarrier` could look.
 *
 * Runs against OkHttp's real `newWebSocket`, which is exactly the production
 * engine. `okhttp3.internal.ws` is internal but public to the JVM.
 */
class TransportShieldTest {
    /** A minimal RFC 6455 server: accepts, shakes hands, sends one text message, notes the offer headers. */
    private class Blaster(val message: String) {
        @Volatile var sawDeflateOffer: Boolean? = null
        private val socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val done = CountDownLatch(1)

        fun start() {
            thread(isDaemon = true) { serve() }
        }

        private fun serve() {
            try {
                socket.soTimeout = 15_000
                val conn = socket.accept()
                val input = DataInputStream(conn.getInputStream())
                val output = conn.getOutputStream()
                // Read the upgrade request headers.
                val headers = LinkedHashMap<String, String>()
                val firstLine = readLine(input)
                require(firstLine.startsWith("GET"))
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                sawDeflateOffer = headers["sec-websocket-extensions"]?.contains("permessage-deflate")
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
                )
                output.write(
                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                output.flush()
                sendText(output, message.toByteArray(Charsets.UTF_8))
                // Give the client a moment to refuse, then close.
                Thread.sleep(300)
                conn.close()
                done.countDown()
            } catch (_: Throwable) {
                done.countDown()
            }
        }

        private fun readLine(input: DataInputStream): String {
            val builder = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException()
                if (b == '\n'.code) {
                    if (builder.isNotEmpty() && builder[builder.length - 1] == '\r') builder.setLength(builder.length - 1)
                    return builder.toString()
                }
                builder.append(b.toChar())
            }
        }

        private fun sendText(output: java.io.OutputStream, payload: ByteArray) {
            // A single unmasked text frame: 0x81, length (16/64-bit forms), payload.
            output.write(0x81)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size < 65536 -> {
                    output.write(126)
                    output.write((payload.size shr 8) and 0xFF)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            output.write(payload)
            output.flush()
        }

        fun waitDone(seconds: Long): Boolean = done.await(seconds, TimeUnit.SECONDS)

        fun stop() = runCatching { socket.close() }
    }

    private class Probe : WebSocketListener() {
        val messages = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val ended = CountDownLatch(1)
        override fun onMessage(webSocket: WebSocket, text: String) {
            messages += text
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            failures += t
            ended.countDown()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            ended.countDown()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ended.countDown()
        }
    }

    @Test
    fun anOversizeMessageIsRefusedBeforeDelivery() {
        val server = Blaster("x".repeat(1 shl 20)) // 1 MiB over the test's 128 KiB cap
        server.start()
        val probe = Probe()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(OkHttpGuards.NoDeflateInterceptor)
            .socketFactory(CappedSocketFactory(SocketFactory.getDefault(), 128 * 1024))
            .build()
        val request = Request.Builder().url("ws://127.0.0.1:${server.port}/rc/1").build()
        client.newWebSocket(request, probe)
        assertTrue(probe.ended.await(15, TimeUnit.SECONDS), "the socket cap must end the connection")
        assertEquals(emptyList(), probe.messages, "nothing oversized was delivered upward")
        assertTrue(probe.failures.isNotEmpty(), "the failure is surfaced as a dead route")
        server.waitDone(15)
        // Non-null means the server received the upgrade request: the cap did
        // not merely refuse a connect that never arrived.
        assertTrue(server.sawDeflateOffer != null, "the server really did see the WebSocket upgrade")
        server.stop()
    }

    @Test
    fun smallMessagesFlowThroughUnchanged() {
        val server = Blaster("{\"t\":\"hs2\",\"e\":\"x\",\"c\":\"y\"}")
        server.start()
        val probe = Probe()
        val client = OkHttpClient()
        val request = Request.Builder().url("ws://127.0.0.1:${server.port}/rc/1").build()
        client.newWebSocket(request, probe)
        assertTrue(probe.ended.await(15, TimeUnit.SECONDS) || probe.messages.isNotEmpty())
        assertEquals(listOf("{\"t\":\"hs2\",\"e\":\"x\",\"c\":\"y\"}"), probe.messages)
        server.waitDone(15)
        server.stop()
    }
}
