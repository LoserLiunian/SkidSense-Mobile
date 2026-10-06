package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.CryptoError
import com.skidsense.mobile.rc.HandshakeMode
import com.skidsense.mobile.rc.Initiator
import com.skidsense.mobile.rc.KeyPair
import com.skidsense.mobile.rc.PairingPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The first visit (spec §4, `enroll` mode): the pairing code from the QR is
 * the PSK, the backend's `rc-enroll` ticket goes in `hello`, and a `welcome`
 * means the host activated this device and recorded its key locally.
 *
 * Tries the QR's LAN addresses first, then the relay (which admits a pending
 * device as long as its first frame is an enroll `hs1`). The connection is
 * closed afterwards; later visits are ordinary `connect` handshakes with a grant.
 */
object Enrollment {
    suspend fun enroll(
        payload: PairingPayload,
        deviceId: String,
        identity: KeyPair,
        ticket: String,
        carriers: CarrierFactory,
        scope: CoroutineScope,
        config: ClientConfig = ClientConfig(),
        onProgress: (String) -> Unit = {}
    ): Welcome = reach(
        payload = payload,
        deviceId = deviceId,
        identity = identity,
        ticket = ticket,
        carriers = carriers,
        scope = scope,
        mode = HandshakeMode.ENROLL,
        psk = payload.code,
        modeLabel = "正在与电脑握手",
        config = config,
        onProgress = onProgress
    )

    /**
     * The 409 recovery (spec §9): the phone is already paired to this host with
     * a key the backend still lists as active, so `POST /devices` refused with
     * the existing device id and no ticket. Fetch an `rc-access` grant for it
     * and run an ordinary `connect` handshake — the same skeleton as `enroll`,
     * with the grant in `hello` instead of a ticket and the pairing code
     * replaced by nothing.
     *
     * Its own function rather than a branch inside `enroll` because the two
     * differ in exactly these arguments and agree in everything expensive:
     * route order, per-route timeouts, and which failures are worth retrying
     * elsewhere. A caller that has a ticket must never reach this path, and a
     * caller that has none must never take the PSK one — keeping them apart is
     * what makes that checkable.
     */
    suspend fun connectWithGrant(
        payload: PairingPayload,
        deviceId: String,
        identity: KeyPair,
        /** An `rc-access` grant, resolved by the caller (it may need refreshing). */
        grant: String,
        carriers: CarrierFactory,
        scope: CoroutineScope,
        config: ClientConfig = ClientConfig(),
        onProgress: (String) -> Unit = {}
    ): Welcome = reach(
        payload = payload,
        deviceId = deviceId,
        identity = identity,
        grant = grant,
        carriers = carriers,
        scope = scope,
        mode = HandshakeMode.CONNECT,
        psk = null,
        modeLabel = "正在重新连接这台电脑",
        config = config,
        onProgress = onProgress
    )

    private suspend fun reach(
        payload: PairingPayload,
        deviceId: String,
        identity: KeyPair,
        /** `connect` carries one of these and `enroll` the other; never both. */
        grant: String? = null,
        ticket: String? = null,
        carriers: CarrierFactory,
        scope: CoroutineScope,
        mode: HandshakeMode,
        psk: ByteArray?,
        modeLabel: String,
        config: ClientConfig,
        onProgress: (String) -> Unit
    ): Welcome {
        val endpoint = HostEndpoint(payload.hostId, payload.hostKey, deviceId, payload.lanAddrs, payload.lanPort)
        var lastError: Throwable = RcException("no-route", "没有可用的连接方式")
        // A refusal from something that spoke the protocol says more than "could not connect".
        var reached = false
        fun unreachable(error: Throwable) { if (!reached) lastError = error }
        fun refused(error: Throwable) { reached = true; lastError = error }
        for (route in endpoint.routes()) {
            onProgress("正在通过${route.label}连接…")
            val timeout = if (route is Route.Lan) config.lanConnectTimeoutMs else config.relayConnectTimeoutMs
            val carrier = try {
                withTimeout(timeout) { carriers.open(route, CarrierTarget(payload.hostId, deviceId)) }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) {
                    unreachable(RcException("timeout", "${route.label}：连接超时"))
                    continue
                }
                throw error
            } catch (error: Throwable) {
                unreachable(error)
                continue
            }
            try {
                val initiator = Initiator(mode, payload.hostId, payload.hostKey, identity, psk)
                val connection = RcConnection.establish(carrier, route, initiator, grant, ticket, config.connection, scope)
                val welcome = connection.welcome
                connection.close(modeLabel)
                return welcome
            } catch (error: CancellationException) {
                throw error
            } catch (error: HandshakeClosed) {
                // Same rule as the client's loop: the carrier died before the
                // welcome. This route is dead; the device is not.
                unreachable(error)
            } catch (error: HandshakeRejected) {
                // A plaintext `hsr` on a LAN address is forgeable by anything
                // that answers there, so it never settles anything on its own
                // (spec §6.5, C6): the remaining routes still run. Only the same
                // refusal through the relay — TLS to the backend, which
                // identity-checks the device before forwarding — counts as the
                // host's word, and only it stops the walk.
                if (error.permanent && route is Route.Relay) throw error
                refused(error)
            } catch (error: RelayRejected) {
                if (error.permanent) throw error
                refused(error)
            } catch (error: RcConnection.HelloRefused) {
                // The ticket was refused: retrying elsewhere will not change that.
                throw error
            } catch (error: CryptoError) {
                refused(error)
            } catch (error: Throwable) {
                unreachable(error)
            }
        }
        throw lastError
    }
}
