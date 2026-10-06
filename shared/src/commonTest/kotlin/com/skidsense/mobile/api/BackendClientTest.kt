package com.skidsense.mobile.api

import com.skidsense.mobile.store.MemorySecretStore
import com.skidsense.mobile.store.getString
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backend client against a mock engine: the `{success,message,data}`
 * envelope, the refresh-once-on-401 rule, the single `new_api_refresh` cookie,
 * and the companion endpoints' paths and shapes.
 */
class BackendClientTest {
    private val base = "https://backend.example"

    private class Recorded {
        val requests = mutableListOf<HttpRequestData>()
        fun last() = requests.last()
        fun paths() = requests.map { it.url.encodedPath }
    }

    private fun client(
        secrets: MemorySecretStore = MemorySecretStore(),
        recorded: Recorded = Recorded(),
        handler: (HttpRequestData) -> Pair<HttpStatusCode, String>
    ): Pair<BackendClient, Recorded> {
        val engine = MockEngine { request ->
            recorded.requests += request
            val (status, body) = handler(request)
            respond(
                ByteReadChannel(body),
                status,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.SetCookie to listOf("new_api_refresh=cookie-1; Path=/; HttpOnly")
                )
            )
        }
        return BackendClient(HttpClient(engine), secrets, base) to recorded
    }

    private fun envelopeSuccess(data: String) = """{"success":true,"message":"","data":$data}"""

    private fun loginBody(token: String = "tok-1") =
        """{"access_token":"$token","access_expires_at":${Clock.System.now().epochSeconds + 900},"user":{"id":42,"username":"liunian"},"session":{"id":"sess-1"}}"""

    /**
     * The whole app is Chinese, and new-api picks its message language from
     * the user's setting or this header — without it, a refusal such as a
     * revoked device's arrived in English in the middle of a Chinese screen.
     */
    @Test
    fun everyRequestAsksForChineseMessages() = runTest {
        val (client, recorded) = client { HttpStatusCode.OK to envelopeSuccess("""{"system_name":"x"}""") }
        runCatching { client.status(base) }
        assertEquals("zh-CN", recorded.last().headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun loginStoresTheTokenCookieAndSession() = runTest {
        val secrets = MemorySecretStore()
        val (client, recorded) = client(secrets) { request: HttpRequestData ->
            when {
                request.url.encodedPath.endsWith("encryption-key") -> HttpStatusCode.OK to envelopeSuccess("""{"enabled":false}""")
                else -> HttpStatusCode.OK to respondWithCookie(loginBody())
            }
        }
        assertNull(client.login(base, "liunian", "pw", geetest = null, turnstile = null))
        val session = assertNotNull(client.session.value)
        assertEquals("tok-1", session.accessToken)
        assertEquals("cookie-1", session.refreshCookie)
        assertEquals("sess-1", session.sessionId)
        assertEquals(42L, session.userId)
        assertEquals("liunian", session.username)
        assertTrue(secrets.getString("auth-session")!!.contains("tok-1"))
        // The server-side session is what the client sends back on refresh.
        assertEquals(listOf("/api/user/login/encryption-key", "/api/user/login"), recorded.paths())
    }

    @Test
    fun loginSendsTheGeetestValidateAsAQueryParameter() = runTest {
        val (client, recorded) = client { _: HttpRequestData -> HttpStatusCode.OK to respondWithCookie(loginBody()) }
        val validate = """{"lot_number":"lot","captcha_output":"cap","pass_token":"pass","gen_time":"1700000000"}"""
        client.login(base, "u", "p", geetest = validate, turnstile = null)
        // No encryption-key call here: the request still goes, and the raw
        // password is sent when the server says encryption is off.
        val login = recorded.requests.first { it.url.encodedPath.endsWith("/api/user/login") }
        assertEquals(validate, login.url.parameters["geetest"])
        assertEquals(null, login.url.parameters["turnstile"])
    }

    @Test
    fun secondFactorIsReturnedAsAChallenge() = runTest {
        val (client, _) = client { _: HttpRequestData ->
            HttpStatusCode.OK to envelopeSuccess(
                """{"require_verification":true,"flow_token":"flow-1","expires_at":1,"methods":[{"method":"2fa","available":true},{"method":"passkey","available":false,"reason":"no key"}]}"""
            )
        }
        val challenge = assertNotNull(client.login(base, "u", "p", null, null))
        assertEquals("flow-1", challenge.flowToken)
        assertEquals("2fa", challenge.methods[0].method)
        assertEquals(false, challenge.methods[1].available)
        assertNull(client.session.value)
    }

    @Test
    fun verifyCompletesTheChallenge() = runTest {
        val (client, recorded) = client { request: HttpRequestData ->
            if (request.url.encodedPath.endsWith("/verify")) HttpStatusCode.OK to respondWithCookie(loginBody("tok-2"))
            else HttpStatusCode.OK to envelopeSuccess("""{"enabled":false}""")
        }
        client.verifyLogin(base, "flow-1", "123456")
        assertEquals("tok-2", client.session.value?.accessToken)
        assertTrue(recorded.paths().last().endsWith("/api/user/login/verify"))
    }

    @Test
    fun aFailedCallCarriesTheServersMessage() = runTest {
        val (client, _) = client { _: HttpRequestData -> HttpStatusCode.OK to """{"success":false,"message":"密码错误","data":null}""" }
        val error = assertFailsWith<BackendException> { client.login(base, "u", "p", null, null) }
        assertEquals("密码错误", error.message)
    }

    @Test
    fun a401RefreshesOnceThenSucceeds() = runTest {
        val secrets = MemorySecretStore()
        var refreshCalls = 0
        val (client, recorded) = client(secrets) { request: HttpRequestData ->
            when {
                request.url.encodedPath.endsWith("encryption-key") -> HttpStatusCode.OK to envelopeSuccess("""{"enabled":false}""")
                request.url.encodedPath.endsWith("/api/user/login") -> HttpStatusCode.OK to respondWithCookie(loginBody("stale"))
                request.url.encodedPath.endsWith("/auth/refresh") -> {
                    refreshCalls += 1
                    HttpStatusCode.OK to envelopeSuccess(loginBody("fresh"))
                }
                request.url.encodedPath.endsWith("/hosts") -> {
                    val bearer = request.headers[HttpHeaders.Authorization]
                    if (bearer == "Bearer stale") HttpStatusCode.Unauthorized to """{"success":false,"message":"token expired"}"""
                    else HttpStatusCode.OK to envelopeSuccess("""[{"host_id":"h1","name":"Mac","online":true}]""")
                }
                else -> HttpStatusCode.OK to envelopeSuccess("null")
            }
        }
        client.login(base, "u", "p", null, null)
        // The access token is considered fresh by the login response, so the
        // 401 path is what triggers the refresh.
        val hosts = client.hosts()
        assertEquals("h1", hosts.single().hostId)
        assertTrue(hosts.single().online)
        assertEquals(1, refreshCalls)
        assertEquals("Bearer stale", recorded.requests.first { it.url.encodedPath.endsWith("/hosts") }.headers[HttpHeaders.Authorization])
        assertEquals("fresh", client.session.value?.accessToken)
    }

    @Test
    fun refreshSendsTheCookieAndSessionHeader() = runTest {
        val secrets = MemorySecretStore()
        val (client, recorded) = client(secrets) { request: HttpRequestData ->
            when {
                request.url.encodedPath.endsWith("encryption-key") -> HttpStatusCode.OK to envelopeSuccess("""{"enabled":false}""")
                request.url.encodedPath.endsWith("/api/user/login") -> HttpStatusCode.OK to respondWithCookie(loginBody())
                else -> HttpStatusCode.OK to envelopeSuccess(loginBody("tok-2"))
            }
        }
        client.login(base, "u", "p", null, null)
        client.invalidateAccessToken()
        assertEquals("tok-2", client.accessToken())
        val refresh = recorded.requests.first { it.url.encodedPath.endsWith("/auth/refresh") }
        assertEquals("new_api_refresh=cookie-1", refresh.headers[HttpHeaders.Cookie])
        assertEquals("sess-1", refresh.headers["X-Auth-Session"])
    }

    @Test
    fun aRefusedRefreshIsARealLogout() = runTest {
        val secrets = MemorySecretStore()
        val (client, _) = client(secrets) { request: HttpRequestData ->
            when {
                request.url.encodedPath.endsWith("encryption-key") -> HttpStatusCode.OK to envelopeSuccess("""{"enabled":false}""")
                request.url.encodedPath.endsWith("/api/user/login") -> HttpStatusCode.OK to respondWithCookie(loginBody())
                else -> HttpStatusCode.Unauthorized to """{"success":false,"message":"invalid refresh"}"""
            }
        }
        client.login(base, "u", "p", null, null)
        client.invalidateAccessToken()
        assertNull(client.accessToken())
        assertNull(client.session.value)
        assertNull(secrets.getString("auth-session"))
    }

    @Test
    fun companionPathsAndShapes() = runTest {
        val (client, recorded) = companionClient()
        client.login(base, "u", "p", null, null)
        val device = client.registerDevice("h1", "我的手机", "pub-key", "android")
        assertEquals("dev-1", device.device.deviceId)
        assertEquals("ticket-1", device.ticket)
        client.grant("h1", "dev-1")
        assertEquals(1, client.devices("h1").size)
        client.updateDevice("dev-1", name = "新名字", scopes = listOf("sessions", "prompt"))
        client.revokeDevice("dev-1")
        val config = client.companionConfig()
        assertEquals(true, config.enabled)
        assertEquals(3600, config.accessTtl)
        client.historyKeys("h1", "dev-1")
        client.historySessions("h1", since = 5)
        client.historyBlob("h1", "claude:1")

        assertTrue("/api/companion/devices".let { p -> recorded.paths().contains(p) })
        assertEquals("/api/companion/devices/dev-1", recorded.requests.first { it.method.value == "PATCH" }.url.encodedPath)
        assertEquals(listOf("sessions", "prompt"), recorded.requests.first { it.method.value == "PATCH" }.bodyText().let { body ->
            Regex("\"scopes\":\\[(.*?)\\]").find(body)!!.groupValues[1].split(",").map { it.trim('"') }
        })
        val sessionsList = recorded.requests.filter { it.url.encodedPath == "/api/companion/history/sessions" }
        assertEquals(1, sessionsList.size)
        assertEquals("5", sessionsList.single().url.parameters["since"])
        assertEquals("claude:1", recorded.requests.last().url.parameters["session_key"])
    }

    @Test
    fun companionRequiresALogin() = runTest {
        val (client, _) = client { _: HttpRequestData -> HttpStatusCode.OK to envelopeSuccess("null") }
        val error = assertFailsWith<BackendException> { client.hosts() }
        assertEquals("尚未登录", error.message)
    }

    @Test
    fun nonJsonBodiesAreReportedClearly() = runTest {
        val (client, _) = client { _: HttpRequestData -> HttpStatusCode.OK to "<html>proxy error</html>" }
        val error = assertFailsWith<BackendException> { client.status(base) }
        assertTrue(error.message!!.contains("无法解析"), error.message)
    }

    @Test
    fun unreachableServersAreReportedWithTheAddress() = runTest {
        val engine = MockEngine { throw IllegalStateException("connection refused") }
        val client = BackendClient(HttpClient(engine), MemorySecretStore(), base)
        val error = assertFailsWith<BackendException> { client.status(base) }
        assertTrue(error.message!!.contains(base), error.message)
    }

    @Test
    fun logoutClearsStateEvenWhenTheServerFails() = runTest {
        val secrets = MemorySecretStore()
        val (client, _) = client(secrets) { request: HttpRequestData ->
            when {
                request.url.encodedPath.endsWith("encryption-key") -> HttpStatusCode.OK to envelopeSuccess("""{"enabled":false}""")
                request.url.encodedPath.endsWith("/api/user/login") -> HttpStatusCode.OK to respondWithCookie(loginBody())
                else -> HttpStatusCode.InternalServerError to """{"success":false,"message":"boom"}"""
            }
        }
        client.login(base, "u", "p", null, null)
        client.logout()
        assertNull(client.session.value)
        assertNull(secrets.getString("auth-session"))
    }

    private fun companionClient(): Pair<BackendClient, Recorded> = client { request: HttpRequestData ->
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/api/user/login") -> respondWithCookie(loginBody())
            path.endsWith("/api/companion/config") ->
                envelopeSuccess("""{"enabled":true,"grant_public_key":"k","access_ttl":3600,"enroll_ttl":600,"ws_path":"/api/companion/ws","history":{"enabled":true,"max_blob_bytes":8388608,"max_user_bytes":268435456}}""")
            path.endsWith("/api/companion/hosts") -> envelopeSuccess("""[{"host_id":"h1","name":"Mac","platform":"darwin","lan_addrs":["192.168.1.20"],"lan_port":47290,"online":true,"created_at":1,"last_seen_at":2}]""")
            path.endsWith("/api/companion/devices") && request.method.value == "POST" ->
                envelopeSuccess("""{"device":{"device_id":"dev-1","host_id":"h1","name":"我的手机","public_key":"pub-key","scopes":["sessions"],"status":"pending"},"ticket":"ticket-1","ticket_expires_at":3}""")
            path.endsWith("/api/companion/devices") -> envelopeSuccess("""[{"device_id":"dev-1","host_id":"h1","name":"我的手机","status":"active","scopes":["sessions"]}]""")
            path.contains("/api/companion/devices/") -> envelopeSuccess("""{"device_id":"dev-1","host_id":"h1","name":"新名字","status":"active","scopes":["sessions","prompt"]}""")
            path.endsWith("/api/companion/grant") -> envelopeSuccess("""{"grant":"a.b","expires_at":99}""")
            path.endsWith("/api/companion/history/keys") -> envelopeSuccess("""[{"epoch":1,"wrapped":"w"}]""")
            path.endsWith("/api/companion/history/sessions/blob") -> envelopeSuccess("""{"session_key":"claude:1","epoch":1,"updated_at":5,"blob":"b"}""")
            path.endsWith("/api/companion/history/sessions") -> envelopeSuccess("""[{"session_key":"claude:1","epoch":1,"updated_at":5,"size":10}]""")
            else -> envelopeSuccess("null")
        }
        HttpStatusCode.OK to body
    }

    private fun respondWithCookie(body: String) = """{"success":true,"message":"","data":$body}"""

    private fun HttpRequestData.bodyText(): String =
        (this.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            ?: (this.body as? io.ktor.http.content.TextContent)?.text
            ?: ""
}
