package com.skidsense.mobile.api

import com.skidsense.mobile.rc.B64Std
import com.skidsense.mobile.rc.CryptoProviders
import com.skidsense.mobile.rc.Primitives
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * new-api's optional at-rest login encryption (spec §12; the desktop's
 * `src/main/password-crypto.ts`, the client half of `common.DecryptPassword`).
 *
 * A fresh 32-byte AES key is wrapped with the server's RSA public key under
 * RSA-OAEP(SHA-256, MGF1-SHA-256, label `password-v2`), and the password is
 * sealed with AES-256-GCM whose AAD is `password-v2:<kid>`. Envelope:
 * `v2.<b64 wrappedKey>.<b64 nonce>.<b64 ciphertext‖tag>` in **standard**
 * base64 with padding — Go decodes it that way.
 */
object PasswordEnvelope {
    private const val LABEL = "password-v2"

    /** Thrown when the platform cannot build a `password-v2` envelope at all. */
    class Unsupported(message: String) : Exception(message)

    fun encrypt(password: String, publicKeyPem: String, keyId: String, aesKey: ByteArray? = null, nonce: ByteArray? = null): String {
        if (!CryptoProviders.supportsRsaOaepLabel) {
            // A plain sentence, because "登录失败" gives the user nothing to act
            // on and this is a missing feature, not a wrong password.
            throw Unsupported("这个服务器要求加密传输密码，而 iOS 端还不支持（缺少 RSA-OAEP label 支持）。请在安卓端登录，或让服务器关闭登录加密。")
        }
        val key = aesKey ?: Primitives.randomBytes(32)
        val iv = nonce ?: Primitives.randomBytes(12)
        val format = if (publicKeyPem.contains("BEGIN RSA PUBLIC KEY")) RSA.PublicKey.Format.PEM.PKCS1 else RSA.PublicKey.Format.PEM
        val publicKey = CryptoProviders.rsa.get(RSA.OAEP).publicKeyDecoder(SHA256)
            .decodeFromByteArrayBlocking(format, publicKeyPem.trim().encodeToByteArray())
        val wrapped = publicKey.encryptor().encryptBlocking(key, LABEL.encodeToByteArray())
        val sealed = Primitives.seal(key, iv, "$LABEL:$keyId".encodeToByteArray(), password.encodeToByteArray())
        return listOf("v2", B64Std.encode(wrapped), B64Std.encode(iv), B64Std.encode(sealed)).joinToString(".")
    }
}
