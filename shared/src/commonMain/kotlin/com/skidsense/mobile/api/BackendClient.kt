package com.skidsense.mobile.api

import com.skidsense.mobile.store.SecretStore
import com.skidsense.mobile.store.getString
import com.skidsense.mobile.store.putString
import com.skidsense.mobile.transport.RcJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock

/** A backend-reported failure, carrying the server's message for the UI. */
class BackendException(message: String, val status: Int = 0, val data: JsonElement? = null) : Exception(message)

/** The signed-in session. Persisted, encrypted, in the [SecretStore]. */
@Serializable
data class AuthSession(
    val baseUrl: String,
    val accessToken: String,
    /** Local expiry estimate, ms. */
    val accessExpiresAt: Long = 0,
    val refreshCookie: String? = null,
    val sessionId: String? = null,
    val userId: Long = 0,
    val username: String = ""
)

/**
 * The phone's new-api client: login (spec §12, mirroring the desktop's
 * `src/main/backend.ts`) and the companion API (spec §9).
 *
 * Auth is a short-lived bearer plus the `new_api_refresh` cookie. There is no
 * cookie jar: that one cookie is captured from `Set-Cookie` by hand and
 * replayed on refresh with `X-Auth-Session`. Authenticated calls refresh once
 * on a 401 and retry. Redirects are not followed — a redirect to another host
 * would be a misconfigured deployment, not something to send the bearer to.
 */
class BackendClient(
    private val http: HttpClient,
    private val secrets: SecretStore,
    var defaultBaseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://ai.surise.cn"
        private const val SESSION_KEY = "auth-session"
        private const val REFRESH_COOKIE = "new_api_refresh"
        private const val ACCESS_FALLBACK_MS = 14 * 60 * 1000L
        private fun now() = Clock.System.now().toEpochMilliseconds()
    }

    private val refreshLock = Mutex()
    private val _session = MutableStateFlow(load())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    val baseUrl: String get() = _session.value?.baseUrl ?: defaultBaseUrl

    private fun load(): AuthSession? = runCatching {
        secrets.getString(SESSION_KEY)?.let { RcJson.decodeFromString(AuthSession.serializer(), it) }
    }.getOrNull()

    private fun save(session: AuthSession?) {
        if (session == null) secrets.delete(SESSION_KEY)
        else secrets.putString(SESSION_KEY, RcJson.encodeToString(AuthSession.serializer(), session))
        _session.value = session
    }

    // --- low level -------------------------------------------------------------

    private class Raw(val status: Int, val json: JsonObject?, val setCookies: List<String>)

    private suspend fun raw(
        base: String,
        path: String,
        method: HttpMethod = HttpMethod.Get,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
        bearer: String? = null,
        cookie: String? = null,
        sessionId: String? = null
    ): Raw {
        val url = URLBuilder().takeFrom(base.trimEnd('/') + "/" + path.trimStart('/')).apply {
            for ((key, value) in query) if (value != null) parameters.append(key, value)
        }.build()
        val response: HttpResponse = try {
            http.request(url) {
                this.method = method
                header(HttpHeaders.Accept, "application/json")
                if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                if (cookie != null) header(HttpHeaders.Cookie, cookie)
                if (sessionId != null) header("X-Auth-Session", sessionId)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw BackendException("无法连接服务器（$base）：${error.message ?: error::class.simpleName}")
        }
        val text = response.bodyAsText()
        val json = runCatching { RcJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        return Raw(response.status.value, json, response.headers.getAll(HttpHeaders.SetCookie) ?: emptyList())
    }

    private fun unwrap(res: Raw, base: String): JsonElement {
        val json = res.json ?: throw BackendException(
            if (res.status >= 400) "服务器返回 HTTP ${res.status}" else "服务器返回了无法解析的内容（$base 是不是填错了？）",
            res.status
        )
        if ((json["success"] as? JsonPrimitive)?.booleanOrNull == false || res.status >= 400) {
            val message = (json["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "请求失败（HTTP ${res.status}）"
            throw BackendException(message, res.status, json["data"])
        }
        return json["data"] ?: JsonNull
    }

    /** A current access token, refreshing first when it is missing or about to expire. */
    suspend fun accessToken(): String? {
        val current = _session.value ?: return null
        if (current.accessToken.isNotEmpty() && current.accessExpiresAt > now() + 30_000) return current.accessToken
        return if (refresh()) _session.value?.accessToken else null
    }

    /** Drop the access token so the next call refreshes (the relay said 401). */
    fun invalidateAccessToken() {
        _session.value?.let { save(it.copy(accessExpiresAt = 0)) }
    }

    private suspend fun authed(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null
    ): JsonElement {
        val base = _session.value?.baseUrl ?: throw BackendException("尚未登录")
        val token = accessToken() ?: throw BackendException("登录已过期，请重新登录", 401)
        val res = raw(base, path, method, query, body, bearer = token)
        if (res.status == 401) {
            invalidateAccessToken()
            val again = accessToken() ?: throw BackendException("登录已过期，请重新登录", 401)
            return unwrap(raw(base, path, method, query, body, bearer = again), base)
        }
        return unwrap(res, base)
    }

    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = try {
        RcJson.decodeFromJsonElement(serializer, element)
    } catch (error: Exception) {
        throw BackendException("服务器返回的数据格式不对：${error.message}")
    }

    // --- login (spec §12) --------------------------------------------------------

    suspend fun status(base: String): ServerStatus = decode(ServerStatus.serializer(), unwrap(raw(base, "api/status"), base))

    private suspend fun passwordFields(base: String, password: String): Map<String, String> {
        val key = unwrap(raw(base, "api/user/login/encryption-key"), base) as? JsonObject ?: return mapOf("password" to password)
        if ((key["enabled"] as? JsonPrimitive)?.booleanOrNull != true) return mapOf("password" to password)
        val publicKey = (key["public_key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val kid = (key["kid"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (publicKey.isEmpty() || kid.isEmpty()) return mapOf("password" to password)
        val envelope = try {
            PasswordEnvelope.encrypt(password, publicKey, kid)
        } catch (error: PasswordEnvelope.Unsupported) {
            throw BackendException(error.message ?: "这个平台还不支持登录加密", 0)
        }
        return mapOf("password_encrypted" to envelope, "encryption_key_id" to kid)
    }

    /**
     * Returns null when signed in, or the second-factor challenge. [geetest] is
     * the JSON of GeeTest v4's `getValidate()`; [turnstile] a Turnstile token.
     */
    suspend fun login(base: String, username: String, password: String, geetest: String?, turnstile: String?): LoginChallenge? {
        val cleanBase = base.trim().trimEnd('/')
        val fields = passwordFields(cleanBase, password)
        val body = buildJsonObject {
            put("username", username)
            for ((key, value) in fields) put(key, value)
        }
        val res = raw(
            cleanBase, "api/user/login", HttpMethod.Post,
            query = mapOf("geetest" to geetest, "turnstile" to turnstile),
            body = body
        )
        val data = unwrap(res, cleanBase) as? JsonObject ?: JsonObject(emptyMap())
        val token = (data["access_token"] as? JsonPrimitive)?.contentOrNull
        if (!token.isNullOrEmpty()) {
            storeSession(cleanBase, data, res.setCookies)
            return null
        }
        if ((data["require_verification"] as? JsonPrimitive)?.booleanOrNull == true) {
            val flow = (data["flow_token"] as? JsonPrimitive)?.contentOrNull ?: throw BackendException("登录需要二次验证，但服务器没有给出验证流程")
            val methods = (data["methods"] as? JsonArray)?.mapNotNull {
                runCatching { RcJson.decodeFromJsonElement(LoginMethod.serializer(), it) }.getOrNull()
            } ?: emptyList()
            return LoginChallenge(flow, methods)
        }
        throw BackendException("登录失败：服务器没有返回凭证")
    }

    /** Complete a challenge with a TOTP or a backup code. */
    suspend fun verifyLogin(base: String, flowToken: String, code: String) {
        val cleanBase = base.trim().trimEnd('/')
        val res = raw(
            cleanBase, "api/user/login/verify", HttpMethod.Post,
            body = buildJsonObject {
                put("flow_token", flowToken)
                put("method", "2fa")
                put("code", code.trim())
            }
        )
        val data = unwrap(res, cleanBase) as? JsonObject
        if ((data?.get("access_token") as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) throw BackendException("验证码错误或已过期")
        storeSession(cleanBase, data, res.setCookies)
    }

    /** Mint a fresh access token from the refresh cookie. Single-flight. */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        val current = _session.value ?: return@withLock false
        if (current.accessExpiresAt > now() + 30_000 && current.accessToken.isNotEmpty()) return@withLock true
        val cookie = current.refreshCookie ?: return@withLock false
        val res = try {
            raw(current.baseUrl, "api/user/auth/refresh", HttpMethod.Post, cookie = "$REFRESH_COOKIE=$cookie", sessionId = current.sessionId)
        } catch (error: BackendException) {
            return@withLock false
        }
        val json = res.json
        if (json == null || (json["success"] as? JsonPrimitive)?.booleanOrNull == false) {
            // A refused refresh token is a real logout, not a blip.
            if (res.status == 401 || res.status == 403) save(null)
            return@withLock false
        }
        val data = json["data"] as? JsonObject ?: return@withLock false
        if ((data["access_token"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()) return@withLock false
        storeSession(current.baseUrl, data, res.setCookies)
        true
    }

    suspend fun logout() {
        val current = _session.value ?: return
        try {
            raw(
                current.baseUrl, "api/user/auth/logout", HttpMethod.Post,
                bearer = current.accessToken,
                cookie = current.refreshCookie?.let { "$REFRESH_COOKIE=$it" },
                sessionId = current.sessionId
            )
        } catch (_: Exception) {
            // A failed server-side revoke must not strand the user signed in locally.
        } finally {
            save(null)
        }
    }

    private fun storeSession(base: String, data: JsonObject, setCookies: List<String>) {
        val user = data["user"] as? JsonObject
        val session = data["session"] as? JsonObject
        val sessionId = (session?.get("id") as? JsonPrimitive)?.contentOrNull ?: (session?.get("sid") as? JsonPrimitive)?.contentOrNull
        val pattern = Regex("(?:^|;\\s*)$REFRESH_COOKIE=([^;]+)")
        val cookie = setCookies.firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1) } ?: _session.value?.refreshCookie
        val expiresRaw = (data["access_expires_at"] as? JsonPrimitive)
        val expires = expiresRaw?.longOrNull?.let { if (it > 1_000_000_000_000L) it else it * 1000 }
            ?: (now() + ACCESS_FALLBACK_MS)
        val previous = _session.value
        save(
            AuthSession(
                baseUrl = base,
                accessToken = (data["access_token"] as JsonPrimitive).content,
                accessExpiresAt = expires,
                refreshCookie = cookie,
                sessionId = sessionId ?: previous?.sessionId,
                userId = (user?.get("id") as? JsonPrimitive)?.longOrNull ?: previous?.userId ?: 0,
                username = (user?.get("username") as? JsonPrimitive)?.contentOrNull ?: previous?.username.orEmpty()
            )
        )
    }

    suspend fun self(): UserInfo = decode(UserInfo.serializer(), authed("api/user/self"))

    // --- companion (spec §9) -----------------------------------------------------

    suspend fun companionConfig(): CompanionConfig = decode(CompanionConfig.serializer(), authed("api/companion/config"))

    suspend fun hosts(): List<HostRow> = decode(ListSerializer(HostRow.serializer()), authed("api/companion/hosts").orEmptyArray())

    suspend fun deleteHost(hostId: String) {
        authed("api/companion/hosts/${encodePath(hostId)}", HttpMethod.Delete)
    }

    /**
     * Register this phone's static key for a host. A 409 means this exact key
     * is already active there; [BackendException.data] then names the device.
     */
    suspend fun registerDevice(hostId: String, name: String, publicKey: String, platform: String): DeviceRegistration =
        decode(
            DeviceRegistration.serializer(),
            authed("api/companion/devices", HttpMethod.Post, body = buildJsonObject {
                put("host_id", hostId)
                put("name", name)
                put("public_key", publicKey)
                put("platform", platform)
            })
        )

    suspend fun devices(hostId: String): List<DeviceRow> =
        decode(ListSerializer(DeviceRow.serializer()), authed("api/companion/devices", query = mapOf("host_id" to hostId)).orEmptyArray())

    suspend fun updateDevice(deviceId: String, name: String? = null, scopes: List<String>? = null): DeviceRow =
        decode(
            DeviceRow.serializer(),
            authed("api/companion/devices/${encodePath(deviceId)}", HttpMethod.Patch, body = buildJsonObject {
                if (name != null) put("name", name)
                if (scopes != null) putJsonArray("scopes") { scopes.forEach { add(JsonPrimitive(it)) } }
            })
        )

    suspend fun revokeDevice(deviceId: String) {
        authed("api/companion/devices/${encodePath(deviceId)}", HttpMethod.Delete)
    }

    suspend fun grant(hostId: String, deviceId: String): GrantResponse =
        decode(
            GrantResponse.serializer(),
            authed("api/companion/grant", HttpMethod.Post, body = buildJsonObject {
                put("host_id", hostId)
                put("device_id", deviceId)
            })
        )

    suspend fun historyKeys(hostId: String, deviceId: String): List<HistoryKeyRow> =
        decode(ListSerializer(HistoryKeyRow.serializer()), authed("api/companion/history/keys", query = mapOf("host_id" to hostId, "device_id" to deviceId)).orEmptyArray())

    suspend fun historySessions(hostId: String, since: Long = 0): List<HistorySessionRow> =
        decode(
            ListSerializer(HistorySessionRow.serializer()),
            authed("api/companion/history/sessions", query = mapOf("host_id" to hostId, "since" to since.toString())).orEmptyArray()
        )

    suspend fun historyBlob(hostId: String, sessionKey: String): HistoryBlobRow =
        decode(HistoryBlobRow.serializer(), authed("api/companion/history/sessions/blob", query = mapOf("host_id" to hostId, "session_key" to sessionKey)))

    private fun JsonElement.orEmptyArray(): JsonElement = if (this is JsonNull) JsonArray(emptyList()) else this

    private fun encodePath(segment: String): String {
        // Ids are base64url (spec §9): nothing to escape, but refuse anything else.
        require(Regex("^[A-Za-z0-9_-]+$").matches(segment)) { "无效的 id" }
        return segment
    }
}
