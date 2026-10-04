package com.skidsense.mobile.rc

/**
 * The device side of the `skidsense-rc/1` handshake (spec §4).
 *
 * Modelled on Noise IK, not an implementation of it: the device already holds
 * the host's static key (pinned from the pairing QR, never learned from the
 * network), and four DH terms go into one HKDF over the transcript hash —
 *
 *     es = DH(e_c, S_h)   only the real host can compute this
 *     ss = DH(S_c, S_h)   binds the two long-term identities
 *     ee = DH(e_c, e_h)   forward secrecy
 *     se = DH(S_c, e_h)   authenticates the device
 *
 * so a relay that swaps either ephemeral key derives keys neither end does,
 * and cannot produce the host's confirmation tag.
 */
object Handshake {
    internal const val LABEL_STATIC = "${Protocol.NAME} static"
    internal const val LABEL_C2S = "${Protocol.NAME} c2s"
    internal const val LABEL_S2C = "${Protocol.NAME} s2c"
    internal const val LABEL_CONFIRM = "${Protocol.NAME} confirm"

    private val ZERO_NONCE = ByteArray(12)

    /** `h0`: binds the protocol, the mode and the intended host into everything after. */
    fun handshakeHash(mode: HandshakeMode, hostId: String): ByteArray =
        Primitives.sha256(utf8(Protocol.NAME), ZERO_BYTE, utf8(mode.wire), ZERO_BYTE, utf8(hostId))

    /** `enroll` needs the 32-byte pairing code; `connect` takes none. */
    internal fun checkPsk(mode: HandshakeMode, psk: ByteArray?): ByteArray = when (mode) {
        HandshakeMode.ENROLL -> {
            if (psk == null || psk.size != 32) throw CryptoError("bad-key", "配对码应为 32 字节")
            psk.copyOf()
        }
        HandshakeMode.CONNECT -> {
            if (psk != null && psk.isNotEmpty()) throw CryptoError("bad-key", "connect 模式不带配对码")
            ByteArray(0)
        }
    }

    internal class Derived(val c2s: ByteArray, val s2c: ByteArray, val confirm: ByteArray, val th: ByteArray)

    internal fun derive(
        es: ByteArray,
        ss: ByteArray,
        ee: ByteArray,
        se: ByteArray,
        psk: ByteArray,
        h0: ByteArray,
        clientEphemeral: ByteArray,
        sealedStatic: ByteArray,
        hostEphemeral: ByteArray
    ): Derived {
        val ikm = concat(es, ss, ee, se, psk)
        val th = Primitives.sha256(h0, clientEphemeral, sealedStatic, hostEphemeral)
        val confirmKey = Primitives.hkdf(ikm, th, LABEL_CONFIRM)
        return Derived(
            c2s = Primitives.hkdf(ikm, th, LABEL_C2S),
            s2c = Primitives.hkdf(ikm, th, LABEL_S2C),
            confirm = Primitives.hmacSha256(confirmKey, th),
            th = th
        )
    }

    internal fun sealStatic(k1: ByteArray, h0: ByteArray, clientEphemeral: ByteArray, clientStaticPub: ByteArray): ByteArray =
        Primitives.seal(k1, ZERO_NONCE, concat(h0, clientEphemeral), clientStaticPub)

    internal fun openStatic(k1: ByteArray, h0: ByteArray, clientEphemeral: ByteArray, sealed: ByteArray): ByteArray =
        Primitives.open(k1, ZERO_NONCE, concat(h0, clientEphemeral), sealed)

    internal fun staticKey(es: ByteArray, psk: ByteArray, h0: ByteArray): ByteArray =
        Primitives.hkdf(concat(es, psk), h0, LABEL_STATIC)
}

/** The keys one end of a finished handshake uses. */
class SessionKeys(val send: ByteArray, val recv: ByteArray, val th: ByteArray)

/**
 * The device half: [hs1] to send, then [finish] turns the host's `hs2` into
 * session keys — or throws `handshake-failed` when the confirmation does not
 * match, i.e. the far end does not hold the pinned host key.
 *
 * [ephemeral] is for the known-answer vectors only; production passes nothing.
 */
class Initiator(
    val mode: HandshakeMode,
    val hostId: String,
    private val hostStatic: ByteArray,
    private val clientStatic: KeyPair,
    psk: ByteArray? = null,
    ephemeral: KeyPair? = null
) {
    private val psk = Handshake.checkPsk(mode, psk)
    private val h0 = Handshake.handshakeHash(mode, hostId)
    private val e = ephemeral ?: Primitives.generateKeyPair()
    private val es: ByteArray
    private val sealedStatic: ByteArray
    val hs1: Hs1Frame

    init {
        if (hostStatic.size != 32) throw CryptoError("bad-key", "主机公钥应为 32 字节")
        es = Primitives.dh(e.priv, hostStatic)
        val k1 = Handshake.staticKey(es, this.psk, h0)
        sealedStatic = Handshake.sealStatic(k1, h0, e.pub, clientStatic.pub)
        hs1 = Hs1Frame(Protocol.VERSION, mode.wire, hostId, B64u.encode(e.pub), B64u.encode(sealedStatic))
    }

    fun finish(hs2: Hs2Frame): SessionKeys {
        val eh = B64u.decode(hs2.e, 32)
        val confirm = B64u.decode(hs2.c, 32)
        val derived = Handshake.derive(
            es = es,
            ss = Primitives.dh(clientStatic.priv, hostStatic),
            ee = Primitives.dh(e.priv, eh),
            se = Primitives.dh(clientStatic.priv, eh),
            psk = psk,
            h0 = h0,
            clientEphemeral = e.pub,
            sealedStatic = sealedStatic,
            hostEphemeral = eh
        )
        // This is what proves the far end holds the host's private key: `es`
        // on its side needs it. Without the check a relay could answer with
        // any ephemeral key and the failure would only show up as garbage later.
        if (!sameBytes(confirm, derived.confirm)) {
            throw CryptoError("handshake-failed", "主机身份校验失败：可能不是你配对的那台电脑")
        }
        return SessionKeys(send = derived.c2s, recv = derived.s2c, th = derived.th)
    }
}
