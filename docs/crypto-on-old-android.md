# X25519 on Android API 24–32

`minSdk` is 24, and the protocol's handshake is four X25519 operations
(`docs/remote-control.md` §4.4 in the desktop repo). This note records why
that works on every API level this app supports, and how it was checked.

## The problem

Android's platform JCA gained X25519 with **API 33** (`KeyAgreement.getInstance("XDH")`,
`KeyFactory.getInstance("X25519")`, `KeyPairGenerator.getInstance("X25519")`).
Below that, `Conscrypt` — selected by both `JavaCryptographyProvider` lookups on
modern Android — has no XDH algorithm at all, and neither does the stripped
platform "BC" provider. An implementation that asks the *platform* for X25519
therefore throws `NoSuchAlgorithmException` on API 24–32, which is every Android
version this app's `minSdk` covers except the newest ones.

## What Android actually has, measured

Probed on an API 30 emulator (`android-30;aosp_atd;arm64-v8a`) by the
`OnDeviceCryptoTest` report:

| Lookup | Provider on API 30 |
|---|---|
| `KeyAgreement` `XDH` | **UNAVAILABLE** |
| `KeyFactory` `X25519` | **UNAVAILABLE** |
| `Cipher` `AES/GCM/NoPadding` | `AndroidOpenSSL` |
| `Mac` `HmacSHA256` | `AndroidOpenSSL` |
| `MessageDigest` `SHA-256` | `AndroidOpenSSL` |
| `Cipher` `RSA/ECB/OAEPPadding` | `AndroidOpenSSL` |
| `KeyAgreement` `XDH` with an explicit BouncyCastle `Provider` | works |

So the split is: everything except X25519 is fine on the platform (API 19+ for
AES-GCM), and X25519 is the one primitive with a hole.

## The fix, and why it is this one

`cryptography-kotlin`'s JDK provider implements XDH by asking JCA. Its 0.6.0 API
lets a caller hand it a `java.security.Provider` instance:

```kotlin
internal actual object CryptoProviders {
    private val bouncyCastle by lazy { BouncyCastleProvider() }
    actual val x25519: CryptographyProvider by lazy { CryptographyProvider.JDK(bouncyCastle) }
}
```

Three properties of that call make it the right one:

1. **It is not a global registration.** `CryptographyProvider.JDK(provider)` is
   `JdkCryptographyProvider(provider)`, whose every `getInstance(alg, provider)`
   overload prefers the given instance. `Security.addProvider` is never called,
   so nothing else in the process — or the platform — changes behaviour.
2. **BouncyCastle is `bcprov-jdk18on`, bundled in the APK.** It is pure Java, so
   the same code runs on API 24 and API 35; its classes live under
   `org.bouncycastle`, distinct from the platform's repackaged
   `com.android.org.bouncycastle`, so there is no clash. Android's "BC
   deprecation" checks (`targetSdk` 28+) apply to the *system* BC instance, not
   to an app's own, so they do not apply here.
3. **It also fixes public-key derivation.** cryptography-kotlin's JDK provider
   derives a public key from a raw private key through a BouncyCastle bridge
   that is only active when BC classes are on the classpath — which the app's
   own key loading needs (`Primitives.keyPairFromPrivate`).

`Primitives.dh` additionally rejects an all-zero shared secret itself. BC does
too, but the protocol's rule (§2) should not depend on which provider is
underneath: a low-order peer key zeroes the DH term no matter what our private
key is, and a silently-zero term would be a hole in the handshake.

`RSA-OAEP` with a **label** (the login password envelope, §12) also goes to the
bundled BC: `associatedData` support in older Conscrypt builds is not something
to bet a login on. Everything else stays on the platform provider, which is
native code and faster for the megabyte-sized frames the protocol allows.

`HKDF` needs nothing from the platform: cryptography-kotlin implements RFC 5869
itself on top of HMAC-SHA256, which Conscrypt has everywhere.

## How this is verified

- `./gradlew :shared:testAndroidHostTest` — 74 tests on the JVM host, including
  every known-answer vector, byte for byte.
- `./gradlew :shared:connectedAndroidDeviceTest` — 75 tests on an **API 30
  emulator** (`skidsense_api30`, `system-images;android-30;aosp_atd;arm64-v8a`),
  where the report line above proves the platform has no XDH and the app's
  provider reports `JDK (BC)`. The handshake vectors pass there, which is the
  evidence that BC is really doing the X25519 on an API level without it.
- The app installs and starts on API 30 and API 37 with no crash in the log.

API 24–29 were not run: no system image was available locally for those levels,
and the mechanism is identical (the provider lookup is a runtime `getInstance`
with an explicit instance, with no API-level branching anywhere in the path).
That is an inference from the mechanism, not a measurement, and is the one gap
in this note.
