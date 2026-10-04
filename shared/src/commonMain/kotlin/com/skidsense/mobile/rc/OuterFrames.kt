package com.skidsense.mobile.rc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The outer layer (spec §4–§5): what a carrier — a LAN socket or the relay —
 * sees. `hs1`/`hs2`/`hsr` during the handshake, then `d` frames of ciphertext.
 * Field order matches the reference so serialised frames are byte-identical.
 */
data class Hs1Frame(val v: Int, val mode: String, val host: String, val e: String, val s: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("t", "hs1")
        put("v", v)
        put("mode", mode)
        put("host", host)
        put("e", e)
        put("s", s)
    }
}

data class Hs2Frame(val e: String, val c: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("t", "hs2")
        put("e", e)
        put("c", c)
    }
}

data class HsRejectFrame(val code: String, val message: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("t", "hsr")
        put("code", code)
        put("message", message)
    }
}

data class DataFrame(val n: Long, val c: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("t", "d")
        put("n", n)
        put("c", c)
    }
}

/** A relay-level error (spec §10.2): sent by the backend, not the host, then the socket closes. */
data class RelayErrorFrame(val code: String, val message: String)

sealed interface OuterFrame {
    data class Hs1(val frame: Hs1Frame) : OuterFrame
    data class Hs2(val frame: Hs2Frame) : OuterFrame
    data class Reject(val frame: HsRejectFrame) : OuterFrame
    data class Data(val frame: DataFrame) : OuterFrame
    data class RelayError(val frame: RelayErrorFrame) : OuterFrame
}

object OuterFrames {
    internal val json = Json { ignoreUnknownKeys = true }

    fun encode(frame: JsonObject): String = frame.toString()

    /**
     * Parse one carrier text message. Shape only — the cryptographic checks
     * belong to whoever consumes the frame. Throws [CryptoError] on anything
     * that is not a well-formed outer frame.
     */
    fun parse(text: String): OuterFrame {
        if (text.length > Protocol.MAX_FRAME) throw CryptoError("too-large", "帧过大")
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            throw CryptoError("bad-frame", "帧无效", error)
        }
        return parseObject(obj)
    }

    fun parseObject(obj: JsonObject): OuterFrame {
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun bad(): Nothing = throw CryptoError("bad-frame", "帧无效")
        return when (str("t")) {
            "hs1" -> {
                val v = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: bad()
                OuterFrame.Hs1(Hs1Frame(v.toInt(), str("mode") ?: bad(), str("host") ?: bad(), str("e") ?: bad(), str("s") ?: bad()))
            }
            "hs2" -> OuterFrame.Hs2(Hs2Frame(str("e") ?: bad(), str("c") ?: bad()))
            "hsr" -> OuterFrame.Reject(HsRejectFrame(str("code") ?: "handshake-failed", str("message") ?: "握手被拒绝"))
            "d" -> {
                val n = (obj["n"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: bad()
                OuterFrame.Data(DataFrame(n, str("c") ?: bad()))
            }
            "relay-error" -> OuterFrame.RelayError(RelayErrorFrame(str("code") ?: "unknown", str("message") ?: "中继错误"))
            else -> bad()
        }
    }
}
