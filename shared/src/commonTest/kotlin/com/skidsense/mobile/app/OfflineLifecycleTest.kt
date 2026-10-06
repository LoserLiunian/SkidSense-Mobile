package com.skidsense.mobile.app

import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.transport.ClientState
import com.skidsense.mobile.transport.FakeHost
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * How a paired phone goes offline, through the real [AppController] and its
 * real grant cache (third joint review).
 *
 * `HostSignalTest` covers the same signals against [com.skidsense.mobile.transport.RcClient]
 * with a stand-in `Credentials` — which is exactly how the app's own cache
 * shipped without the behaviour that test asserts: the stand-in implemented
 * it, the real one did not.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class OfflineLifecycleTest {
    private fun grants(app: TestApp): Int = app.backend.log.count { it.endsWith("/api/companion/grant") }

    /**
     * The desktop widened (or narrowed) this phone's scopes and kicked it with
     * `scopes-changed`: the reconnect must carry a grant fetched after the
     * change, or the new scopes wait up to an hour for the cached one to expire.
     */
    @Test
    fun aKickForChangedScopesFetchesAFreshGrant() = runTest {
        val app = TestApp(backgroundScope)
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        app.connectTo(host)
        delay(5_000) // the app's own post-connect requests, before the kick
        val before = grants(app)
        val connections = host.connections
        host.kick("scopes-changed")
        withTimeout(60_000) { app.controller.state.first { it.connected && host.connections > connections } }
        assertEquals(before + 1, grants(app), "the reconnect after scopes-changed asks the backend again")
        app.controller.disconnect()
    }

    /**
     * Revoked while connected over the LAN, with the cached grant at the end of
     * its life. The backend refuses the next grant with 409; that ends the
     * round instead of being retried every 30 seconds — each retry used to
     * spend the account's per-user critical budget (20 per 20 minutes) that the
     * owner's other phones need to connect at all.
     */
    @Test
    fun aRevokedPhoneStopsAskingForGrants() = runTest {
        val app = TestApp(backgroundScope)
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        // Inside its last minute, so the cache will not reuse it.
        app.backend.handler = { _, path, _ ->
            if (path.endsWith("/api/companion/grant")) HttpStatusCode.OK to TestBackend.ok(
                """{"grant":"grant-token","expires_at":${Clock.System.now().epochSeconds + 59}}"""
            ) else null
        }
        app.connectTo(host)
        delay(5_000)
        app.backend.handler = { _, path, _ ->
            if (path.endsWith("/api/companion/grant")) {
                HttpStatusCode.Conflict to TestBackend.fail("该设备尚未激活或已被撤销", """{"status":"revoked"}""")
            } else null
        }
        host.rejectConnectWith = "unknown-device"
        val start = grants(app)
        host.kick("revoked", "这台设备已被撤销")
        delay(20 * 60 * 1000L)
        val failed = assertIs<ClientState.Failed>(app.controller.state.value.connection)
        assertEquals("revoked", failed.code)
        assertTrue(grants(app) - start <= 1, "one refused grant ends it; saw ${grants(app) - start} in 20 minutes")
        app.controller.disconnect()
    }

    /** A host row that is gone (404) is as final as a revoked device. */
    @Test
    fun aGoneHostRowAlsoEndsTheRound() = runTest {
        val app = TestApp(backgroundScope)
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        app.backend.handler = { _, path, _ ->
            if (path.endsWith("/api/companion/grant")) HttpStatusCode.NotFound to TestBackend.fail("电脑不存在") else null
        }
        TestApp.writePaired(app.files, listOf(TestApp.pairedHost(host.hostId, host.hostStatic, "dev-1")))
        app.carriers.lan = { host }
        app.controller.start()
        app.controller.connect(host.hostId)
        delay(20 * 60 * 1000L)
        val failed = assertIs<ClientState.Failed>(app.controller.state.value.connection)
        assertEquals("revoked", failed.code)
        assertTrue(grants(app) <= 1, "saw ${grants(app)} grant requests")
        app.controller.disconnect()
    }

    /**
     * 「忘记」 used to drop the pairing on the phone only. The desktop kept the
     * device enrolled and went on wrapping every new history epoch to a key
     * the user believed they had let go of.
     */
    @Test
    fun forgettingAHostRevokesThisPhoneThere() = runTest {
        val app = TestApp(backgroundScope)
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        app.backend.handler = { method, path, _ ->
            if (method == "DELETE" && path.endsWith("/api/companion/devices/dev-1")) HttpStatusCode.OK to TestBackend.ok("null") else null
        }
        app.connectTo(host)
        delay(5_000) // the app's own post-connect requests, before the disconnect
        app.controller.forgetHost(host.hostId)
        assertTrue("DELETE /api/companion/devices/dev-1" in app.backend.log, app.backend.log.toString())
        assertEquals(emptyList(), app.pairedOnDisk())
        assertEquals(null, app.controller.state.value.activeHostId)
    }

    /** Offline, the phone still forgets — and says the desktop must finish the job. */
    @Test
    fun forgettingWhileTheBackendIsDownStillForgetsAndSaysSo() = runTest {
        val app = TestApp(backgroundScope)
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        app.backend.handler = { method, path, _ ->
            if (method == "DELETE" && path.contains("/api/companion/devices/")) HttpStatusCode.BadGateway to TestBackend.fail("bad gateway") else null
        }
        app.connectTo(host)
        delay(5_000) // the app's own post-connect requests, before the disconnect
        app.controller.forgetHost(host.hostId)
        assertEquals(emptyList(), app.pairedOnDisk())
        val error = assertNotNull(app.controller.state.value.lastError)
        assertTrue("电脑" in error, error)
    }
}
