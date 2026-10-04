package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.CryptoError
import com.skidsense.mobile.rc.FrameOpener
import com.skidsense.mobile.rc.FrameSealer
import com.skidsense.mobile.rc.Initiator
import com.skidsense.mobile.rc.OuterFrame
import com.skidsense.mobile.rc.OuterFrames
import com.skidsense.mobile.rc.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

/** Tunables, overridable in tests. */
data class ConnectionConfig(
    val handshakeTimeoutMs: Long = Protocol.HANDSHAKE_TIMEOUT_MS,
    val requestTimeoutMs: Long = 30_000,
    /** How often the device pings when the line is quiet. */
    val pingIntervalMs: Long = 25_000,
    /** No frame at all for this long means the line is dead. */
    val idleTimeoutMs: Long = 70_000,
    val app: AppInfo = AppInfo("skidsense-mobile", "0.1.0", "android")
)

/**
 * One established, authenticated conversation with a host over one carrier:
 * the handshake (§4), `hello`/`welcome` (§6.1), then requests, parted
 * responses, events and pings until either side closes.
 *
 * Any violation — a frame out of order, a byte that does not authenticate, a
 * frame that is not a `d` frame after the handshake — closes the connection.
 * Nothing is retried here; reconnecting is [RcClient]'s job, and it always
 * runs a fresh handshake, so a fresh key.
 */
class RcConnection private constructor(
    private val carrier: Carrier,
    private val sealer: FrameSealer,
    private val opener: FrameOpener,
    val welcome: Welcome,
    val route: Route,
    private val config: ConnectionConfig,
    parent: CoroutineScope
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val sendLock = Mutex()
    private val stateLock = Mutex()
    private var nextId = 1L
    private val pending = HashMap<String, Pending>()
    private var lastReceivedAt = now()

    private val _events = MutableSharedFlow<RcEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<RcEvent> = _events

    /** Completes with why the connection ended. */
    val closed = CompletableDeferred<RcException>()
    private var closeError: RcException? = null

    private class Pending(val result: CompletableDeferred<JsonElement>) {
        var parts: Array<String?>? = null
        var bytes: Long = 0
    }

    companion object {
        private fun now(): Long = Clock.System.now().toEpochMilliseconds()

        /**
         * Run the handshake and `hello` on [carrier]. On success the
         * connection's read loop is running in [scope]; on failure the carrier
         * is closed and the error says why ([HandshakeRejected],
         * [RelayRejected], [CryptoError] for a failed confirmation, or
         * [ConnectionClosed]).
         */
        suspend fun establish(
            carrier: Carrier,
            route: Route,
            initiator: Initiator,
            grant: String?,
            ticket: String?,
            config: ConnectionConfig,
            scope: CoroutineScope
        ): RcConnection {
            try {
                return withTimeout(config.handshakeTimeoutMs) {
                    carrier.send(OuterFrames.encode(initiator.hs1.toJson()))
                    val hs2 = when (val frame = receiveOuter(carrier, route)) {
                        is OuterFrame.Hs2 -> frame.frame
                        is OuterFrame.Reject -> throw HandshakeRejected(
                            frame.frame.code,
                            HSR_MESSAGES[frame.frame.code] ?: "电脑拒绝了连接（${frame.frame.code}）"
                        )
                        else -> throw ConnectionClosed("握手应答无效")
                    }
                    val keys = initiator.finish(hs2)
                    val sealer = FrameSealer(keys.send)
                    val opener = FrameOpener(keys.recv)
                    carrier.send(OuterFrames.encode(sealer.seal(Inner.hello(config.app, grant, ticket)).toJson()))

                    val first = when (val frame = receiveOuter(carrier, route)) {
                        is OuterFrame.Data -> opener.open(frame.frame)
                        is OuterFrame.Reject -> throw HandshakeRejected(
                            frame.frame.code,
                            HSR_MESSAGES[frame.frame.code] ?: "电脑拒绝了连接（${frame.frame.code}）"
                        )
                        else -> throw ConnectionClosed("握手后收到了无效的帧")
                    }
                    val message = parseInner(first)
                    when (message.str("t")) {
                        "welcome" -> {
                            val welcome = RcJson.decodeFromJsonElement(Welcome.serializer(), message)
                            if (welcome.host.id.isNotEmpty() && welcome.host.id != initiator.hostId) {
                                throw ConnectionClosed("电脑报告的主机 id 与配对时不一致")
                            }
                            RcConnection(carrier, sealer, opener, welcome, route, config, scope).also { it.start() }
                        }
                        "bye" -> throw HelloRefused(message.str("reason") ?: "电脑拒绝了这台手机")
                        else -> throw ConnectionClosed("电脑没有回应 hello")
                    }
                }
            } catch (error: TimeoutCancellationException) {
                carrier.close("handshake timeout")
                throw ConnectionClosed("握手超时", error)
            } catch (error: Throwable) {
                carrier.close("handshake failed")
                throw error
            }
        }

        private suspend fun receiveOuter(carrier: Carrier, route: Route): OuterFrame {
            val text = try {
                carrier.incoming.receive()
            } catch (error: ClosedReceiveChannelException) {
                throw ConnectionClosed("连接被对方关闭", error)
            }
            val frame = OuterFrames.parse(text)
            if (frame is OuterFrame.RelayError) {
                // Only the relay speaks this. On a LAN socket it is someone
                // pretending, and is treated as the violation it is.
                if (route !is Route.Relay) throw ConnectionClosed("局域网连接收到了中继错误帧")
                throw RelayRejected(frame.frame.code, RELAY_MESSAGES[frame.frame.code] ?: "中继拒绝了连接（${frame.frame.code}）")
            }
            return frame
        }

        internal fun parseInner(text: String): JsonObject = try {
            RcJson.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            throw ConnectionClosed("收到了无法解析的消息", error)
        }
    }

    /** `bye` in answer to `hello`: the grant or ticket was not accepted. */
    class HelloRefused(reason: String) : RcException("hello-refused", "电脑拒绝了这台手机：$reason")

    private fun start() {
        scope.launch { readLoop() }
        scope.launch { keepAlive() }
    }

    private suspend fun readLoop() {
        try {
            while (scope.isActive) {
                val text = try {
                    carrier.incoming.receive()
                } catch (error: ClosedReceiveChannelException) {
                    throw ConnectionClosed("连接已断开", error)
                }
                lastReceivedAt = now()
                val frame = OuterFrames.parse(text)
                val plaintext = when (frame) {
                    is OuterFrame.Data -> opener.open(frame.frame)
                    is OuterFrame.RelayError -> {
                        if (route !is Route.Relay) throw ConnectionClosed("局域网连接收到了中继错误帧")
                        throw RelayRejected(frame.frame.code, RELAY_MESSAGES[frame.frame.code] ?: "中继断开了连接（${frame.frame.code}）")
                    }
                    else -> throw ConnectionClosed("连接中收到了非数据帧")
                }
                dispatch(parseInner(plaintext))
            }
        } catch (error: RcException) {
            shutdown(error)
        } catch (error: CryptoError) {
            shutdown(ConnectionClosed("连接校验失败（${error.code}）：${error.message}", error))
        } catch (error: kotlinx.coroutines.CancellationException) {
            shutdown(ConnectionClosed("连接已关闭", error))
        } catch (error: Throwable) {
            shutdown(ConnectionClosed("连接出错：${error.message}", error))
        }
    }

    private suspend fun dispatch(message: JsonObject) {
        when (message.str("t")) {
            "res" -> onResponse(message)
            "ev" -> {
                val kind = message.str("k") ?: return
                _events.emit(RcEvent(kind, message["p"] ?: JsonObject(emptyMap())))
            }
            "ping" -> {
                val ts = (message["ts"] as? JsonPrimitive)?.longOrNull ?: 0
                sendInner(Inner.pong(ts))
            }
            "pong" -> Unit
            "bye" -> throw ConnectionClosed(message.str("reason")?.let { "电脑断开了连接：$it" } ?: "电脑断开了连接")
            // An unknown inner type from a newer host is ignored, not fatal.
            else -> Unit
        }
    }

    private suspend fun onResponse(message: JsonObject) {
        val id = message.str("id") ?: return
        val entry = stateLock.withLock { pending[id] } ?: return
        val partsTotal = (message["parts"] as? JsonPrimitive)?.intOrNull
        if (partsTotal != null) {
            val part = (message["part"] as? JsonPrimitive)?.intOrNull
            val data = message.str("d")
            val complete = stateLock.withLock {
                val buffer = entry.parts ?: arrayOfNulls<String>(partsTotal).also { entry.parts = it }
                when {
                    partsTotal <= 0 || buffer.size != partsTotal || part == null || part !in 0 until partsTotal || data == null -> {
                        failLocked(id, entry, RcException("bad-response", "分片响应格式错误"))
                        null
                    }
                    else -> {
                        entry.bytes += data.length
                        if (entry.bytes > Protocol.MAX_RESPONSE) {
                            failLocked(id, entry, RcException("too-large", "响应超过 64 MiB 上限"))
                            null
                        } else {
                            buffer[part] = data
                            if (buffer.all { it != null }) {
                                pending.remove(id)
                                buffer.joinToString("")
                            } else null
                        }
                    }
                }
            } ?: return
            val whole = try {
                RcJson.parseToJsonElement(complete).jsonObject
            } catch (error: Exception) {
                entry.result.completeExceptionally(RcException("bad-response", "分片响应无法解析"))
                return
            }
            resolve(entry, whole)
            return
        }
        stateLock.withLock { pending.remove(id) }
        resolve(entry, message)
    }

    private fun failLocked(id: String, entry: Pending, error: RcException) {
        pending.remove(id)
        entry.result.completeExceptionally(error)
    }

    private fun resolve(entry: Pending, body: JsonObject) {
        val ok = (body["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == true) {
            entry.result.complete(body["r"] ?: kotlinx.serialization.json.JsonNull)
        } else {
            val error = body["e"] as? JsonObject
            entry.result.completeExceptionally(
                RemoteCallError(error?.str("code") ?: "internal", error?.str("message") ?: "请求失败")
            )
        }
    }

    private suspend fun keepAlive() {
        while (scope.isActive) {
            delay(config.pingIntervalMs)
            val idle = now() - lastReceivedAt
            if (idle >= config.idleTimeoutMs) {
                shutdown(ConnectionClosed("连接无响应"))
                return
            }
            if (idle >= config.pingIntervalMs) {
                runCatching { sendInner(Inner.ping(now())) }
            }
        }
    }

    private suspend fun sendInner(text: String) {
        val size = text.encodeToByteArray().size
        if (size > Protocol.MAX_PLAINTEXT) throw RcException("too-large", "消息超过 1 MiB 上限")
        // Sealing and sending under one lock: the counter order is the send order.
        sendLock.withLock {
            closeError?.let { throw it }
            val frame = sealer.seal(text)
            carrier.send(OuterFrames.encode(frame.toJson()))
        }
    }

    /**
     * Send `req` and wait for its `res` (reassembled when in parts). Throws
     * [RemoteCallError] for `ok:false`, [ConnectionClosed] if the line drops.
     */
    suspend fun request(method: String, params: JsonElement? = null, timeoutMs: Long = config.requestTimeoutMs): JsonElement {
        val deferred = CompletableDeferred<JsonElement>()
        val id = stateLock.withLock {
            closeError?.let { throw it }
            val id = "q${nextId++}"
            pending[id] = Pending(deferred)
            id
        }
        try {
            sendInner(Inner.request(id, method, params))
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            throw RcException("timeout", "请求超时：$method")
        } finally {
            stateLock.withLock { pending.remove(id) }
        }
    }

    val isOpen: Boolean get() = closeError == null

    /** Say goodbye and close. */
    suspend fun close(reason: String = "client closed") {
        if (closeError != null) return
        runCatching { withTimeout(1_000) { sendInner(Inner.bye(reason)) } }
        shutdown(ConnectionClosed("已断开"))
    }

    private suspend fun shutdown(error: RcException) {
        if (closeError != null) return
        closeError = error
        withContext(NonCancellable) {
            val failed = stateLock.withLock { pending.values.toList().also { pending.clear() } }
            for (entry in failed) entry.result.completeExceptionally(error)
            runCatching { carrier.close(error.message ?: "") }
            closed.complete(error)
        }
        scope.cancel()
    }
}
