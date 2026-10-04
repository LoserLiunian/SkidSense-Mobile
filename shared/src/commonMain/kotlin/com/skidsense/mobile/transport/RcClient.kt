package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.CryptoError
import com.skidsense.mobile.rc.HandshakeMode
import com.skidsense.mobile.rc.Initiator
import com.skidsense.mobile.rc.KeyPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * One paired host, as the client needs it. [hostKey] is the key pinned from the
 * QR code — never learned from the network.
 *
 * The LAN address is whatever the pairing QR carried, and a router hands out a
 * new one often enough that it cannot be the only one we ever try. [learn]
 * replaces the current addresses with a fresher list from `GET /hosts` while
 * keeping the old ones as a fallback: a stale address costs one short timeout,
 * and a host that moved is then reachable again on the next attempt.
 */
class HostEndpoint(
    val hostId: String,
    val hostKey: ByteArray,
    val deviceId: String,
    lanAddrs: List<String>,
    val lanPort: Int,
    val relayEnabled: Boolean = true
) {
    private var current: List<String> = lanAddrs

    /** Addresses we have seen for this host, newest first: what to try right now. */
    val addresses: List<String> get() = current

    /**
     * Everything known, in the order to try it: the current addresses first,
     * then whatever the QR code said, so a moved host recovers and a stale
     * advertisement costs one timeout rather than a failure.
     */
    private val known: MutableList<String> = ArrayList(16)

    init {
        known += lanAddrs
    }

    /**
     * Take a fresher address list from the backend. Returns true when it says
     * something new, so the caller can nudge a client that is failing.
     */
    fun learn(addrs: List<String>, port: Int? = null): Boolean {
        val incoming = addrs.filter { it.isNotBlank() }.distinct()
        if (incoming.isEmpty() && (port == null || port == lanPort)) return false
        val changed = incoming != current || (port != null && port != lanPort)
        if (incoming.isNotEmpty()) {
            current = incoming
            for (address in incoming.asReversed()) {
                known.remove(address)
                known.add(0, address)
            }
            while (known.size > 16) known.removeAt(known.size - 1)
        }
        return changed
    }

    fun routes(): List<Route> = buildList {
        if (lanPort in 1..65535) known.distinct().forEach { add(Route.Lan(it, lanPort)) }
        if (relayEnabled) add(Route.Relay)
    }
}

/** Where `rc-access` grants come from (the backend, with a cache). */
interface Credentials {
    /** An `rc-access` grant for this device and host. [fresh] skips any cache. */
    suspend fun grant(fresh: Boolean): String

    /** The relay said the bearer is no good; the next relay attempt should re-authenticate. */
    suspend fun onUnauthorized() {}
}

data class ClientConfig(
    /** Per LAN address: a desktop on this network answers well within this. */
    val lanConnectTimeoutMs: Long = 2_500,
    val relayConnectTimeoutMs: Long = 15_000,
    val backoffBaseMs: Long = 1_000,
    val backoffMaxMs: Long = 30_000,
    /** How long a call waits for a connection before failing. */
    val callWaitMs: Long = 15_000,
    val connection: ConnectionConfig = ConnectionConfig()
)

sealed interface ClientState {
    data object Idle : ClientState
    data class Connecting(val via: String, val attempt: Int) : ClientState
    data class Connected(val welcome: Welcome, val route: Route) : ClientState
    /** Will retry by itself after [retryInMs]. */
    data class Waiting(val error: String, val retryInMs: Long, val attempt: Int) : ClientState
    /** Will not retry until [RcClient.retry]: the same attempt would fail the same way. */
    data class Failed(val error: String, val code: String?) : ClientState
}

/**
 * The connection to one host, kept up: LAN addresses first (each with a short
 * timeout), then the relay; exponential backoff between rounds; re-subscribe
 * after every reconnect. One implementation for both carriers — the route is
 * the only difference.
 */
class RcClient(
    private val endpoint: HostEndpoint,
    private val identity: KeyPair,
    private val credentials: Credentials,
    private val carriers: CarrierFactory,
    private val scope: CoroutineScope,
    private val config: ClientConfig = ClientConfig()
) {
    private val _state = MutableStateFlow<ClientState>(ClientState.Idle)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RcEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<RcEvent> = _events

    /**
     * Called once per round when no LAN address worked and the relay is next.
     * The host's address may have changed, and only the backend knows the new
     * one — so this is the hook that refreshes it (`AppController.refreshHosts`).
     */
    var onLanUnreachable: (suspend () -> Unit)? = null

    /** Keys to follow, across reconnects. */
    private val subscriptions = mutableSetOf<String>()
    /** Keys already subscribed on the connection currently up. */
    private var subscribedOnCurrent = mutableSetOf<String>()
    private val subscriptionLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var current: RcConnection? = null

    val hostId: String get() = endpoint.hostId

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    /** Retry now: after a permanent failure, or to cut a backoff short (app resumed, network changed). */
    fun retry() {
        start()
        wake.trySend(Unit)
    }

    suspend fun stop() {
        loop?.cancel()
        loop = null
        current?.close("client stopped")
        current = null
        _state.value = ClientState.Idle
    }

    private suspend fun run() {
        var attempt = 0
        var freshGrant = false
        while (true) {
            attempt += 1
            when (val outcome = connectOnce(attempt, freshGrant)) {
                is Outcome.Up -> {
                    attempt = 0
                    freshGrant = false
                    val connection = outcome.connection
                    current = connection
                    val forward = scope.launch { connection.events.collect { _events.emit(it) } }
                    // Re-subscribe before announcing the connection: a caller
                    // that reacts to Connected and immediately asks for a
                    // transcript must not be able to race the subscription.
                    resubscribe(connection)
                    _state.value = ClientState.Connected(connection.welcome, connection.route)

                    val why = connection.closed.await()
                    forward.cancel()
                    current = null
                    if (why is RelayRejected && why.permanent) {
                        _state.value = ClientState.Failed(why.message ?: "连接被拒绝", why.code)
                        wake.receive()
                        continue
                    }
                    if (why is RelayRejected && why.code == "unauthorized") credentials.onUnauthorized()
                    // A connection that was up drops: reconnect promptly, once.
                    _state.value = ClientState.Waiting(why.message ?: "连接已断开", config.backoffBaseMs, 1)
                    withTimeoutOrNull(config.backoffBaseMs) { wake.receive() }
                }
                is Outcome.Permanent -> {
                    _state.value = ClientState.Failed(outcome.error.message ?: "连接失败", (outcome.error as? RcException)?.code)
                    wake.receive()
                    attempt = 0
                }
                is Outcome.Retry -> {
                    freshGrant = outcome.freshGrant
                    val backoff = backoff(attempt)
                    _state.value = ClientState.Waiting(outcome.message, backoff, attempt)
                    withTimeoutOrNull(backoff) { wake.receive() }
                }
            }
        }
    }

    private fun backoff(attempt: Int): Long {
        val exp = config.backoffBaseMs * (1L shl (attempt - 1).coerceIn(0, 10))
        val capped = exp.coerceAtMost(config.backoffMaxMs)
        // ±20 % jitter so a fleet of phones does not reconnect in lockstep.
        val jitter = (capped * 0.2 * (Random.nextDouble() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(config.backoffBaseMs / 2)
    }

    private sealed interface Outcome {
        class Up(val connection: RcConnection) : Outcome
        class Permanent(val error: Throwable) : Outcome
        class Retry(val message: String, val freshGrant: Boolean) : Outcome
    }

    private suspend fun connectOnce(attempt: Int, freshGrant: Boolean): Outcome {
        val routes = endpoint.routes()
        if (routes.isEmpty()) return Outcome.Permanent(RcException("no-route", "没有可用的连接方式"))
        _state.value = ClientState.Connecting("获取授权", attempt)
        val grant = try {
            credentials.grant(freshGrant)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return if (error is RcException && error.code == "revoked") Outcome.Permanent(error)
            else Outcome.Retry("无法获取授权凭证：${error.message}", freshGrant = false)
        }

        var lastError = "无法连接"
        var reached = false
        fun unreachable(message: String) { if (!reached) lastError = message }
        fun refused(message: String) { reached = true; lastError = message }
        for (route in routes) {
            _state.value = ClientState.Connecting(route.label, attempt)
            val timeout = if (route is Route.Lan) config.lanConnectTimeoutMs else config.relayConnectTimeoutMs
            val carrier = try {
                withTimeout(timeout) { carriers.open(route, CarrierTarget(endpoint.hostId, endpoint.deviceId)) }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) {
                    unreachable("${route.label}：连接超时")
                    continue
                }
                throw error
            } catch (error: Throwable) {
                unreachable("${route.label}：${error.message ?: "无法连接"}")
                continue
            }
            try {
                val initiator = Initiator(HandshakeMode.CONNECT, endpoint.hostId, endpoint.hostKey, identity)
                val connection = RcConnection.establish(carrier, route, initiator, grant, null, config.connection, scope)
                return Outcome.Up(connection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: HandshakeRejected) {
                if (error.permanent) return Outcome.Permanent(error)
                refused(error.message ?: error.code)
                // Rate limiting is per source; the relay is another source, so go on.
            } catch (error: RelayRejected) {
                if (error.permanent) return Outcome.Permanent(error)
                if (error.code == "unauthorized") credentials.onUnauthorized()
                refused(error.message ?: error.code)
            } catch (error: RcConnection.HelloRefused) {
                // The host would not take the grant. The next round asks the
                // backend for a fresh one rather than replaying the cached one.
                return Outcome.Retry(error.message ?: "授权被拒绝", freshGrant = true)
            } catch (error: CryptoError) {
                // A failed confirmation: whatever answered at this address is
                // not the host we pinned. Not fatal — another route may be.
                refused("${route.label}：${error.message}")
            } catch (error: Throwable) {
                unreachable("${route.label}：${error.message ?: "连接失败"}")
            }
        }
        if (routes.any { it is Route.Lan } && !reached) {
            runCatching { onLanUnreachable?.invoke() }
        }
        return Outcome.Retry(lastError, freshGrant = false)
    }

    /**
     * Subscribe every remembered key on a fresh connection.
     *
     * The bookkeeping is shared with [subscribe] under one lock: a key is sent
     * at most once per connection, whichever of the two gets there first.
     */
    private suspend fun resubscribe(connection: RcConnection) {
        subscriptionLock.withLock { subscribedOnCurrent = mutableSetOf() }
        val keys = subscriptionLock.withLock { subscriptions.toList() }
        for (key in keys) {
            subscriptionLock.withLock {
                if (isCurrent(connection) && subscribedOnCurrent.add(key)) {
                    runCatching { connection.request("subscribe", JsonObject(mapOf("key" to JsonPrimitive(key)))) }
                }
            }
        }
    }

    private fun isCurrent(connection: RcConnection): Boolean = current === connection || current == null

    /**
     * The live connection, waiting up to [ClientConfig.callWaitMs] for one.
     *
     * Always waits on the state rather than returning [current] when it looks
     * usable: between a drop and the reconnect, `current` still points at the
     * connection that just died, and handing that back would fail a request
     * that the next connection would have served.
     */
    suspend fun connection(): RcConnection {
        retry()
        val connected = runCatching {
            withTimeoutOrNull(config.callWaitMs) { state.filterIsInstance<ClientState.Connected>().first() }
        }.getOrNull() ?: throw RcException("offline", (state.value as? ClientState.Failed)?.error ?: "未连接到电脑")
        return current?.takeIf { it.isOpen }
            ?: throw RcException("offline", "连接已断开：${connected.route.label}")
    }

    /** Call a method on the host. Fails fast with [RcException] when offline. */
    suspend fun call(method: String, params: JsonElement? = null, timeoutMs: Long = config.connection.requestTimeoutMs): JsonElement =
        connection().request(method, params, timeoutMs)

    /** Follow a session's patches (or `*`); remembered across reconnects. */
    suspend fun subscribe(key: String) {
        subscriptionLock.withLock { subscriptions += key }
        val connection = connection()
        subscriptionLock.withLock {
            if (isCurrent(connection) && subscribedOnCurrent.add(key)) {
                runCatching { connection.request("subscribe", JsonObject(mapOf("key" to JsonPrimitive(key)))) }
            }
        }
    }

    suspend fun unsubscribe(key: String) {
        subscriptionLock.withLock { subscriptions -= key; subscribedOnCurrent -= key }
        current?.takeIf { it.isOpen }?.let { runCatching { it.request("unsubscribe", JsonObject(mapOf("key" to JsonPrimitive(key)))) } }
    }

    val welcome: Welcome? get() = (state.value as? ClientState.Connected)?.welcome
}
