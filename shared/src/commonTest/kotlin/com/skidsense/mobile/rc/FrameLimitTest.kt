package com.skidsense.mobile.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The per-frame and per-key limits (spec §5), from both ends. The receiving
 * side used to trust the peer to stop at 2^32 frames; it now refuses a frame
 * past the limit itself, as the desktop does.
 */
class FrameLimitTest {
    private val key = Primitives.randomBytes(32)

    /** A frame at counter [n], built the way the protocol defines one — which no conforming sealer emits past the limit. */
    private fun forge(n: Long, text: String): DataFrame =
        DataFrame(n, B64u.encode(Primitives.seal(key, FrameCrypto.nonce(n), FrameCrypto.aad(n), utf8(text))))

    @Test
    fun theSealerRefusesAMessageOverOneFrame() {
        val sealer = FrameSealer(key)
        val error = assertFailsWith<CryptoError> { sealer.seal("好".repeat(Protocol.MAX_PLAINTEXT / 3 + 1)) }
        assertEquals("too-large", error.code)
        assertEquals(0L, sealer.sent, "a refused seal spends no counter value")
    }

    @Test
    fun theOpenerRefusesAPeerPastThePerKeyFrameLimit() {
        val opener = FrameOpener(key)
        opener.startAt(Protocol.MAX_FRAMES_PER_KEY - 1)
        assertEquals("last", opener.open(forge(Protocol.MAX_FRAMES_PER_KEY - 1, "last")), "the forged frame is well formed")
        val error = assertFailsWith<CryptoError> { opener.open(forge(Protocol.MAX_FRAMES_PER_KEY, "over")) }
        assertEquals("rekey", error.code)
    }

    @Test
    fun theOpenerRefusesAPlaintextOverOneFrame() {
        val error = assertFailsWith<CryptoError> { FrameOpener(key).open(forge(0, "x".repeat(Protocol.MAX_PLAINTEXT + 1))) }
        assertEquals("too-large", error.code)
    }

    @Test
    fun utf8LengthCountsBytesNotCodeUnits() {
        assertEquals(1L, utf8Length("a"))
        assertEquals(2L, utf8Length("é"))
        assertEquals(3L, utf8Length("好"))
        assertEquals(4L, utf8Length("😀"))
        assertEquals(3L, utf8Length("\uD83D"), "a lone surrogate counts as U+FFFD")
        // Compared with a real encoding only for well-formed text: platforms
        // disagree on what a lone surrogate becomes (the JVM writes '?').
        val text = "a好😀é，混合 text 与 emoji 🎉"
        assertEquals(utf8(text).size.toLong(), utf8Length(text))
    }
}
