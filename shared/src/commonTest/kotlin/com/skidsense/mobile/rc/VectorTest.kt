package com.skidsense.mobile.rc

import dev.whyoleg.cryptography.algorithms.EdDSA
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Known-answer vectors (spec §15): from `input`, reproduce `output` byte for
 * byte, then run the outputs backwards — open the frames, unwrap the key,
 * open the blob, verify the grant.
 */
class VectorTest {
    private val root = Json.parseToJsonElement(REMOTE_VECTORS_JSON).jsonObject
    private val input = root.getValue("input").jsonObject
    private val output = root.getValue("output").jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun hex(key: String): ByteArray = hexToBytes(input.str(key))

    private val hostId = input.str("hostId")
    private val hostStatic = Primitives.keyPairFromPrivate(hex("hostStaticPriv"))
    private val clientStatic = Primitives.keyPairFromPrivate(hex("clientStaticPriv"))
    private val clientEphemeral = Primitives.keyPairFromPrivate(hex("clientEphemeralPriv"))
    private val hostEphemeral = Primitives.keyPairFromPrivate(hex("hostEphemeralPriv"))
    private val psk = hex("psk")

    @Test
    fun vectorFileIsThisProtocol() {
        assertEquals(Protocol.NAME, root.str("protocol"))
    }

    @Test
    fun publicKeysFromPrivateKeys() {
        assertEquals(output.str("hostStaticPub"), B64u.encode(hostStatic.pub))
        assertEquals(output.str("clientStaticPub"), B64u.encode(clientStatic.pub))
        assertEquals(output.str("clientEphemeralPub"), B64u.encode(clientEphemeral.pub))
        assertEquals(output.str("hostEphemeralPub"), B64u.encode(hostEphemeral.pub))
    }

    @Test
    fun handshakeHashes() {
        assertEquals(output.str("h0Enroll"), B64u.encode(Handshake.handshakeHash(HandshakeMode.ENROLL, hostId)))
        assertEquals(output.str("h0Connect"), B64u.encode(Handshake.handshakeHash(HandshakeMode.CONNECT, hostId)))
    }

    @Test
    fun enrollHandshakeAndFrames() = checkHandshake(HandshakeMode.ENROLL, "enroll")

    @Test
    fun connectHandshakeAndFrames() = checkHandshake(HandshakeMode.CONNECT, "connect")

    private fun checkHandshake(mode: HandshakeMode, key: String) {
        val expected = output.obj(key)
        val usePsk = if (mode == HandshakeMode.ENROLL) psk else null

        val client = Initiator(mode, hostId, hostStatic.pub, clientStatic, usePsk, clientEphemeral)
        assertEquals(expected.obj("hs1").toString(), client.hs1.toJson().toString(), "hs1 ($key)")
        assertEquals(OuterFrames.encode(expected.obj("hs1")), OuterFrames.encode(client.hs1.toJson()))

        val server = TestResponder(hostId, hostStatic, client.hs1, usePsk)
        assertContentEquals(clientStatic.pub, server.clientStatic, "host recovers the device static key")
        val (hs2, hostKeys) = server.complete(hostEphemeral)
        assertEquals(expected.obj("hs2").toString(), hs2.toJson().toString(), "hs2 ($key)")

        val clientKeys = client.finish(hs2)
        assertEquals(expected.str("c2s"), B64u.encode(clientKeys.send), "c2s ($key)")
        assertEquals(expected.str("s2c"), B64u.encode(clientKeys.recv), "s2c ($key)")
        assertEquals(expected.str("th"), B64u.encode(clientKeys.th), "th ($key)")
        assertContentEquals(clientKeys.send, hostKeys.recv)
        assertContentEquals(clientKeys.recv, hostKeys.send)

        val messages = input.obj("messages")
        val c2sTexts = messages.getValue("c2s").jsonArray.map { it.jsonPrimitive.content }
        val s2cTexts = messages.getValue("s2c").jsonArray.map { it.jsonPrimitive.content }

        val c2sSealer = FrameSealer(clientKeys.send)
        val s2cSealer = FrameSealer(hostKeys.send)
        val c2sFrames = c2sTexts.map { c2sSealer.seal(it).toJson().toString() }
        val s2cFrames = s2cTexts.map { s2cSealer.seal(it).toJson().toString() }
        assertEquals((expected.getValue("framesC2s") as JsonArray).map { it.toString() }, c2sFrames, "framesC2s ($key)")
        assertEquals((expected.getValue("framesS2c") as JsonArray).map { it.toString() }, s2cFrames, "framesS2c ($key)")

        // Backwards: open the vector's frames and get the original text.
        val hostOpener = FrameOpener(hostKeys.recv)
        val opened = expected.getValue("framesC2s").jsonArray.map {
            hostOpener.open((OuterFrames.parseObject(it.jsonObject) as OuterFrame.Data).frame)
        }
        assertEquals(c2sTexts, opened)
        val clientOpener = FrameOpener(clientKeys.recv)
        val openedS2c = expected.getValue("framesS2c").jsonArray.map {
            clientOpener.open((OuterFrames.parseObject(it.jsonObject) as OuterFrame.Data).frame)
        }
        assertEquals(s2cTexts, openedS2c)
    }

    @Test
    fun keyWrap() {
        val wrapInput = input.obj("wrap")
        val wrapOut = output.obj("wrap")
        val epoch = wrapInput.getValue("epoch").jsonPrimitive.long
        val context = HistoryCrypto.wrapContext(hostId, epoch)
        assertEquals(wrapOut.str("recipientPub"), B64u.encode(clientStatic.pub))
        assertEquals(wrapOut.str("context"), B64u.encode(context))

        val key = hexToBytes(wrapInput.str("key"))
        val ephemeral = Primitives.keyPairFromPrivate(hexToBytes(wrapInput.str("ephemeralPriv")))
        val wrapped = HistoryCrypto.wrapKey(key, clientStatic.pub, context, ephemeral)
        assertEquals(wrapOut.str("wrapped"), B64u.encode(wrapped))

        // Backwards: the device unwraps the vector's bytes with its static key.
        val unwrapped = HistoryCrypto.unwrapKey(B64u.decode(wrapOut.str("wrapped")), clientStatic, context)
        assertContentEquals(key, unwrapped)
    }

    @Test
    fun historyBlob() {
        val blobInput = input.obj("blob")
        val blobOut = output.obj("blob")
        val epoch = blobInput.getValue("epoch").jsonPrimitive.long
        val aad = HistoryCrypto.historyAad(hostId, blobInput.str("sessionKey"), epoch)
        assertEquals(blobOut.str("aad"), B64u.encode(aad))

        val key = hexToBytes(blobInput.str("key"))
        val plaintext = blobInput.str("plaintext")
        val blob = HistoryCrypto.sealBlob(key, aad, utf8(plaintext), hexToBytes(blobInput.str("nonce")))
        assertEquals(blobOut.str("blob"), B64u.encode(blob))

        val opened = HistoryCrypto.openBlob(key, aad, B64u.decode(blobOut.str("blob")))
        assertEquals(plaintext, opened.decodeToString())
    }

    @Test
    fun grantToken() {
        // The phone never verifies grants (the host does); this only proves the
        // Ed25519 path and the claims serialisation agree with the reference.
        val grantInput = input.obj("grant")
        val grantOut = output.obj("grant")
        val eddsa = CryptoProviders.x25519.get(EdDSA)
        val privateKey = eddsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, hexToBytes(grantInput.str("seed")))
        val publicKey = privateKey.getPublicKeyBlocking().encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW)
        assertEquals(grantOut.str("publicKey"), B64u.encode(publicKey))

        val claims = grantOut.obj("claims")
        assertEquals(B64u.encode(clientStatic.pub), claims.str("dpk"))
        val body = B64u.encode(utf8(claims.toString()))
        val signature = privateKey.signatureGenerator().generateSignatureBlocking(utf8(body))
        if (CryptoProviders.deterministicEd25519) {
            // Byte-for-byte equality is only meaningful where the signature is
            // deterministic; Apple's CryptoKit signs Ed25519 with a random nonce.
            assertEquals(grantOut.str("token"), "$body.${B64u.encode(signature)}")
        }

        val (tokenBody, tokenSignature) = grantOut.str("token").split('.')
        val verifier = eddsa.publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, publicKey).signatureVerifier()
        // The claims segment is identical everywhere, and the vector's own
        // signature verifies against the vector's public key.
        assertEquals(grantOut.str("token").substringBefore('.'), body)
        assertTrue(verifier.tryVerifySignatureBlocking(utf8(tokenBody), B64u.decode(tokenSignature, 64)))
        assertTrue(verifier.tryVerifySignatureBlocking(utf8(body), signature), "our own signature verifies too")
    }

    @Test
    fun hostFingerprint() {
        assertEquals(output.str("fingerprint"), fingerprint(hostStatic.pub))
        assertEquals("D511-0BBB-BA1D-667B", fingerprint(hostStatic.pub))
    }
}
