package com.skidsense.mobile.app

import com.skidsense.mobile.api.AuthSession
import com.skidsense.mobile.api.BackendClient
import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.KeyPair
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.store.MemoryFileStore
import com.skidsense.mobile.store.MemorySecretStore
import com.skidsense.mobile.store.SecretStore
import com.skidsense.mobile.store.putString
import com.skidsense.mobile.transport.FakeCarriers
import com.skidsense.mobile.transport.FakeHost
import com.skidsense.mobile.transport.RcJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

/**
 * A stand-in backend on Ktor's MockEngine, so the real [AppController] can be
 * driven end to end against [FakeHost] over in-memory carriers.
 *
 * The engine is pinned to the test's dispatcher: MockEngine otherwise answers
 * on `Dispatchers.IO`, and a virtual-time timeout in the test then fires
 * before a real-time answer arrives.
 */
class TestBackend(val base: String = "https://backend.example") {
    /** `METHOD /path` of every request, in order. */
    val log = mutableListOf<String>()

    /** Answers first; whatever it leaves (null) falls through to [basics]. */
    var handler: (method: String, path: String, body: String) -> Pair<HttpStatusCode, String>? = { _, _, _ -> null }

    fun client(secrets: SecretStore, dispatcher: CoroutineDispatcher? = null): BackendClient = BackendClient(
        HttpClient(MockEngine(MockEngineConfig().apply {
            if (dispatcher != null) this.dispatcher = dispatcher
            addHandler { request ->
                val body = (request.body as? TextContent)?.text ?: ""
                val path = request.url.encodedPath
                log += "${request.method.value} $path"
                val (status, text) = handler(request.method.value, path, body)
                    ?: basics(path)
                    ?: (HttpStatusCode.NotFound to fail("not found"))
                respond(ByteReadChannel(text), status, headersOf(HttpHeaders.ContentType to listOf("application/json")))
            }
        })),
        secrets,
        base
    )

    companion object {
        fun ok(data: String) = """{"success":true,"message":"","data":$data}"""
        fun fail(message: String, data: String = "null") = """{"success":false,"message":"$message","data":$data}"""
        fun grant() = ok("""{"grant":"grant-token","expires_at":${Clock.System.now().epochSeconds + 3600}}""")
        fun loginBody(userId: Long, name: String) =
            """{"access_token":"tok-$userId","access_expires_at":${Clock.System.now().epochSeconds + 900},"user":{"id":$userId,"username":"$name"},"session":{"id":"sess-$userId"}}"""

        /** What a signed-in phone needs from the backend on every path. */
        private fun basics(path: String): Pair<HttpStatusCode, String>? = when {
            path.endsWith("/api/companion/hosts") -> HttpStatusCode.OK to ok("[]")
            path.endsWith("/api/companion/grant") -> HttpStatusCode.OK to grant()
            path.endsWith("/api/user/auth/logout") -> HttpStatusCode.OK to ok("null")
            path.endsWith("/encryption-key") -> HttpStatusCode.OK to ok("""{"enabled":false}""")
            else -> null
        }
    }
}

/** The real controller over a [TestBackend] and [FakeCarriers], with in-memory stores. */
class TestApp(
    scope: CoroutineScope,
    val backend: TestBackend = TestBackend(),
    val secrets: MemorySecretStore = MemorySecretStore(),
    val files: MemoryFileStore = MemoryFileStore(),
    signedInAs: Long? = 42,
    accessExpiresAt: Long = Clock.System.now().toEpochMilliseconds() + 3_600_000
) {
    val carriers = FakeCarriers(scope)

    init {
        if (signedInAs != null) {
            secrets.putString(
                "auth-session",
                RcJson.encodeToString(
                    AuthSession.serializer(),
                    AuthSession(backend.base, "tok", accessExpiresAt, "cookie", "sess", signedInAs, "user$signedInAs")
                )
            )
        }
    }

    val backendClient = backend.client(secrets, scope.coroutineContext[CoroutineDispatcher])
    val controller = AppController(backendClient, carriers, secrets, files, "android", "Test Phone", scope)

    fun pairedOnDisk(): List<PairedHost> =
        files.read("paired-hosts.json")?.let { RcJson.decodeFromString(ListSerializer(PairedHost.serializer()), it) } ?: emptyList()

    /** Pair [host] on disk, start, connect over the LAN, and wait until the connection is up. */
    suspend fun connectTo(host: FakeHost, deviceId: String = "dev-1") {
        writePaired(files, listOf(pairedHost(host.hostId, host.hostStatic, deviceId, base = backend.base)))
        carriers.lan = { host }
        controller.start()
        controller.connect(host.hostId)
        withTimeout(60_000) { controller.state.first { it.connected } }
    }

    companion object {
        fun writePaired(files: MemoryFileStore, hosts: List<PairedHost>) =
            files.write("paired-hosts.json", RcJson.encodeToString(ListSerializer(PairedHost.serializer()), hosts))

        /**
         * A stored pairing. [userId] is written into the JSON rather than through
         * the constructor, so the same helper describes a record from before
         * pairings carried their account (0 leaves it out) and one from after.
         */
        fun pairedHost(
            hostId: String,
            key: KeyPair,
            deviceId: String,
            base: String = "https://backend.example",
            name: String = hostId,
            userId: Long = 42
        ): PairedHost {
            val plain = RcJson.encodeToJsonElement(
                PairedHost.serializer(),
                PairedHost(
                    hostId = hostId,
                    hostKey = B64u.encode(key.pub),
                    deviceId = deviceId,
                    name = name,
                    machine = name,
                    lanAddrs = listOf("192.168.1.20"),
                    lanPort = 47290,
                    server = base,
                    pairedAt = 1
                )
            ) as JsonObject
            val stored = if (userId == 0L) JsonObject(plain - "userId") else JsonObject(plain + ("userId" to JsonPrimitive(userId)))
            return RcJson.decodeFromJsonElement(PairedHost.serializer(), stored)
        }

        fun newHostId(): String = B64u.encode(Primitives.randomBytes(16))
    }
}
