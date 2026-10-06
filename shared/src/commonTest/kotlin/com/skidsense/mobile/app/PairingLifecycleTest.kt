package com.skidsense.mobile.app

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.rc.Protocol
import com.skidsense.mobile.rc.utf8
import com.skidsense.mobile.transport.FakeHost
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pairing lifecycle against a real [AppController]: the plaintext-refusal
 * repair rule (spec §6.5, C6/S11), and pairings belonging to an account (S33).
 *
 * The scenario the LAN attacker wins without the rule: the phone is about to
 * re-scan a host its key is still active on (the 409 path), and anything
 * answering the QR's LAN address claims `unknown-device`. It used to cost the
 * phone its own backend row — revoked by *itself*, before the relay was ever
 * tried.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class PairingLifecycleTest {
    /** One host-id pre-registered between [TestApp.pairedOnDisk] and the QR. */
    private class HostedId(val hostId: String)

    private fun TestScope.qrFor(host: FakeHost, code: ByteArray, port: Int = 47290, server: String = "https://backend.example"): String =
        Protocol.PAIRING_URL_PREFIX + B64u.encode(utf8(
            """{"v":1,"n":"${host.hostId}","k":"${B64u.encode(host.hostStatic.pub)}","c":"${B64u.encode(code)}","h":["192.168.1.20"],"p":$port,"s":"$server","m":"书房的 Mac"}"""
        ))

    /** The backend walk of the 409 path: 409 with the old id, then the second registration. */
    private fun TestApp.pair409(
        host: FakeHost,
        oldDevice: String = "dev-old",
        newDevice: String = "dev-new"
    ) {
        var registrations = 0
        backend.handler = handler@{ method, path, _ ->
            if (path.endsWith("/api/companion/devices") && method == "POST") {
                registrations += 1
                return@handler if (registrations == 1) {
                    HttpStatusCode.Conflict to TestBackend.fail("已登记", """{"device_id":"$oldDevice"}""")
                } else {
                    HttpStatusCode.OK to TestBackend.ok(
                        """{"device":{"device_id":"$newDevice","host_id":"${host.hostId}","name":"Test Phone","platform":"android","status":"pending","created_at":1},"ticket":"ticket-1","ticket_expires_at":9999999999}"""
                    )
                }
            }
            if (method == "DELETE" && path.contains("/api/companion/devices/")) {
                return@handler HttpStatusCode.OK to TestBackend.ok("null")
            }
            null
        }
    }

    /**
     * A LAN address answering `unknown-device` meant nothing: the repair walks
     * the relay first, and only the host's own word through it — which here
     * accepts — counts. Nothing is revoked.
     */
    @Test
    fun aPlaintextUnknownDeviceOnTheLanDoesNotRevokeThePhonesOwnRow() = runTest {
        val app = TestApp(backgroundScope)
        val impostor = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope, rejectWith = "unknown-device")
        val genuine = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        app.pair409(genuine)
        app.carriers.lan = { impostor }
        app.carriers.relay = { genuine }
        app.controller.start()

        app.controller.pair(com.skidsense.mobile.rc.Pairing.decode(qrFor(genuine, Primitives.randomBytes(32))))
        assertEquals(
            emptyList(),
            app.backend.log.filter { it.startsWith("DELETE") },
            "a plaintext refusal must never reach the destructive repair (C6); calls=${app.backend.log}"
        )
        val paired = app.pairedOnDisk()
        assertEquals(1, paired.size)
        assertEquals("dev-old", paired[0].deviceId, "the row the backend still lists is kept, not replaced")
        assertEquals(1, genuine.connections)
    }

    /** The relay saying it too *is* the stale case: then, and only then, the row is replaced. */
    @Test
    fun theSameRefusalThroughTheRelayIsWhatEarnsTheRepair() = runTest {
        val app = TestApp(backgroundScope)
        val code = Primitives.randomBytes(32)
        val rejectingHost = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope, rejectConnectWith = "unknown-device")
        rejectingHost.pairingCode = code
        app.pair409(rejectingHost)
        app.carriers.lan = { rejectingHost }
        app.carriers.relay = { rejectingHost }
        app.controller.start()

        app.controller.pair(com.skidsense.mobile.rc.Pairing.decode(qrFor(rejectingHost, code)))
        assertEquals(
            listOf("DELETE /api/companion/devices/dev-old"),
            app.backend.log.filter { it.startsWith("DELETE") },
            "the relay's word is the one confirmation that counts; calls=${app.backend.log}"
        )
        assertEquals("dev-new", app.pairedOnDisk().single().deviceId)
    }

    /** A relay that cannot be reached says nothing: no revocation, the pairing survives. */
    @Test
    fun anUnreachableRelayConfirmsNothingAndNothingIsRevoked() = runTest {
        val app = TestApp(backgroundScope)
        val impostor = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope, rejectWith = "unknown-device")
        val genuine = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        app.pair409(genuine)
        app.carriers.lan = { impostor }
        app.carriers.relay = { null }
        app.controller.start()

        val error = runCatching {
            app.controller.pair(com.skidsense.mobile.rc.Pairing.decode(qrFor(genuine, Primitives.randomBytes(32))))
        }.exceptionOrNull()
        assertTrue(error != null, "with no truthful route the attempt fails rather than destroys")
        assertEquals(emptyList(), app.backend.log.filter { it.startsWith("DELETE") })
        assertEquals(emptyList(), app.pairedOnDisk(), "no pairing was finished, but none was destroyed either")
    }

    // --- S33: pairings belong to the account that made them ---------------------

    /**
     * Logging out clears memory only; the same-process re-login reads the same
     * account's rows back, and saving a new pairing must not overwrite rows the
     * current account cannot see.
     */
    @Test
    fun reloginReadsBackTheSameAccountsPairings() = runTest {
        val files = com.skidsense.mobile.store.MemoryFileStore()
        val a = TestApp.pairedHost("A", Primitives.generateKeyPair(), "dev-A", name = "电脑A", userId = 42)
        val b = TestApp.pairedHost("B", Primitives.generateKeyPair(), "dev-B", name = "电脑B", userId = 42)
        TestApp.writePaired(files, listOf(a, b))
        val app = TestApp(backgroundScope, files = files)
        app.controller.start()
        assertEquals(listOf("A", "B"), app.controller.state.value.paired.map { it.hostId })

        app.controller.logout()
        assertEquals(emptyList(), app.controller.state.value.paired, "memory is cleared")
        assertEquals(2, app.pairedOnDisk().size, "disk is the account's, not the logout's business")

        // The same account signs back in in the same process: its pairings return.
        app.backend.handler = { method, path, _ ->
            if (path.endsWith("/api/user/login") && method == "POST") {
                HttpStatusCode.OK to TestBackend.ok(TestBackend.loginBody(42, "user42"))
            } else null
        }
        app.controller.login("https://backend.example", "user42", "pass", null, null)
        assertEquals(listOf("A", "B"), app.controller.state.value.paired.map { it.hostId }, "the account's pairings are reloaded")
    }

    @Test
    fun anotherAccountDoesNotSeeThePreviousAccountsHostsOnColdStart() = runTest {
        val files = com.skidsense.mobile.store.MemoryFileStore()
        TestApp.writePaired(files, listOf(TestApp.pairedHost("A", Primitives.generateKeyPair(), "dev-A", name = "张三的电脑", userId = 42)))

        val lisi = TestApp(backgroundScope, files = files, signedInAs = 43)
        lisi.controller.start()
        assertEquals(emptyList(), lisi.controller.state.value.paired, "user 43 must not see user 42's computer")

        // Legacy rows (written before pairings carried an owner) are adopted by
        // whoever is on the same backend: there is no better claimant.
        val legacy = com.skidsense.mobile.store.MemoryFileStore()
        TestApp.writePaired(legacy, listOf(TestApp.pairedHost("A", Primitives.generateKeyPair(), "dev-A", userId = 0)))
        val anyone = TestApp(backgroundScope, files = legacy, signedInAs = 43)
        anyone.controller.start()
        assertEquals(1, anyone.controller.state.value.paired.size)
    }
}
