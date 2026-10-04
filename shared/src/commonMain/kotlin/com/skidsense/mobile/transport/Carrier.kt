package com.skidsense.mobile.transport

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Something that moves outer frames (spec §4–§5) as text messages: a LAN
 * WebSocket straight to the desktop, a relay WebSocket through the backend,
 * or an in-memory pipe in tests. Both real carriers carry byte-identical
 * frames (spec §10.2, §10.4), which is why one client speaks both.
 */
interface Carrier {
    /** Text messages from the far end. Closes when the carrier does. */
    val incoming: ReceiveChannel<String>

    suspend fun send(text: String)

    /** Close the carrier. Idempotent. */
    suspend fun close(reason: String = "")

    /** A short label for UI and logs: `局域网 192.168.1.20` / `中继`. */
    val label: String
}

/** Where a carrier goes. */
sealed interface Route {
    val label: String

    data class Lan(val address: String, val port: Int) : Route {
        override val label: String get() = "局域网 $address"
    }

    data object Relay : Route {
        override val label: String get() = "中继"
    }
}

/** Opens carriers. Throws when the far end cannot be reached. */
fun interface CarrierFactory {
    suspend fun open(route: Route, target: CarrierTarget): Carrier
}

/** Everything a factory needs to address one host for one device. */
data class CarrierTarget(
    val hostId: String,
    /** Null before the device is registered (never the case for a real connection). */
    val deviceId: String?
)

/** The carrier could not be opened at all (refused, timed out, DNS…). */
class CarrierUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
