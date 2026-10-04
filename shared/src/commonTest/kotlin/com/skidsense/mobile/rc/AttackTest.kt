package com.skidsense.mobile.rc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Every way the channel is meant to fail closed — ported from the attack
 * sections of the desktop's `scripts/verify-remote.ts`.
 */
class AttackTest {
    private val hostStatic = Primitives.generateKeyPair()
    private val clientStatic = Primitives.generateKeyPair()
    private val hostId = B64u.encode(Primitives.randomBytes(16))

    private fun codeOf(block: () -> Unit): String = try {
        block()
        "(no error)"
    } catch (error: CryptoError) {
        error.code
    } catch (error: Exception) {
        error.message ?: error.toString()
    }

    private fun connected(): Pair<SessionKeys, SessionKeys> {
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        val (hs2, hostKeys) = TestResponder(hostId, hostStatic, client.hs1).complete()
        return client.finish(hs2) to hostKeys
    }

    // --- handshake ------------------------------------------------------------

    @Test
    fun handshakeAgreesAndHidesTheDevice() {
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        val server = TestResponder(hostId, hostStatic, client.hs1)
        assertContentEquals(clientStatic.pub, server.clientStatic)
        val (hs2, hostKeys) = server.complete()
        val clientKeys = client.finish(hs2)
        assertContentEquals(clientKeys.send, hostKeys.recv)
        assertContentEquals(clientKeys.recv, hostKeys.send)
        assertContentEquals(clientKeys.th, hostKeys.th)
        assertFalse(sameBytes(clientKeys.send, clientKeys.recv), "the two directions use different keys")
        assertFalse(client.hs1.toJson().toString().contains(B64u.encode(clientStatic.pub)), "hs1 does not show the device key")

        val again = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        val againKeys = again.finish(TestResponder(hostId, hostStatic, again.hs1).complete().first)
        assertFalse(sameBytes(againKeys.send, clientKeys.send), "a second handshake derives fresh keys")
    }

    @Test
    fun relayWithItsOwnStaticCannotOpenHs1() {
        val relayStatic = Primitives.generateKeyPair()
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        assertEquals("handshake-failed", codeOf { TestResponder(hostId, relayStatic, client.hs1) })
    }

    @Test
    fun wrongPinnedHostKeyFails() {
        // The phone pinned a key the host does not hold: the host cannot open hs1.
        val pinnedWrong = Primitives.generateKeyPair()
        val client = Initiator(HandshakeMode.CONNECT, hostId, pinnedWrong.pub, clientStatic)
        assertEquals("handshake-failed", codeOf { TestResponder(hostId, hostStatic, client.hs1) })
    }

    @Test
    fun swappedHs2EphemeralIsCaught() {
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        val (genuine, _) = TestResponder(hostId, hostStatic, client.hs1).complete()
        val swapped = genuine.copy(e = B64u.encode(Primitives.generateKeyPair().pub))
        assertEquals("handshake-failed", codeOf { client.finish(swapped) })
    }

    @Test
    fun forgedConfirmIsRejected() {
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        val (genuine, _) = TestResponder(hostId, hostStatic, client.hs1).complete()
        assertEquals("handshake-failed", codeOf { client.finish(genuine.copy(c = B64u.encode(ByteArray(32) { 7 }))) })
    }

    @Test
    fun handshakeForAnotherHostIsRejected() {
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        assertEquals("wrong-host", codeOf { TestResponder(B64u.encode(Primitives.randomBytes(16)), hostStatic, client.hs1) })
    }

    @Test
    fun malformedHs1IsRejected() {
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        assertEquals("unsupported-version", codeOf { TestResponder(hostId, hostStatic, client.hs1.copy(v = 2)) })
        assertEquals("handshake-failed", codeOf { TestResponder(hostId, hostStatic, client.hs1.copy(mode = "admin")) })
        assertEquals("handshake-failed", codeOf {
            TestResponder(hostId, hostStatic, client.hs1.copy(s = B64u.encode(ByteArray(48) { 1 })))
        })
        assertEquals("bad-encoding", codeOf { TestResponder(hostId, hostStatic, client.hs1.copy(e = client.hs1.e + "=")) })
    }

    @Test
    fun lowOrderPointsAreRejected() {
        // All-zero, and a point of order 8 (from the curve25519 small-subgroup list).
        val lowOrder = listOf(
            ByteArray(32),
            hexToBytes("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
            hexToBytes("0100000000000000000000000000000000000000000000000000000000000000")
        )
        for (point in lowOrder) {
            assertEquals("bad-key", codeOf { Primitives.dh(clientStatic.priv, point) }, point.toHexString())
        }
        val client = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        assertEquals("bad-key", codeOf { TestResponder(hostId, hostStatic, client.hs1.copy(e = B64u.encode(ByteArray(32)))) })
        // A device must not start a handshake against a low-order "host key" either.
        assertEquals("bad-key", codeOf { Initiator(HandshakeMode.CONNECT, hostId, ByteArray(32), clientStatic) })
        // ...nor accept one as the host's ephemeral.
        val (hs2, _) = TestResponder(hostId, hostStatic, client.hs1).complete()
        assertEquals("bad-key", codeOf { client.finish(hs2.copy(e = B64u.encode(ByteArray(32)))) })
    }

    @Test
    fun enrollNeedsTheRightPairingCode() {
        val code = Primitives.randomBytes(32)
        val client = Initiator(HandshakeMode.ENROLL, hostId, hostStatic.pub, clientStatic, code)
        val right = TestResponder(hostId, hostStatic, client.hs1, code)
        assertEquals(HandshakeMode.ENROLL, right.mode)
        assertContentEquals(clientStatic.pub, right.clientStatic)

        assertEquals("handshake-failed", codeOf { TestResponder(hostId, hostStatic, client.hs1, Primitives.randomBytes(32)) })
        assertEquals("bad-key", codeOf { TestResponder(hostId, hostStatic, client.hs1) })
        // The mode is bound into h0: an enroll hs1 cannot pass as connect.
        assertEquals("handshake-failed", codeOf { TestResponder(hostId, hostStatic, client.hs1.copy(mode = "connect")) })
        assertEquals("bad-key", codeOf { Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic, code) })
        assertEquals("bad-key", codeOf { Initiator(HandshakeMode.ENROLL, hostId, hostStatic.pub, clientStatic, ByteArray(16)) })
    }

    @Test
    fun enrollWithWrongPskFailsAtTheDeviceToo() {
        // A host that somehow opened hs1 with a different code derives different
        // keys; its confirmation does not verify at the device.
        val code = Primitives.randomBytes(32)
        val client = Initiator(HandshakeMode.ENROLL, hostId, hostStatic.pub, clientStatic, code)
        val connectClient = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, clientStatic)
        val (connectHs2, _) = TestResponder(hostId, hostStatic, connectClient.hs1).complete()
        assertEquals("handshake-failed", codeOf { client.finish(connectHs2) })
    }

    // --- data frames ------------------------------------------------------------

    @Test
    fun framesAreStrictlyOrdered() {
        val (clientKeys, hostKeys) = connected()
        val sealer = FrameSealer(clientKeys.send)
        val opener = FrameOpener(hostKeys.recv)
        val f0 = sealer.seal("第一帧")
        val f1 = sealer.seal("second")
        val f2 = sealer.seal("third")
        assertEquals(listOf(0L, 1L, 2L), listOf(f0.n, f1.n, f2.n))
        assertEquals("第一帧", opener.open(f0))
        assertEquals("second", opener.open(f1))
        assertEquals("replayed", codeOf { opener.open(f0) })
        assertEquals("out-of-order", codeOf { opener.open(f2.copy(n = 3)) })
    }

    @Test
    fun tamperedFramesAreRejected() {
        val (clientKeys, hostKeys) = connected()
        val sealer = FrameSealer(clientKeys.send)
        val f0 = sealer.seal("第一帧")
        sealer.seal("second")
        val f2 = sealer.seal("third")

        val flipped = B64u.decode(f0.c).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals("bad-frame", codeOf { FrameOpener(hostKeys.recv).open(f0.copy(c = B64u.encode(flipped))) })

        val tag = B64u.decode(f0.c).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals("bad-frame", codeOf { FrameOpener(hostKeys.recv).open(f0.copy(c = B64u.encode(tag))) })

        val truncated = B64u.decode(f0.c).copyOfRange(0, 10)
        assertEquals("bad-frame", codeOf { FrameOpener(hostKeys.recv).open(f0.copy(c = B64u.encode(truncated))) })

        // n is in the AAD as well as the nonce: relabelling frame 2 as frame 1 fails.
        val relabel = FrameOpener(hostKeys.recv)
        relabel.open(f0)
        assertEquals("bad-frame", codeOf { relabel.open(f2.copy(n = 1)) })

        // Directions do not mix: a device frame does not open under the host's send key.
        assertEquals("bad-frame", codeOf { FrameOpener(hostKeys.send).open(f0) })
    }

    @Test
    fun aFailedFrameDoesNotAdvanceTheCounter() {
        val (clientKeys, hostKeys) = connected()
        val sealer = FrameSealer(clientKeys.send)
        val f0 = sealer.seal("x")
        val opener = FrameOpener(hostKeys.recv)
        val bad = B64u.decode(f0.c).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals("bad-frame", codeOf { opener.open(f0.copy(c = B64u.encode(bad))) })
        assertEquals(0L, opener.received)
    }

    @Test
    fun invalidUtf8PlaintextIsRejected() {
        val (clientKeys, hostKeys) = connected()
        // Seal raw bytes that are not UTF-8 (a lone continuation byte), bypassing FrameSealer.
        val n = 0L
        val sealed = Primitives.seal(clientKeys.send, FrameCrypto.nonce(n), FrameCrypto.aad(n), byteArrayOf(0x41, 0x80.toByte()))
        assertEquals("bad-frame", codeOf { FrameOpener(hostKeys.recv).open(DataFrame(n, B64u.encode(sealed))) })
    }

    // --- key wrap and history ----------------------------------------------------

    @Test
    fun keyWrapBindsRecipientAndEpoch() {
        val recipient = Primitives.generateKeyPair()
        val key = Primitives.randomBytes(32)
        val context = HistoryCrypto.wrapContext(hostId, 1)
        val wrapped = HistoryCrypto.wrapKey(key, recipient.pub, context)
        assertEquals(80, wrapped.size)
        assertContentEquals(key, HistoryCrypto.unwrapKey(wrapped, recipient, context))
        assertFailsWith<CryptoError> { HistoryCrypto.unwrapKey(wrapped, Primitives.generateKeyPair(), context) }
        assertFailsWith<CryptoError> { HistoryCrypto.unwrapKey(wrapped, recipient, HistoryCrypto.wrapContext(hostId, 2)) }
        assertFailsWith<CryptoError> { HistoryCrypto.unwrapKey(wrapped, recipient, HistoryCrypto.wrapContext("other-host", 1)) }
        assertFailsWith<CryptoError> { HistoryCrypto.unwrapKey(wrapped.copyOf(79), recipient, context) }
    }

    @Test
    fun historyBlobIsBoundToItsSession() {
        val key = Primitives.randomBytes(32)
        val aad = HistoryCrypto.historyAad(hostId, "claude:abc", 1)
        val blob = HistoryCrypto.sealBlob(key, aad, utf8("历史"))
        assertEquals("历史", HistoryCrypto.openBlob(key, aad, blob).decodeToString())
        assertFailsWith<CryptoError> { HistoryCrypto.openBlob(key, HistoryCrypto.historyAad(hostId, "claude:other", 1), blob) }
        assertFailsWith<CryptoError> { HistoryCrypto.openBlob(key, HistoryCrypto.historyAad(hostId, "claude:abc", 2), blob) }
        val again = HistoryCrypto.sealBlob(key, aad, utf8("历史"))
        assertFalse(sameBytes(blob.copyOfRange(0, 12), again.copyOfRange(0, 12)), "nonces differ")
    }

    // --- encoding ---------------------------------------------------------------

    @Test
    fun base64UrlIsStrict() {
        val bytes = byteArrayOf(-5, -1, 0, 63)
        val text = B64u.encode(bytes)
        assertEquals("-_8APw", text)
        assertContentEquals(bytes, B64u.decode(text))
        assertEquals("bad-encoding", codeOf { B64u.decode("$text==") }, "padding")
        assertEquals("bad-encoding", codeOf { B64u.decode("+/8APw") }, "standard alphabet")
        assertEquals("bad-encoding", codeOf { B64u.decode("-_8AP") }, "length % 4 == 1")
        assertEquals("bad-encoding", codeOf { B64u.decode("-_8APx") }, "non-canonical trailing bits")
        assertEquals("bad-encoding", codeOf { B64u.decode("-_8A Pw") }, "whitespace")
        assertEquals("bad-encoding", codeOf { B64u.decode(text, 5) }, "wrong length")
        assertContentEquals(ByteArray(0), B64u.decode(""))
    }

    // --- pairing payload --------------------------------------------------------

    private fun encodePairing(fields: String): String =
        Protocol.PAIRING_URL_PREFIX + B64u.encode(utf8(fields))

    @Test
    fun pairingPayloadDecodes() {
        val k = B64u.encode(hostStatic.pub)
        val c = B64u.encode(Primitives.randomBytes(32))
        val json = """{"v":1,"n":"$hostId","k":"$k","c":"$c","h":["192.168.1.20","10.0.0.5"],"p":47290,"s":"https://ai.surise.cn","m":"书房的 Mac"}"""
        val link = encodePairing(json)
        val payload = Pairing.decode(link)
        assertEquals(hostId, payload.hostId)
        assertContentEquals(hostStatic.pub, payload.hostKey)
        assertEquals(listOf("192.168.1.20", "10.0.0.5"), payload.lanAddrs)
        assertEquals(47290, payload.lanPort)
        assertEquals("书房的 Mac", payload.machine)
        assertEquals(payload, Pairing.decode(link.removePrefix(Protocol.PAIRING_URL_PREFIX)), "bare payload works")
        assertEquals(payload, Pairing.decode("  $link\n"), "surrounding whitespace is ignored")
        assertEquals(fingerprint(hostStatic.pub), payload.fingerprint)
    }

    @Test
    fun badPairingPayloadsAreRejected() {
        assertFailsWith<IllegalArgumentException> { Pairing.decode("skidsense://pair/1?d=!!!") }
        assertFailsWith<IllegalArgumentException> { Pairing.decode("skidsense://pair/2?d=abc") }
        val c = B64u.encode(Primitives.randomBytes(32))
        val shortKey = B64u.encode(ByteArray(16))
        assertFailsWith<IllegalArgumentException> {
            Pairing.decode(encodePairing("""{"v":1,"n":"x","k":"$shortKey","c":"$c","h":[],"p":1,"s":"s","m":"m"}"""))
        }
        val k = B64u.encode(hostStatic.pub)
        assertFailsWith<IllegalArgumentException> {
            Pairing.decode(encodePairing("""{"v":2,"n":"x","k":"$k","c":"$c","h":[],"p":1,"s":"s","m":"m"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            Pairing.decode(encodePairing("""{"v":1,"k":"$k","c":"$c","h":[],"p":1,"s":"s","m":"m"}"""))
        }
    }

    @Test
    fun backendUrlsCompareNormalised() {
        assertEquals(normalizeBackendUrl("https://ai.surise.cn"), normalizeBackendUrl("HTTPS://AI.surise.cn/"))
        assertEquals(normalizeBackendUrl("https://ai.surise.cn:443"), normalizeBackendUrl("https://ai.surise.cn"))
        assertNotEquals(normalizeBackendUrl("http://ai.surise.cn"), normalizeBackendUrl("https://ai.surise.cn"))
        assertNotEquals(normalizeBackendUrl("https://evil.example"), normalizeBackendUrl("https://ai.surise.cn"))
    }

    @Test
    fun fingerprintFormat() {
        val fp = fingerprint(Primitives.randomBytes(32))
        assertTrue(Regex("^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$").matches(fp), fp)
    }
}
