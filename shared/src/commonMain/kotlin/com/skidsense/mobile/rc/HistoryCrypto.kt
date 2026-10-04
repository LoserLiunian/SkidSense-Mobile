package com.skidsense.mobile.rc

/**
 * History hosting (spec §11): the host's history key `K` arrives wrapped to
 * this device's static X25519 key (ECIES), and each session is a sealed blob
 * bound to its host, session key and key epoch.
 *
 * The phone only ever unwraps and opens; [wrapKey] and [sealBlob] exist so the
 * known-answer vectors can be reproduced and the round trip tested.
 */
object HistoryCrypto {
    private const val LABEL_WRAP = "${Protocol.NAME} wrap"
    private val ZERO_NONCE = ByteArray(12)

    /** AAD for a history-key wrap: which host, which epoch. */
    fun wrapContext(hostId: String, epoch: Long): ByteArray =
        concat(utf8("${Protocol.NAME} history-key"), ZERO_BYTE, utf8(hostId), ZERO_BYTE, u32be(epoch))

    /** AAD for one stored session: the backend cannot swap one session's blob in for another's. */
    fun historyAad(hostId: String, sessionKey: String, epoch: Long): ByteArray =
        concat(utf8("${Protocol.NAME} history"), ZERO_BYTE, utf8(hostId), ZERO_BYTE, utf8(sessionKey), ZERO_BYTE, u32be(epoch))

    /** `e.pub (32) ‖ AEAD(kek, 0-nonce, context, key) (48)` — 80 bytes. */
    fun wrapKey(key: ByteArray, recipient: ByteArray, context: ByteArray, ephemeral: KeyPair? = null): ByteArray {
        if (key.size != 32) throw CryptoError("bad-key", "被包裹的密钥应为 32 字节")
        val e = ephemeral ?: Primitives.generateKeyPair()
        val kek = Primitives.hkdf(Primitives.dh(e.priv, recipient), concat(e.pub, recipient), LABEL_WRAP)
        return concat(e.pub, Primitives.seal(kek, ZERO_NONCE, context, key))
    }

    fun unwrapKey(wrapped: ByteArray, recipient: KeyPair, context: ByteArray): ByteArray {
        if (wrapped.size != 80) throw CryptoError("bad-key", "包裹的密钥长度无效")
        val epub = wrapped.copyOfRange(0, 32)
        val kek = Primitives.hkdf(Primitives.dh(recipient.priv, epub), concat(epub, recipient.pub), LABEL_WRAP)
        return Primitives.open(kek, ZERO_NONCE, context, wrapped.copyOfRange(32, 80))
    }

    /** `nonce (12, random) ‖ ciphertext ‖ tag (16)`. */
    fun sealBlob(key: ByteArray, aad: ByteArray, plaintext: ByteArray, nonce: ByteArray? = null): ByteArray {
        val iv = nonce ?: Primitives.randomBytes(12)
        if (iv.size != 12) throw CryptoError("bad-key", "nonce 应为 12 字节")
        return concat(iv, Primitives.seal(key, iv, aad, plaintext))
    }

    fun openBlob(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < 28) throw CryptoError("bad-frame", "密文过短")
        return Primitives.open(key, blob.copyOfRange(0, 12), aad, blob.copyOfRange(12, blob.size))
    }
}

/**
 * A human-checkable fingerprint of a host key (spec §3): the first 8 bytes of
 * SHA-256, upper-case hex, in groups of four — `D511-0BBB-BA1D-667B`.
 */
fun fingerprint(publicKey: ByteArray): String =
    Primitives.sha256(publicKey).copyOfRange(0, 8).toHexString(upper = true).chunked(4).joinToString("-")
