package com.skidsense.mobile.rc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a pairing QR code carries (spec §3). Single-letter keys on the wire
 * because QR density is the constraint.
 */
data class PairingPayload(
    /** Host id. */
    val hostId: String,
    /** Host static public key — pinned from here on, never learned from the network. */
    val hostKey: ByteArray,
    /** The 32-byte pairing code: the enroll handshake's PSK. */
    val code: ByteArray,
    /** LAN addresses, tried in order. */
    val lanAddrs: List<String>,
    val lanPort: Int,
    /** The backend both ends must be signed in to. */
    val server: String,
    /** Machine name, for display only. */
    val machine: String
) {
    val fingerprint: String get() = fingerprint(hostKey)

    override fun equals(other: Any?): Boolean =
        other is PairingPayload && hostId == other.hostId && hostKey.contentEquals(other.hostKey) &&
            code.contentEquals(other.code) && lanAddrs == other.lanAddrs && lanPort == other.lanPort &&
            server == other.server && machine == other.machine

    override fun hashCode(): Int = hostId.hashCode() * 31 + hostKey.contentHashCode()
}

object Pairing {
    /**
     * Accepts the whole `skidsense://pair/1?d=…` link or the bare base64url
     * payload, so a code pasted by hand works as well as a scanned one.
     */
    fun decode(text: String): PairingPayload {
        val trimmed = text.trim()
        val data = when {
            trimmed.startsWith(Protocol.PAIRING_URL_PREFIX) -> trimmed.removePrefix(Protocol.PAIRING_URL_PREFIX)
            trimmed.startsWith("skidsense://") -> throw IllegalArgumentException("配对码版本不受支持，请更新手机端")
            else -> trimmed
        }
        val raw: JsonObject = try {
            OuterFrames.json.parseToJsonElement(strictUtf8(B64u.decode(data))).jsonObject
        } catch (_: Exception) {
            throw IllegalArgumentException("配对码无效")
        }
        if ((raw["v"] as? JsonPrimitive)?.intOrNull != 1) throw IllegalArgumentException("配对码版本不受支持")
        fun str(key: String): String =
            (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw IllegalArgumentException("配对码无效")
        val hostKey = try { B64u.decode(str("k"), 32) } catch (_: CryptoError) { throw IllegalArgumentException("配对码里的主机公钥无效") }
        val code = try { B64u.decode(str("c"), 32) } catch (_: CryptoError) { throw IllegalArgumentException("配对码无效") }
        val hosts = (raw["h"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull } ?: emptyList()
        val port = (raw["p"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: 0
        val hostId = str("n")
        if (hostId.isEmpty()) throw IllegalArgumentException("配对码无效")
        return PairingPayload(hostId, hostKey, code, hosts, port, str("s"), str("m"))
    }
}

/**
 * Backend addresses are compared after normalising: scheme and host lower-cased,
 * default ports and trailing slashes dropped. `https://AI.example.com/` and
 * `https://ai.example.com` are the same backend; `http://` and `https://` are not.
 */
fun normalizeBackendUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed.lowercase()
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    val rest = trimmed.substring(schemeEnd + 3)
    val slash = rest.indexOf('/')
    var authority = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
    val path = if (slash < 0) "" else rest.substring(slash)
    if (scheme == "https" && authority.endsWith(":443")) authority = authority.removeSuffix(":443")
    if (scheme == "http" && authority.endsWith(":80")) authority = authority.removeSuffix(":80")
    return "$scheme://$authority$path"
}
