package com.skidsense.mobile.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same known-answer checks as the JVM host tests, but on a real device.
 *
 * This is the test that matters for the X25519 story: on API 24–32 Android's
 * own providers have no XDH, so a handshake here only passes if the bundled
 * BouncyCastle provider is really being used (see `CryptoProviders.android.kt`).
 * Running it on an old image is the only way to know.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceCryptoTest {
    private val root = kotlinx.serialization.json.Json.parseToJsonElement(REMOTE_VECTORS_JSON).jsonObject
    private val input = root.getValue("input").jsonObject
    private val output = root.getValue("output").jsonObject

    private fun str(obj: JsonObject, key: String) = obj.getValue(key).jsonPrimitive.content

    /**
     * Where each primitive comes from on this device. The point of printing it
     * is the X25519 line: on API 24-32 the platform has no XDH at all, so the
     * report has to name a provider that is not the platform's.
     */
    @Test
    fun apiLevelIsWhatThisTestIsFor() {
        val sdk = android.os.Build.VERSION.SDK_INT
        assertTrue("running on API $sdk", sdk >= 24)
        val report = buildString {
            append("SKIDSENSE_API=$sdk")
            append(" keyAgreementXDH=").append(providerOf { javax.crypto.KeyAgreement.getInstance("XDH").provider.name })
            append(" keyFactoryX25519=").append(providerOf { java.security.KeyFactory.getInstance("X25519").provider.name })
            append(" bcXDH=").append(providerOf { javax.crypto.KeyAgreement.getInstance("XDH", "BC").provider.name })
            append(" bcEd25519=").append(providerOf { java.security.KeyFactory.getInstance("Ed25519", "BC").provider.name })
            append(" aesGcm=").append(providerOf { javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").provider.name })
            append(" hmac=").append(providerOf { javax.crypto.Mac.getInstance("HmacSHA256").provider.name })
            append(" sha256=").append(providerOf { java.security.MessageDigest.getInstance("SHA-256").provider.name })
            append(" rsaOaep=").append(providerOf { javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding").provider.name })
            append(" usedX25519=").append(CryptoProviders.x25519.name)
        }
        println(report)
        // The bundled provider is what the app uses for X25519 on this API level.
        assertEquals("JDK (BC)", CryptoProviders.x25519.name)
    }

    private fun providerOf(block: () -> String): String = try {
        block()
    } catch (error: Throwable) {
        "UNAVAILABLE"
    }

    @Test
    fun handshakeReproducesTheVectors() {
        val hostId = str(input, "hostId")
        val hostStatic = Primitives.keyPairFromPrivate(hexToBytes(str(input, "hostStaticPriv")))
        val clientStatic = Primitives.keyPairFromPrivate(hexToBytes(str(input, "clientStaticPriv")))
        val clientEphemeral = Primitives.keyPairFromPrivate(hexToBytes(str(input, "clientEphemeralPriv")))
        val hostEphemeral = Primitives.keyPairFromPrivate(hexToBytes(str(input, "hostEphemeralPriv")))
        val psk = hexToBytes(str(input, "psk"))

        assertEquals(str(output, "hostStaticPub"), B64u.encode(hostStatic.pub))
        assertEquals(str(output, "clientStaticPub"), B64u.encode(clientStatic.pub))

        for ((mode, key) in listOf(HandshakeMode.ENROLL to "enroll", HandshakeMode.CONNECT to "connect")) {
            val expected = output.getValue(key).jsonObject
            val client = Initiator(
                mode, hostId, hostStatic.pub, clientStatic,
                if (mode == HandshakeMode.ENROLL) psk else null, clientEphemeral
            )
            assertEquals(expected.getValue("hs1").toString(), client.hs1.toJson().toString())
            val (hs2, hostKeys) = TestResponder(hostId, hostStatic, client.hs1, if (mode == HandshakeMode.ENROLL) psk else null)
                .complete(hostEphemeral)
            assertEquals(expected.getValue("hs2").toString(), hs2.toJson().toString())
            val keys = client.finish(hs2)
            assertEquals(str(expected, "c2s"), B64u.encode(keys.send))
            assertEquals(str(expected, "s2c"), B64u.encode(keys.recv))
            assertEquals(str(expected, "th"), B64u.encode(keys.th))

            val sealer = FrameSealer(keys.send)
            val frames = input.getValue("messages").jsonObject.getValue("c2s").jsonArray.map { it.jsonPrimitive.content }
            val produced = frames.map { sealer.seal(it).toJson().toString() }
            assertEquals(expected.getValue("framesC2s").jsonArray.map { it.toString() }, produced)
            val opener = FrameOpener(hostKeys.recv)
            assertEquals(
                frames,
                expected.getValue("framesC2s").jsonArray.map {
                    opener.open((OuterFrames.parseObject(it.jsonObject) as OuterFrame.Data).frame)
                }
            )
        }
    }

    @Test
    fun keyWrapBlobAndFingerprintMatch() {
        val hostId = str(input, "hostId")
        val clientStatic = Primitives.keyPairFromPrivate(hexToBytes(str(input, "clientStaticPriv")))
        val wrapInput = input.getValue("wrap").jsonObject
        val wrapOut = output.getValue("wrap").jsonObject
        val epoch = wrapInput.getValue("epoch").jsonPrimitive.long
        val context = HistoryCrypto.wrapContext(hostId, epoch)
        assertEquals(str(wrapOut, "context"), B64u.encode(context))
        val key = hexToBytes(str(wrapInput, "key"))
        val wrapped = HistoryCrypto.wrapKey(key, clientStatic.pub, context, Primitives.keyPairFromPrivate(hexToBytes(str(wrapInput, "ephemeralPriv"))))
        assertEquals(str(wrapOut, "wrapped"), B64u.encode(wrapped))
        assertTrue(HistoryCrypto.unwrapKey(wrapped, clientStatic, context).contentEquals(key))
        assertEquals("D511-0BBB-BA1D-667B", str(output, "fingerprint"))
    }

    @Test
    fun lowOrderPointsAreRefusedEvenWhenTheProviderAllowsThem() {
        val priv = Primitives.randomBytes(32)
        val failed = try {
            Primitives.dh(priv, ByteArray(32))
            false
        } catch (_: CryptoError) {
            true
        }
        assertTrue("an all-zero X25519 output must be refused", failed)
    }

    /** The login password envelope, produced with the platform's RSA and opened with the platform's JCA. */
    @Test
    fun passwordEnvelopeUsesThePlatformRsaAndKeystoreFreeAes() {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val pem = buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded))
            append("\n-----END PUBLIC KEY-----\n")
        }
        val envelope = com.skidsense.mobile.api.PasswordEnvelope.encrypt("密码-pw", pem, "kid-9")
        val parts = envelope.split(".")
        assertEquals(4, parts.size)
        val decoder = java.util.Base64.getDecoder()
        val rsa = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            keyPair.private,
            javax.crypto.spec.OAEPParameterSpec(
                "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
                javax.crypto.spec.PSource.PSpecified("password-v2".encodeToByteArray())
            )
        )
        val aesKey = rsa.doFinal(decoder.decode(parts[1]))
        val gcm = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, decoder.decode(parts[2])))
        gcm.updateAAD("password-v2:kid-9".encodeToByteArray())
        assertEquals("密码-pw", gcm.doFinal(decoder.decode(parts[3])).decodeToString())
    }

    @Test
    fun aesGcmAndHkdfWork() {
        val key = Primitives.randomBytes(32)
        val nonce = Primitives.randomBytes(12)
        val aad = utf8("aad")
        val sealed = Primitives.seal(key, nonce, aad, utf8("hello"))
        assertEquals("hello", Primitives.open(key, nonce, aad, sealed).decodeToString())
        assertEquals(32, Primitives.hkdf(Primitives.randomBytes(32), Primitives.randomBytes(32), "info").size)
        assertEquals(32, Primitives.hmacSha256(key, aad).size)
        assertEquals(32, Primitives.sha256(aad).size)
    }
}
