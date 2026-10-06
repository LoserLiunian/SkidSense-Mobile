package com.skidsense.mobile.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The one lenient JSON configuration everything inbound is parsed with: the desktop keeps adding fields. */
val RcJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = false
    encodeDefaults = true
}

@Serializable
data class AppInfo(val name: String, val version: String, val platform: String)

@Serializable
data class WelcomeHost(val id: String = "", val name: String = "", val version: String = "", val platform: String = "")

@Serializable
data class WelcomeDevice(val id: String = "", val scopes: List<String> = emptyList())

@Serializable
data class WelcomeUser(val id: Long = 0, val name: String? = null)

/** The host's answer to `hello` (spec §6.1). [methods] lists only what this device may call. */
@Serializable
data class Welcome(
    val v: Int = 1,
    val host: WelcomeHost = WelcomeHost(),
    val device: WelcomeDevice = WelcomeDevice(),
    val user: WelcomeUser? = null,
    val methods: List<String> = emptyList()
) {
    fun can(method: String): Boolean = method in methods
    fun hasScope(scope: String): Boolean = scope in device.scopes
}

/** Builders for the inner messages the device sends (spec §6). */
object Inner {
    fun hello(app: AppInfo, grant: String?, ticket: String?): String = buildJsonObject {
        put("t", "hello")
        putJsonArray("v") { add(kotlinx.serialization.json.JsonPrimitive(1)) }
        putJsonObject("app") {
            put("name", app.name)
            put("version", app.version)
            put("platform", app.platform)
        }
        if (ticket != null) put("ticket", ticket) else if (grant != null) put("grant", grant)
    }.toString()

    fun request(id: String, method: String, params: JsonElement?): String = buildJsonObject {
        put("t", "req")
        put("id", id)
        put("m", method)
        if (params != null) put("p", params)
    }.toString()

    fun ping(ts: Long): String = buildJsonObject { put("t", "ping"); put("ts", ts) }.toString()
    fun pong(ts: Long): String = buildJsonObject { put("t", "pong"); put("ts", ts) }.toString()
    fun bye(reason: String): String = buildJsonObject { put("t", "bye"); put("reason", reason) }.toString()
}

/** One `ev` message (spec §6.4). */
data class RcEvent(val kind: String, val payload: JsonElement)

/** Everything that can go wrong talking to a host, with a Chinese message fit for the UI. */
open class RcException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The host answered `ok:false` (spec §6.2). */
class RemoteCallError(code: String, message: String) : RcException(code, message)

/** The host refused the handshake with `hsr` (spec §4.6). */
class HandshakeRejected(code: String, message: String) : RcException(code, message) {
    /** Codes that will not change by retrying the same thing. */
    val permanent: Boolean get() = code in setOf("unknown-device", "disabled", "unsupported-version", "enroll-closed")
}

/** The relay itself refused (spec §10.2), before or instead of the host. */
class RelayRejected(code: String, message: String) : RcException(code, message) {
    val permanent: Boolean get() = code == "revoked"
}

/**
 * The desktop ended the connection and said why with a machine-readable code
 * (spec §6.5, C5). A kick whose grant scopes no longer stand must make the
 * reconnect ask the backend for a new grant: the cached `rc-access` still
 * lists the old ones for up to an hour, so reconnecting with it brings back
 * exactly what the kick was meant to change.
 */
class HostBye(val byeCode: String?, message: String) : RcException("bye", message) {
    val regrant: Boolean get() = byeCode == "scopes-changed" || byeCode == "revoked"
}

/** The connection ended (bye, carrier closed, protocol violation, timeout). */
open class ConnectionClosed(message: String, cause: Throwable? = null) : RcException("closed", message, cause)

/**
 * The connection ended *while the handshake between `hs2` and `welcome` was
 * still in doubt*: the carrier dropped mid-hello, or a plaintext frame
 * arrived after the host had proven its key. Distinct from both a refusal
 * ([HandshakeRejected]) and an established connection dying: this route is
 * dead, and the device is not (spec §6.5, C6).
 */
class HandshakeClosed(message: String, cause: Throwable? = null) : ConnectionClosed(message, cause)

internal fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal val HSR_MESSAGES: Map<String, String> = mapOf(
    "unsupported-version" to "电脑端的协议版本与手机端不一致，请更新",
    "wrong-host" to "连到的不是配对的那台电脑",
    "enroll-closed" to "配对码已失效，请在电脑上重新生成二维码",
    "unknown-device" to "这台手机没有与该电脑配对，或已被撤销",
    "handshake-failed" to "握手失败：配对码不对，或不是你配对的那台电脑",
    "replayed" to "握手被判为重放，请重试",
    "rate-limited" to "尝试太频繁，请稍后再试",
    "disabled" to "电脑端关闭了远程控制"
)

internal val RELAY_MESSAGES: Map<String, String> = mapOf(
    "host-offline" to "电脑不在线",
    "unauthorized" to "登录已失效，请重新登录",
    "revoked" to "这台手机已被撤销，需要重新配对",
    "rate-limited" to "请求太频繁，请稍后再试",
    "too-large" to "消息过大",
    "superseded" to "这台手机在别处连上了同一台电脑",
    "shutdown" to "中继服务正在重启"
)
