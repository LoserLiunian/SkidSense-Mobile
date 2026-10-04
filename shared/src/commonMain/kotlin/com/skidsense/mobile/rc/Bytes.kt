package com.skidsense.mobile.rc

import kotlin.io.encoding.Base64

/**
 * Byte-level encoding for `skidsense-rc/1` (spec §2).
 *
 * Every binary field on the wire is base64url **without padding**, and decoding
 * is strict: another alphabet, padding, or a non-canonical encoding (one whose
 * unused trailing bits are not zero, so that two strings would name the same
 * bytes) is refused. The check is the reference implementation's own: decode,
 * re-encode, and require the result to equal the input.
 */
object B64u {
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val alphabet = Regex("^[A-Za-z0-9_-]*$")

    fun encode(data: ByteArray): String = codec.encode(data)

    /** Strict decode; [length] when given is the exact decoded length required. */
    fun decode(text: String?, length: Int? = null): ByteArray {
        if (text == null || !alphabet.matches(text) || text.length % 4 == 1) {
            throw CryptoError("bad-encoding", "编码无效")
        }
        val out = try {
            codec.decode(text)
        } catch (_: IllegalArgumentException) {
            throw CryptoError("bad-encoding", "编码无效")
        }
        if (codec.encode(out) != text) throw CryptoError("bad-encoding", "编码无效")
        if (length != null && out.size != length) throw CryptoError("bad-encoding", "长度应为 $length 字节")
        return out
    }
}

/** Standard base64 with padding — only for new-api's password envelope (§12), which Go decodes that way. */
internal object B64Std {
    fun encode(data: ByteArray): String = Base64.Default.encode(data)
    fun decode(text: String): ByteArray = Base64.Default.decode(text)
}

internal fun utf8(text: String): ByteArray = text.encodeToByteArray()

/**
 * Strict UTF-8 decode (spec §5): a lone surrogate or an overlong form is not
 * text the far end sent, so it is refused rather than replaced with U+FFFD.
 */
internal fun strictUtf8(bytes: ByteArray): String = try {
    bytes.decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    throw CryptoError("bad-frame", "帧内容不是合法的 UTF-8")
}

internal fun u64be(value: Long): ByteArray {
    require(value >= 0) { "u64be takes a non-negative value" }
    return ByteArray(8) { index -> (value ushr (56 - 8 * index)).toByte() }
}

internal fun u32be(value: Long): ByteArray {
    require(value in 0..0xFFFF_FFFFL) { "u32be out of range" }
    return ByteArray(4) { index -> (value ushr (24 - 8 * index)).toByte() }
}

internal fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

internal val ZERO_BYTE: ByteArray = byteArrayOf(0)

/** Constant-time comparison: the time taken does not depend on where the first difference is. */
fun sameBytes(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (index in a.indices) diff = diff or (a[index].toInt() xor b[index].toInt())
    return diff == 0
}

internal fun ByteArray.isAllZero(): Boolean {
    var acc = 0
    for (byte in this) acc = acc or byte.toInt()
    return acc == 0
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd hex length" }
    return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHexString(upper: Boolean = false): String {
    val digits = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}
