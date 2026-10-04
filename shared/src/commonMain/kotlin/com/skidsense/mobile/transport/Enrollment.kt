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
                val initiator = Initiator(HandshakeMode.ENROLL, payload.hostId, payload.hostKey, identity, payload.code)
                val connection = RcConnection.establish(carrier, route, initiator, null, ticket, config.connection, scope)
                val welcome = connection.welcome
                connection.close("enrolled")
                return welcome
            } catch (error: CancellationException) {
                throw error
            } catch (error: HandshakeRejected) {
                if (error.permanent) throw error
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
