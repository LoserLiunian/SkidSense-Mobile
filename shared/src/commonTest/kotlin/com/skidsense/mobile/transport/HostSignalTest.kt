package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.HandshakeMode
import com.skidsense.mobile.rc.Initiator
import com.skidsense.mobile.rc.OuterFrame
import com.skidsense.mobile.rc.OuterFrames
import com.skidsense.mobile.rc.Pairing
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.rc.Protocol
import com.skidsense.mobile.rc.TestResponder
import com.skidsense.mobile.rc.utf8
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the device does with the two signals that arrive in the clear or
 * end a connection: a `bye` that says why (C5), and an `hsr` that nothing
 * authenticated (C6).
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class HostSignalTest {
    private val hostStatic = Primitives.generateKeyPair()
    private val identity = Primitives.generateKeyPair()
    private val hostId = B64u.encode(Primitives.randomBytes(16))

    private class Grants : Credentials {
        /** `false` the cached grant was reused, `true` a fresh one had to be fetched. */
        val freshRequests = mutableListOf<Boolean>()
        private var calls = 0
        private var cached: String? = null

        override suspend fun grant(fresh: Boolean): String {
            cached?.takeIf { !fresh }?.let {
                freshRequests += false
                return it
            }
            calls += 1
            freshRequests += true
            return "grant-$calls".also { cached = it }
        }

        /** What the grant cache does with a cached one the host just invalidated (spec §6.5, C5). */
        override suspend fun onDropped() {
            cached = null
        }
    }

    private val config = ClientConfig(lanConnectTimeoutMs = 2_500, backoffBaseMs = 1_000, backoffMaxMs = 8_000, callWaitMs = 20_000)

    private fun TestScope.client(carriers: FakeCarriers, grants: Grants = Grants()) =
        RcClient(HostEndpoint(hostId, hostStatic.pub, "dev-1", listOf("192.168.1.20"), 47290), identity, grants, carriers, backgroundScope, config)

    private suspend fun RcClient.connected(): ClientState.Connected =
        withTimeout(60_000) { state.filterIsInstance<ClientState.Connected>().first() }

    // --- C5: bye with a reason code ---------------------------------------------

    private suspend fun TestScope.kickedWith(code: String?): List<Boolean> {
        val host = FakeHost(hostId, hostStatic, backgroundScope)
        val grants = Grants()
        val client = client(FakeCarriers(backgroundScope).apply { lan = { host } }, grants)
        client.start()
        val first = client.connected()
        host.kick(code)
        withTimeout(60_000) { client.state.filterIsInstance<ClientState.Connected>().first { it !== first } }
        client.stop()
        return grants.freshRequests
    }

    /**
     * Widening a device's scopes on the desktop kicks it so it reconnects with
     * the new ones — which only works if the reconnect asks the backend for a
     * new grant. The cached one still lists the old scopes for up to an hour.
     */
    @Test
    fun aByeForChangedScopesFetchesAFreshGrant() = runTest {
        // The first fetch always runs cold; the kick must make the second one cold too.
        assertEquals(listOf(true, true), kickedWith("scopes-changed"))
    }

    @Test
    fun aByeForRevocationFetchesAFreshGrant() = runTest {
        assertEquals(listOf(true, true), kickedWith("revoked"))
    }

    @Test
    fun anOrdinaryByeKeepsTheCachedGrant() = runTest {
        assertEquals(listOf(true, false), kickedWith(null))
        assertEquals(listOf(true, false), kickedWith("shutdown"))
    }

    // --- C6: a plaintext hsr is not proof of anything --------------------------

    /**
     * Something on the LAN answering with `hsr unknown-device` holds no key: the
     * frame is plaintext and comes before any. It used to end the whole round
     * as a permanent failure, so the relay — where the real host would have
     * taken the connection — was never tried.
     */
    @Test
    fun aPlaintextRefusalOnTheLanStillTriesTheRelay() = runTest {
        val impostor = FakeHost(hostId, Primitives.generateKeyPair(), backgroundScope, rejectWith = "unknown-device")
        val genuine = FakeHost(hostId, hostStatic, backgroundScope)
        val carriers = FakeCarriers(backgroundScope).apply { lan = { impostor }; relay = { genuine } }
        val client = client(carriers)
        client.start()
        assertEquals(Route.Relay, client.connected().route)
        client.stop()
    }

    @Test
    fun theSameRefusalThroughTheRelayIsFinal() = runTest {
        val refusing = FakeHost(hostId, hostStatic, backgroundScope, rejectWith = "unknown-device")
        val carriers = FakeCarriers(backgroundScope).apply { lan = { refusing }; relay = { refusing } }
        val client = client(carriers)
        client.start()
        val failed = withTimeout(60_000) { client.state.filterIsInstance<ClientState.Failed>().first() }
        assertEquals("unknown-device", failed.code)
        assertEquals(listOf<Route>(Route.Lan("192.168.1.20", 47290), Route.Relay), carriers.opened)
        client.stop()
    }

    /** The 409 recovery path: the same rule, through `Enrollment.connectWithGrant`. */
    @Test
    fun reconnectingAnExistingDeviceTriesTheRelayAfterAPlaintextRefusal() = runTest {
        val impostor = FakeHost(hostId, Primitives.generateKeyPair(), backgroundScope, rejectWith = "unknown-device")
        val genuine = FakeHost(hostId, hostStatic, backgroundScope)
        val link = Protocol.PAIRING_URL_PREFIX + B64u.encode(utf8(
            """{"v":1,"n":"$hostId","k":"${B64u.encode(hostStatic.pub)}","c":"${B64u.encode(Primitives.randomBytes(32))}","h":["192.168.1.20"],"p":47290,"s":"https://ai.surise.cn","m":"书房的 Mac"}"""
        ))
        val carriers = FakeCarriers(backgroundScope).apply { lan = { impostor }; relay = { genuine } }
        val welcome = Enrollment.connectWithGrant(Pairing.decode(link), "dev-1", identity, "grant-1", carriers, backgroundScope, config)
        assertEquals(hostId, welcome.host.id)
        assertEquals(1, genuine.connections)
    }

    /**
     * After a valid `hs2` the host has proven itself, and the desktop never
     * sends `hsr` past that point. A plaintext one arriving there is somebody
     * else's, so it is a broken connection — not the host refusing the device.
     */
    @Test
    fun aPlaintextRefusalAfterAValidHs2IsAConnectionError() = runTest {
        val (client, server) = MemoryCarrier.pair("局域网 192.168.1.20")
        backgroundScope.launch {
            val hs1 = (OuterFrames.parse(server.incoming.receive()) as OuterFrame.Hs1).frame
            val (hs2, _) = TestResponder(hostId, hostStatic, hs1, null).complete()
            server.send(OuterFrames.encode(hs2.toJson()))
            server.incoming.receive() // the sealed hello
            server.send("""{"t":"hsr","code":"unknown-device","message":"x"}""")
        }
        val initiator = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, identity)
        val error = runCatching {
            RcConnection.establish(client, Route.Lan("192.168.1.20", 47290), initiator, "grant", null, ConnectionConfig(), backgroundScope)
        }.exceptionOrNull()
        assertIs<ConnectionClosed>(error, "got ${error?.let { it::class.simpleName }}")
    }
}
