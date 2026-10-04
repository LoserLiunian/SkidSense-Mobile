package com.skidsense.mobile.rc

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Which cryptography-kotlin provider serves which primitive, per platform.
 *
 * Split rather than one provider because the platforms disagree on coverage:
 * Android's own JCA (Conscrypt) has AES-GCM, HMAC and SHA-256 on every API
 * level this app supports but no X25519 ("XDH") below API 33, so on Android
 * X25519 comes from an app-bundled BouncyCastle — see the Android `actual`.
 */
internal expect object CryptoProviders {
    /** X25519 key agreement and key generation. */
    val x25519: CryptographyProvider

    /** SHA-256, HMAC-SHA256, HKDF-SHA256, AES-256-GCM. */
    val general: CryptographyProvider

    /** RSA-OAEP with a label (the login password envelope, spec §12). */
    val rsa: CryptographyProvider

    /**
     * Whether Ed25519 signatures are deterministic (RFC 8032's nonce is derived
     * from the key and the message, as BouncyCastle and the JDK do).
     *
     * Apple's CryptoKit signs with a *random* nonce — also RFC 8032, also valid,
     * but a different signature every time for the same input, so a
     * byte-for-byte known-answer check is impossible there. The phone never
     * signs a grant (the backend does) and never verifies one (the host does);
     * the vector test uses this flag to skip only the byte comparison it cannot
     * meet, still checking that the signature verifies.
     */
    val deterministicEd25519: Boolean

    /**
     * Whether RSA-OAEP with a label is available. Apple's `SecRsaOaep` rejects
     * associated data outright, so the `password-v2` envelope cannot be built
     * on iOS yet; the login screen says so instead of failing obscurely.
     */
    val supportsRsaOaepLabel: Boolean
}

/** A raw 32-byte X25519 key pair — raw so it serialises and compares. */
class KeyPair(val priv: ByteArray, val pub: ByteArray) {
    init {
        require(priv.size == 32 && pub.size == 32) { "X25519 keys are 32 bytes" }
    }
}

/**
 * The primitives of spec §2, on cryptography-kotlin. Everything is blocking:
 * each call is microseconds to a millisecond, and callers that care run them
 * off the main thread.
 */
object Primitives {
    private val sha256Hasher by lazy { CryptoProviders.general.get(SHA256).hasher() }
    private val hkdf by lazy { CryptoProviders.general.get(HKDF) }
    private val hmac by lazy { CryptoProviders.general.get(HMAC) }
    private val gcm by lazy { CryptoProviders.general.get(AES.GCM) }
    private val xdh by lazy { CryptoProviders.x25519.get(XDH) }

    fun randomBytes(size: Int): ByteArray = CryptographyRandom.Default.nextBytes(size)

    fun sha256(vararg parts: ByteArray): ByteArray = sha256Hasher.hashBlocking(concat(*parts))

    /** RFC 5869 HKDF-SHA256, 32-byte output, UTF-8 info. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray =
        hkdf.secretDerivation(SHA256, 32.bytes, salt, utf8(info)).deriveSecretToByteArrayBlocking(ikm)

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
        hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator().generateSignatureBlocking(message)

    /** AES-256-GCM, 12-byte nonce, the 16-byte tag appended to the ciphertext. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        if (key.size != 32) throw CryptoError("bad-key", "AES 密钥应为 32 字节")
        if (nonce.size != 12) throw CryptoError("bad-key", "nonce 应为 12 字节")
        return gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher().encryptWithIvBlocking(nonce, plaintext, aad)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < 16) throw CryptoError("bad-frame", "密文过短")
        if (key.size != 32) throw CryptoError("bad-key", "AES 密钥应为 32 字节")
        return try {
            gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
                .cipher().decryptWithIvBlocking(nonce, sealed, aad)
        } catch (error: Exception) {
            throw CryptoError("bad-frame", "解密失败", error)
        }
    }

    fun generateKeyPair(): KeyPair = keyPairFromPrivate(randomBytes(32))

    /** Any 32 bytes are a valid X25519 private key: the scalar is clamped when used. */
    fun keyPairFromPrivate(priv: ByteArray): KeyPair {
        if (priv.size != 32) throw CryptoError("bad-key", "X25519 私钥应为 32 字节")
        val key = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, priv)
        val pub = key.getPublicKeyBlocking().encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW)
        return KeyPair(priv.copyOf(), pub)
    }

    /**
     * X25519, refusing an all-zero result (spec §2).
     *
     * A low-order peer key forces the shared secret to zero whatever our
     * private key is, which would make that DH term contribute nothing.
     * BouncyCastle and CryptoKit already refuse; the explicit check stays
     * because the guarantee should not depend on which provider is underneath.
     */
    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        if (priv.size != 32) throw CryptoError("bad-key", "X25519 私钥应为 32 字节")
        if (pub.size != 32) throw CryptoError("bad-key", "X25519 公钥应为 32 字节")
        val out = try {
            val privateKey = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, priv)
            val publicKey = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, pub)
            privateKey.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(publicKey)
        } catch (error: Exception) {
            throw CryptoError("bad-key", "密钥交换失败", error)
        }
        if (out.size != 32 || out.isAllZero()) throw CryptoError("bad-key", "密钥交换失败")
        return out
    }
}
