package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.Pairing
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.rc.Protocol
import com.skidsense.mobile.rc.utf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The client end to end against an in-process fake host: handshake, hello,
 * requests (plain, parted, failing), events, pings, LAN-then-relay fallback,
 * reconnects, and the ways a connection must be torn down.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class ClientTest {
    private val hostStatic = Primitives.generateKeyPair()
    private val identity = Primitives.generateKeyPair()
    private val hostId = B64u.encode(Primitives.randomBytes(16))

    private class Grants : Credentials {
        var issued = 0
        val freshRequests = mutableListOf<Boolean>()
        override suspend fun grant(fresh: Boolean): String {
            freshRequests += fresh
            issued += 1
            return "grant-$issued"
        }
    }

    private fun endpoint(lan: List<String> = listOf("192.168.1.20"), hostKey: ByteArray = hostStatic.pub) =
        HostEndpoint(hostId, hostKey, "dev-1", lan, 47290)

    private val fastConfig = ClientConfig(
        lanConnectTimeoutMs = 2_500,
        backoffBaseMs = 1_000,
        backoffMaxMs = 8_000,
        callWaitMs = 20_000
    )

    private fun TestScope.client(
        host: FakeHost?,
        carriers: FakeCarriers = FakeCarriers(backgroundScope).apply { lan = { host } },
        endpoint: HostEndpoint = endpoint(),
        grants: Grants = Grants()
    ): RcClient = RcClient(endpoint, identity, grants, carriers, backgroundScope, fastConfig)

    private fun CoroutineScope.host(): FakeHost = FakeHost(hostId, hostStatic, this)

    private suspend fun RcClient.awaitConnected(): ClientState.Connected =
        withTimeout(60_000) { state.filterIsInstance<ClientState.Connected>().first() }

    private suspend fun RcClient.awaitConnectedAgain(previous: ClientState.Connected): ClientState.Connected =
        withTimeout(60_000) {
            state.filterIsInstance<ClientState.Connected>().first { it.route != previous.route || it !== previous }
        }

    @Test
    fun connectsOverLanAndCalls() = runTest {
        val host = backgroundScope.host()
        host.handler = { method, _ ->
            assertEquals("sessions.list", method)
            JsonArray(listOf(buildJsonObject { put("key", "claude:1"); put("title", "示例") }))
        }
        val client = client(host)
        client.start()
        val connected = client.awaitConnected()
        assertIs<Route.Lan>(connected.route)
        assertEquals(hostId, connected.welcome.host.id)
        assertEquals("书房的 Mac", connected.welcome.host.name)
        assertTrue(connected.welcome.hasScope("approve"))
        assertTrue(connected.welcome.can("sessions.list"))
        assertEquals<List<String?>>(listOf("grant-1"), host.helloGrants)

        val result = client.call("sessions.list", JsonObject(emptyMap()))
        assertEquals("示例", (result as JsonArray)[0].jsonObject["title"]!!.jsonPrimitive.content)
        client.stop()
    }

    @Test
    fun requestIdsAreUniqueAndConcurrentCallsResolveIndependently() = runTest {
        val host = backgroundScope.host()
        host.handler = { method, params -> if (method == "echo") params!! else JsonPrimitive(method) }
        val client = client(host)
        client.start()
        client.awaitConnected()
        val results = (1..20).map { n ->
            async { client.call("echo", JsonPrimitive(n)) }
        }.map { it.await() }
        assertEquals((1..20).map { JsonPrimitive(it) }, results)
        client.stop()
    }

    @Test
    fun partedResponsesAreReassembled() = runTest {
        val host = backgroundScope.host()
        host.partSize = 97
        val big = "示例文本".repeat(400)
        host.handler = { _, _ -> buildJsonObject { put("text", big) } }
        val client = client(host)
        client.start()
        client.awaitConnected()
        val result = client.call("fs.read")
        assertEquals(big, result.jsonObject["text"]!!.jsonPrimitive.content)
        client.stop()
    }

    @Test
    fun errorResponsesCarryTheHostsCode() = runTest {
        val host = backgroundScope.host()
        host.handler = { _, _ -> throw RemoteCallError("forbidden", "这台设备没有文件权限") }
        val client = client(host)
        client.start()
        client.awaitConnected()
        val error = assertFailsWith<RemoteCallError> { client.call("fs.list") }
        assertEquals("forbidden", error.code)
        assertEquals("这台设备没有文件权限", error.message)
        client.stop()
    }

    @Test
    fun eventsAndPingsFlow() = runTest {
        val host = backgroundScope.host()
        val client = client(host)
        client.start()
        client.awaitConnected()
        val event = async { client.events.first() }
        testScheduler.advanceUntilIdle()
        host.emit("sessions.changed", JsonObject(emptyMap()))
        assertEquals("sessions.changed", withTimeout(5_000) { event.await() }.kind)
        host.ping(1234)
        // The pong comes back through the host's read loop.
        withTimeout(5_000) { while (host.pongs == 0) kotlinx.coroutines.delay(10) }
        client.stop()
    }

    @Test
    fun fallsBackFromLanToRelay() = runTest {
        val host = backgroundScope.host()
        val carriers = FakeCarriers(backgroundScope).apply {
            lan = { null } // refused
            relay = { host }
        }
        val client = client(null, carriers, endpoint(listOf("192.168.1.20", "10.0.0.5")))
        client.start()
        val connected = client.awaitConnected()
        assertEquals(Route.Relay, connected.route)
        assertEquals(listOf<Route>(Route.Lan("192.168.1.20", 47290), Route.Lan("10.0.0.5", 47290), Route.Relay), carriers.opened)
        client.stop()
    }

    @Test
    fun aSilentLanAddressTimesOutThenTheNextOneIsTried() = runTest {
        val host = backgroundScope.host()
        val carriers = FakeCarriers(backgroundScope).apply {
            blackhole = setOf("192.168.1.20")
            lan = { route -> if (route.address == "10.0.0.5") host else null }
        }
        val client = client(null, carriers, endpoint(listOf("192.168.1.20", "10.0.0.5")))
        client.start()
        val connected = client.awaitConnected()
        assertEquals(Route.Lan("10.0.0.5", 47290), connected.route)
        client.stop()
    }

    @Test
    fun anImpostorOnTheLanIsSkippedForTheRealHostOnTheRelay() = runTest {
        // Something on the LAN answers at the address, holding a different key.
        val impostor = FakeHost(hostId, Primitives.generateKeyPair(), backgroundScope)
        val host = backgroundScope.host()
        val carriers = FakeCarriers(backgroundScope).apply {
            lan = { impostor }
            relay = { host }
        }
        val client = client(null, carriers)
        client.start()
        assertEquals(Route.Relay, client.awaitConnected().route)
        assertEquals(0, impostor.connections, "the impostor never got past the handshake")
        client.stop()
    }

    @Test
    fun unknownDeviceIsPermanent() = runTest {
        val host = backgroundScope.host().apply { rejectWith = "unknown-device" }
        val carriers = FakeCarriers(backgroundScope).apply { lan = { host }; relay = { host } }
        val client = client(null, carriers)
        client.start()
        val failed = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Failed>().first() }
        assertEquals("unknown-device", failed.code)
        val opened = carriers.opened.size
        testScheduler.advanceTimeBy(120_000)
        assertEquals(opened, carriers.opened.size, "no retries after a permanent refusal")
        client.stop()
    }

    @Test
    fun reconnectsAfterADropAndResubscribes() = runTest {
        val host = backgroundScope.host()
        val client = client(host)
        client.start()
        val first = client.awaitConnected()
        client.subscribe("claude:1")
        assertEquals(1, host.calls.count { it.first == "subscribe" }, "subscribed exactly once on the live connection")

        host.drop()
        // Wait for a *different* Connected: the flow still holds the previous
        // one until the state actually moves on.
        client.awaitConnectedAgain(first)
        testScheduler.advanceUntilIdle()
        assertEquals(2, host.connections)
        assertEquals(2, host.calls.count { it.first == "subscribe" }, "subscriptions survive a reconnect")
        client.stop()
    }

    @Test
    fun aGapInTheHostsCounterClosesTheConnection() = runTest {
        val host = backgroundScope.host()
        val client = client(host)
        client.start()
        client.awaitConnected()
        host.skipFrame()
        host.emit("sessions.changed", JsonObject(emptyMap()))
        val waiting = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Waiting>().first() }
        assertTrue(waiting.error.contains("out-of-order"), waiting.error)
        client.stop()
    }

    @Test
    fun aTamperedFrameClosesTheConnection() = runTest {
        val host = backgroundScope.host()
        val client = client(host)
        client.start()
        client.awaitConnected()
        host.sendTampered("{\"t\":\"ev\",\"k\":\"sessions.changed\",\"p\":{}}")
        val waiting = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Waiting>().first() }
        assertTrue(waiting.error.contains("bad-frame"), waiting.error)
        client.stop()
    }

    @Test
    fun aPlaintextFrameAfterTheHandshakeClosesTheConnection() = runTest {
        val host = backgroundScope.host()
        val client = client(host)
        client.start()
        client.awaitConnected()
        host.sendRaw("{\"t\":\"hs2\",\"e\":\"x\",\"c\":\"y\"}")
        withTimeout(60_000) { client.state.filterIsInstance<ClientState.Waiting>().first() }
        client.stop()
    }

    @Test
    fun relayErrorsAreHonouredOnTheRelayOnly() = runTest {
        val host = backgroundScope.host()
        val carriers = FakeCarriers(backgroundScope).apply { relay = { host } }
        val client = client(null, carriers, endpoint(emptyList()))
        client.start()
        client.awaitConnected()
        host.relayError("revoked")
        val failed = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Failed>().first() }
        assertEquals("revoked", failed.code)
        client.stop()

        // The same frame on a LAN socket is someone pretending to be the relay.
        val lanHost = backgroundScope.host()
        val lanClient = client(lanHost)
        lanClient.start()
        lanClient.awaitConnected()
        lanHost.relayError("revoked")
        val waiting = withTimeout(60_000) { lanClient.state.filterIsInstance<ClientState.Waiting>().first() }
        assertTrue(waiting.error.contains("中继错误帧"), waiting.error)
        lanClient.stop()
    }

    @Test
    fun aRefusedGrantIsReplacedWithAFreshOne() = runTest {
        val host = backgroundScope.host().apply { acceptGrant = { it == "grant-2" } }
        val grants = Grants()
        val client = client(host, grants = grants)
        client.start()
        client.awaitConnected()
        assertEquals<List<String?>>(listOf("grant-1", "grant-2"), host.helloGrants)
        assertEquals(listOf(false, true), grants.freshRequests)
        client.stop()
    }

    @Test
    fun callsFailFastWhenOffline() = runTest {
        val carriers = FakeCarriers(backgroundScope)
        val client = client(null, carriers)
        client.start()
        val error = assertFailsWith<RcException> { client.call("sessions.list") }
        assertEquals("offline", error.code)
        client.stop()
    }

    @Test
    fun requestsTimeOut() = runTest {
        val host = backgroundScope.host()
        host.handler = { _, _ -> kotlinx.coroutines.awaitCancellation() }
        val client = client(host)
        client.start()
        client.awaitConnected()
        val error = assertFailsWith<RcException> { client.call("sessions.open", null, timeoutMs = 5_000) }
        assertEquals("timeout", error.code)
        client.stop()
    }

    @Test
    fun enrollmentUsesThePairingCodeAndTicket() = runTest {
        val code = Primitives.randomBytes(32)
        val host = backgroundScope.host().apply {
            pairingCode = code
            acceptTicket = { it == "ticket-1" }
        }
        val link = Protocol.PAIRING_URL_PREFIX + B64u.encode(utf8(
            """{"v":1,"n":"$hostId","k":"${B64u.encode(hostStatic.pub)}","c":"${B64u.encode(code)}","h":["192.168.1.20"],"p":47290,"s":"https://ai.surise.cn","m":"书房的 Mac"}"""
        ))
        val payload = Pairing.decode(link)
        val carriers = FakeCarriers(backgroundScope).apply { lan = { host } }
        val welcome = Enrollment.enroll(payload, "dev-1", identity, "ticket-1", carriers, backgroundScope, fastConfig)
        assertEquals(hostId, welcome.host.id)
        assertEquals(1, host.enrolledKeys.size)
        assertTrue(host.enrolledKeys[0].contentEquals(identity.pub))

        // A wrong code never gets past the host's first step.
        val wrong = payload.copy(code = Primitives.randomBytes(32))
        assertFailsWith<HandshakeRejected> {
            Enrollment.enroll(wrong, "dev-1", identity, "ticket-1", FakeCarriers(backgroundScope).apply { lan = { host } }, backgroundScope, fastConfig)
        }
        // A refused ticket is final.
        assertFailsWith<RcConnection.HelloRefused> {
            Enrollment.enroll(payload, "dev-1", identity, "ticket-2", FakeCarriers(backgroundScope).apply { lan = { host } }, backgroundScope, fastConfig)
        }
    }

    /**
     * The 409 recovery (spec §9): the phone is already registered, the backend
     * issued no ticket, and the way back in is a grant plus an ordinary
     * `connect` handshake — not another registration, which would earn the same
     * 409, and not the pairing code, which the host has already spent.
     */
    @Test
    fun existingDeviceReconnectsWithAGrantInsteadOfATicket() = runTest {
        val host = backgroundScope.host().apply {
            // No pairing code at all: a connect handshake must not need one.
            pairingCode = null
            acceptGrant = { it == "grant-1" }
            acceptTicket = { false }
        }
        val link = Protocol.PAIRING_URL_PREFIX + B64u.encode(utf8(
            """{"v":1,"n":"$hostId","k":"${B64u.encode(hostStatic.pub)}","c":"${B64u.encode(Primitives.randomBytes(32))}","h":["192.168.1.20"],"p":47290,"s":"https://ai.surise.cn","m":"书房的 Mac"}"""
        ))
        val payload = Pairing.decode(link)
        val welcome = Enrollment.connectWithGrant(
            payload, "dev-1", identity, "grant-1",
            FakeCarriers(backgroundScope).apply { lan = { host } }, backgroundScope, fastConfig
        )
        assertEquals(hostId, welcome.host.id)
        assertEquals(listOf<String?>("grant-1"), host.helloGrants)
        // A reconnect enrols nothing: the host already has this key.
        assertTrue(host.enrolledKeys.isEmpty())

        // The grant is what the host checks, so a wrong one must not get in.
        assertFailsWith<RcConnection.HelloRefused> {
            Enrollment.connectWithGrant(
                payload, "dev-1", identity, "grant-2",
                FakeCarriers(backgroundScope).apply { lan = { host } }, backgroundScope, fastConfig
            )
        }

        // And the enroll path must not be reachable with a null ticket: the
        // two are separate functions precisely so this cannot be called wrong.
        assertFailsWith<Throwable> {
            Enrollment.enroll(payload, "dev-1", identity, "", FakeCarriers(backgroundScope).apply { lan = { host } }, backgroundScope, fastConfig)
        }
    }
}
