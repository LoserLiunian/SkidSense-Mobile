package com.skidsense.mobile.rc

import dev.whyoleg.cryptography.CryptographyProvider

/**
 * Providers on iOS: cryptography-kotlin's "optimal" set, i.e. CryptoKit
 * (X25519, HKDF, AES-GCM, HMAC, SHA-256) composed with Apple's CommonCrypto /
 * Security framework.
 *
 * TODO(iOS): RSA-OAEP *with a label* (the login password envelope's
 * `password-v2` label) is not supported by the Apple provider — its
 * `SecRsaOaep` rejects associated data. Before shipping iOS, either encode
 * EME-OAEP (RFC 8017 §7.1.1, SHA-256 + MGF1-SHA256 + label) in common code
 * over `RSA.RAW`, or wrap a small Swift helper. Until then `PasswordEnvelope`
 * throws on iOS and login only works against servers without password
 * encryption.
 */
internal actual object CryptoProviders {
    actual val x25519: CryptographyProvider get() = CryptographyProvider.Default
    actual val general: CryptographyProvider get() = CryptographyProvider.Default
    actual val rsa: CryptographyProvider get() = CryptographyProvider.Default

    /** CryptoKit signs Ed25519 with a random nonce; see the expect declaration. */
    actual val deterministicEd25519: Boolean get() = false

    actual val supportsRsaOaepLabel: Boolean get() = false
}
