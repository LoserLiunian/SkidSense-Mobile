package com.skidsense.mobile.rc

/**
 * The host half of the handshake, test-only: a port of the reference
 * `respond()` so tests can run a full handshake in-process and stand up a fake
 * host. The app never acts as a host, so this stays out of commonMain.
 */
class TestResponder(
    hostId: String,
    private val hostStatic: KeyPair,
    hs1: Hs1Frame,
    psk: ByteArray? = null
) {
    val mode: HandshakeMode
    val clientEphemeral: ByteArray
    val clientStatic: ByteArray
    private val psk: ByteArray
    private val h0: ByteArray
    private val sealedStatic: ByteArray
    private val es: ByteArray

    init {
        if (hs1.v != 1) throw CryptoError("unsupported-version", "协议版本不受支持，请更新手机端")
        mode = HandshakeMode.fromWire(hs1.mode) ?: throw CryptoError("handshake-failed", "握手模式无效")
        if (hs1.host != hostId) throw CryptoError("wrong-host", "握手不是发给这台电脑的")
        this.psk = Handshake.checkPsk(mode, psk)
        h0 = Handshake.handshakeHash(mode, hostId)
        clientEphemeral = B64u.decode(hs1.e, 32)
        sealedStatic = B64u.decode(hs1.s, 48)
        es = Primitives.dh(hostStatic.priv, clientEphemeral)
        val k1 = Handshake.staticKey(es, this.psk, h0)
        clientStatic = try {
            Handshake.openStatic(k1, h0, clientEphemeral, sealedStatic)
        } catch (_: CryptoError) {
            throw CryptoError("handshake-failed", "握手失败")
        }
        if (clientStatic.size != 32) throw CryptoError("handshake-failed", "握手失败")
    }

    fun complete(ephemeral: KeyPair? = null): Pair<Hs2Frame, SessionKeys> {
        val eh = ephemeral ?: Primitives.generateKeyPair()
        val derived = Handshake.derive(
            es = es,
            ss = Primitives.dh(hostStatic.priv, clientStatic),
            ee = Primitives.dh(eh.priv, clientEphemeral),
            se = Primitives.dh(eh.priv, clientStatic),
            psk = psk,
            h0 = h0,
            clientEphemeral = clientEphemeral,
            sealedStatic = sealedStatic,
            hostEphemeral = eh.pub
        )
        return Hs2Frame(B64u.encode(eh.pub), B64u.encode(derived.confirm)) to
            SessionKeys(send = derived.s2c, recv = derived.c2s, th = derived.th)
    }
}
