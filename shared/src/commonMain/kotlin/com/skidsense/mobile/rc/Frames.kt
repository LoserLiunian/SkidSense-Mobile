package com.skidsense.mobile.rc

/**
 * Data frames (spec §5): `nonce = 4×0x00 ‖ u64be(n)`, `aad = "skidsense-rc/1 d" ‖ u64be(n)`.
 */
internal object FrameCrypto {
    private const val LABEL_DATA = "${Protocol.NAME} d"

    fun nonce(n: Long): ByteArray = concat(ByteArray(4), u64be(n))
    fun aad(n: Long): ByteArray = concat(utf8(LABEL_DATA), u64be(n))
}

/**
 * One direction's sealing state. The counter is the nonce: it starts at 0,
 * only goes up, and the sealer refuses to continue past the per-key limits
 * rather than wrap — reusing a GCM nonce under one key leaks the
 * authentication key outright.
 */
class FrameSealer(private val key: ByteArray) {
    private var n = 0L
    private var bytes = 0L

    /** How many frames this key has sealed. */
    val sent: Long get() = n

    fun seal(plaintext: String): DataFrame {
        val data = utf8(plaintext)
        // The far end refuses anything larger and closes the connection over
        // it (spec §5), so it is refused here, where the caller still has a
        // connection to report the error on.
        if (data.size > Protocol.MAX_PLAINTEXT) throw CryptoError("too-large", "消息超过单帧上限")
        if (n >= Protocol.MAX_FRAMES_PER_KEY || bytes + data.size > Protocol.MAX_BYTES_PER_KEY) {
            throw CryptoError("rekey", "本次连接的密钥用量已到上限，需要重新连接")
        }
        val counter = n
        n += 1
        bytes += data.size
        return DataFrame(counter, B64u.encode(Primitives.seal(key, FrameCrypto.nonce(counter), FrameCrypto.aad(counter), data)))
    }
}

/**
 * The receiving direction. Every carrier is reliable and ordered, so `n` must
 * be exactly the next one: smaller is `replayed`, larger is `out-of-order`,
 * and either ends the connection. Stricter than a replay window on purpose —
 * a frame can only go missing on an ordered stream if something removed it.
 */
class FrameOpener(private val key: ByteArray) {
    private var n = 0L
    private var bytes = 0L

    val received: Long get() = n

    fun open(frame: DataFrame): String {
        if (frame.n != n) {
            if (frame.n < n) throw CryptoError("replayed", "重放的帧")
            throw CryptoError("out-of-order", "帧顺序错误")
        }
        // The per-key limits bind the receiver too (spec §5): a peer that
        // carried on past them is using a key the protocol calls spent.
        if (frame.n >= Protocol.MAX_FRAMES_PER_KEY) throw CryptoError("rekey", "对端超过了单个密钥的帧数上限")
        val plaintext = Primitives.open(key, FrameCrypto.nonce(frame.n), FrameCrypto.aad(frame.n), B64u.decode(frame.c))
        if (plaintext.size > Protocol.MAX_PLAINTEXT) throw CryptoError("too-large", "帧过大")
        if (bytes + plaintext.size > Protocol.MAX_BYTES_PER_KEY) throw CryptoError("rekey", "对端超过了单个密钥的字节上限")
        n += 1
        bytes += plaintext.size
        return strictUtf8(plaintext)
    }

    /** Test seam: start the counter elsewhere, to reach the per-key limit without 2^32 frames. */
    internal fun startAt(counter: Long) {
        n = counter
    }
}
