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

    /**
     * `MAX_FRAME` is bytes on the wire (spec §5). The parser counted UTF-16
     * units, so 2 Mi CJK units — about 6 MiB — passed it; only the real carrier's
     * own byte check stood in the way, and a carrier without one had none.
     */
    @Test
    fun theOuterFrameLimitIsCountedInBytes() {
        fun hsr(fill: String) = """{"t":"hsr","code":"x","message":"$fill"}"""
        val envelope = hsr("").length
        val exactAscii = hsr("a".repeat(Protocol.MAX_FRAME - envelope))
        assertEquals(Protocol.MAX_FRAME.toLong(), utf8Length(exactAscii))
        assertEquals(OuterFrame.Reject::class, OuterFrames.parse(exactAscii)::class, "exactly MAX_FRAME bytes is a frame")
        assertEquals("too-large", assertFailsWith<CryptoError> { OuterFrames.parse(hsr("a".repeat(Protocol.MAX_FRAME - envelope + 1))) }.code)

        val cjk = hsr("好".repeat((Protocol.MAX_FRAME - envelope) / 3 + 1))
        assertEquals(true, cjk.length < Protocol.MAX_FRAME, "fewer code units than the limit…")
        assertEquals(true, utf8Length(cjk) > Protocol.MAX_FRAME, "…but more bytes")
        assertEquals("too-large", assertFailsWith<CryptoError> { OuterFrames.parse(cjk) }.code)
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
