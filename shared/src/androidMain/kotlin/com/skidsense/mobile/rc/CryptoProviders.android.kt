package com.skidsense.mobile.rc

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Providers on Android (and on the JVM host tests, which run this same code).
 *
 * ## X25519 on API 24–32
 *
 * cryptography-kotlin's JDK provider implements XDH by asking JCA for
 * `KeyAgreement.getInstance("XDH")`, `KeyFactory.getInstance("X25519")` and
 * friends. Android's platform providers (Conscrypt "AndroidOpenSSL", and the
 * stripped system "BC") only gained XDH in API 33, so on the API 24–32 phones
 * this app supports those lookups throw `NoSuchAlgorithmException`.
 *
 * So X25519 runs on an **app-bundled** BouncyCastle (`org.bouncycastle:
 * bcprov-jdk18on`), handed to cryptography-kotlin as an explicit
 * `java.security.Provider` instance: `CryptographyProvider.JDK(BouncyCastleProvider())`.
 * Every JCA lookup then goes to that instance — `getInstance(alg, provider)`
 * works with a provider that was never registered with `Security`, so nothing
 * global is touched. Its classes live under `org.bouncycastle`, distinct from
 * the platform's repackaged `com.android.org.bouncycastle`, so there is no
 * clash; and Android's "BC deprecation" checks (API 28+) apply only to the
 * *system* BC instance, not to an app's own. BC's X25519 is pure Java
 * (RFC 7748 ladder), the same on every API level, and it refuses an all-zero
 * shared secret itself — `Primitives.dh` checks again regardless.
 *
 * Deriving the public key from a raw private key uses cryptography-kotlin's
 * BouncyCastle bridge, which only activates when BC classes are on the
 * classpath — another reason BC is bundled rather than optional.
 *
 * ## Everything else
 *
 * AES/GCM/NoPadding with AAD (API 19+), HmacSHA256 and SHA-256 are in
 * Conscrypt on every supported level, so they stay on the platform provider —
 * native code, and faster than pure-Java BC for megabyte frames. HKDF is
 * cryptography-kotlin's own RFC 5869 construction over that HMAC, so it needs
 * nothing from the platform. RSA-OAEP with a *label* goes to BC as well: the
 * label support in older Conscrypt builds is not something to bet a login on.
 */
internal actual object CryptoProviders {
    private val bouncyCastle by lazy { BouncyCastleProvider() }

    actual val x25519: CryptographyProvider by lazy { CryptographyProvider.JDK(bouncyCastle) }

    actual val general: CryptographyProvider get() = CryptographyProvider.JDK

    actual val rsa: CryptographyProvider get() = x25519

    actual val deterministicEd25519: Boolean get() = true

    actual val supportsRsaOaepLabel: Boolean get() = true
}
