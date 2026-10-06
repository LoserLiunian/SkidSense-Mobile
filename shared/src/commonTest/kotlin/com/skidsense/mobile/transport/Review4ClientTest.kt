package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.Primitives
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 4 (transport): what the real three-way runs found in the client's
 * connect loop and its requests.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Review4ClientTest {
    private val hostStatic = Primitives.generateKeyPair()
    private val identity = Primitives.generateKeyPair()
    private val hostId = B64u.encode(Primitives.randomBytes(16))

    private class Grants : Credentials {
        override suspend fun grant(fresh: Boolean): String = "grant"
    }

    /**
     * What Ktor hands back when the relay has refused the device and closed
     * the socket — the desktop offline, say: the relay's own `relay-error`
     * frame is already in the inbox, and the first send throws a
     * *CancellationException* ("WebSocket session closed with code
     * VIOLATED_POLICY"), although nothing cancelled the caller.
     */
    private class ClosedByRelay(code: String) : Carrier {
        private val inbox = Channel<String>(Channel.UNLIMITED).also {
            it.trySend("""{"t":"relay-error","code":"$code","message":"x"}""")
            it.close()
        }
        override val incoming: ReceiveChannel<String> = inbox
        override val label: String = "中继"
        override suspend fun send(text: String) {
            throw CancellationException("WebSocket session closed with code VIOLATED_POLICY.")
        }
        override suspend fun close(reason: String) {}
    }

    /**
     * F4: the relay refusing a device while the desktop is offline used to end
     * the reconnect loop for good — the CancellationException was taken for
     * the loop's own cancellation and rethrown — and the phone sat on
     * 「正在连接 · 中继」 even after the desktop came back.
     */
    @Test
    fun aRelayRefusalThatKtorReportsAsCancellationDoesNotEndTheLoop() = runTest {
        val host = FakeHost(hostId, hostStatic, backgroundScope)
        var offline = 2
        val carriers = object : CarrierFactory {
            override suspend fun open(route: Route, target: CarrierTarget): Carrier {
                if (offline > 0) {
                    offline -= 1
                    return ClosedByRelay("host-offline")
                }
                val (client, server) = MemoryCarrier.pair(route.label)
                host.serve(server)
                return client
            }
        }
        val endpoint = HostEndpoint(hostId, hostStatic.pub, "dev-1", emptyList(), 0)
        val client = RcClient(endpoint, identity, Grants(), carriers, backgroundScope, ClientConfig(backoffBaseMs = 1_000, backoffMaxMs = 4_000))
        client.start()
        val waiting = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Waiting>().first() }
        assertTrue("电脑不在线" in waiting.error, "the relay's own reason, not the transport's: ${waiting.error}")
        val connected = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Connected>().first() }
        assertIs<Route.Relay>(connected.route)
        client.stop()
    }

    /** F6: a desktop that moved to another LAN port is reached on the new one without restarting the app. */
    @Test
    fun aLearnedLanPortReplacesTheOldOne() {
        val endpoint = HostEndpoint(hostId, hostStatic.pub, "dev-1", listOf("192.168.1.20"), 47290)
        assertTrue(endpoint.learn(listOf("192.168.1.20"), 51000))
        val lan = endpoint.routes().filterIsInstance<Route.Lan>()
        assertEquals(listOf(51000), lan.map { it.port }.distinct())
        assertTrue(!endpoint.learn(listOf("192.168.1.20"), 51000), "the same port again is not news")
    }

    /**
     * F5: every advertised LAN address used to be tried in turn, 2.5 s each,
     * before the relay — ten seconds and more away from home on every
     * reconnect. Unreachable LAN addresses now cost about one timeout in all,
     * and the relay is already on its way by then.
     */
    @Test
    fun unreachableLanAddressesDoNotHoldTheRelayBack() = runTest {
        val host = FakeHost(hostId, hostStatic, backgroundScope)
        val addresses = listOf("192.168.1.20", "192.168.139.3", "fd07::1", "fdfe::1")
        val carriers = FakeCarriers(backgroundScope).apply {
            blackhole = addresses.toSet()
            relay = { host }
        }
        val endpoint = HostEndpoint(hostId, hostStatic.pub, "dev-1", addresses, 47290)
        val client = RcClient(endpoint, identity, Grants(), carriers, backgroundScope, ClientConfig())
        val started = testScheduler.currentTime
        client.start()
        val connected = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Connected>().first() }
        val took = testScheduler.currentTime - started
        assertIs<Route.Relay>(connected.route)
        assertTrue(took <= 3_000, "relay reached after ${took}ms")
        client.stop()
    }

    /**
     * F5: on the relay, a desktop whose LAN address answers again is moved
     * back to — the relay is budgeted, and staying on it after coming home
     * was the norm, since nothing ever looked again.
     */
    @Test
    fun aRelayConnectionMovesBackToTheLanWhenItAnswers() = runTest {
        val host = FakeHost(hostId, hostStatic, backgroundScope)
        val carriers = FakeCarriers(backgroundScope).apply {
            blackhole = setOf("192.168.1.20")
            lan = { host }
            relay = { host }
        }
        val endpoint = HostEndpoint(hostId, hostStatic.pub, "dev-1", listOf("192.168.1.20"), 47290)
        val client = RcClient(endpoint, identity, Grants(), carriers, backgroundScope, ClientConfig())
        client.start()
        val first = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Connected>().first() }
        assertIs<Route.Relay>(first.route)
        carriers.blackhole = emptySet()
        val back = withTimeout(5 * 60_000) {
            client.state.filterIsInstance<ClientState.Connected>().first { it.route is Route.Lan }
        }
        assertIs<Route.Lan>(back.route)
        client.stop()
    }

    /**
     * Over a budgeted relay a large response arrives slowly but steadily. A
     * fixed 30-second limit on the whole call failed a transcript that was
     * still arriving; the limit is now on silence.
     */
    @Test
    fun aSlowButSteadyResponseIsNotTimedOut() = runTest {
        val host = FakeHost(hostId, hostStatic, backgroundScope, partSize = 1_000, partDelayMs = 10_000)
        val big = "x".repeat(7_000)
        host.handler = { _, _ -> buildJsonObject { put("text", big) } }
        val carriers = FakeCarriers(backgroundScope).apply { lan = { host } }
        val endpoint = HostEndpoint(hostId, hostStatic.pub, "dev-1", listOf("192.168.1.20"), 47290)
        val client = RcClient(endpoint, identity, Grants(), carriers, backgroundScope, ClientConfig())
        client.start()
        withTimeout(60_000) { client.state.filterIsInstance<ClientState.Connected>().first() }
        val result = client.call("sessions.open")
        assertEquals(big, result.jsonObject["text"]!!.jsonPrimitive.content)
        client.stop()
    }
}
