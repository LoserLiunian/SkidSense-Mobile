package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.Protocol
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * A WebSocket carrier on Ktor. Text frames only; a binary frame or a frame
 * over `MAX_FRAME` closes the carrier. No `Origin` header is sent — both the
 * LAN listener and the relay refuse any request that carries one.
 */
class KtorCarrier(
    private val session: DefaultClientWebSocketSession,
    override val label: String
) : Carrier {
    private val inbox = Channel<String>(capacity = 256)
    override val incoming: ReceiveChannel<String> = inbox

    init {
        session.launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            if (text.length > Protocol.MAX_FRAME) {
                                session.close(CloseReason(CloseReason.Codes.TOO_BIG, "frame too large"))
                                break
                            }
                            inbox.send(text)
                        }
                        is Frame.Binary -> {
                            session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "text frames only"))
                            break
                        }
                        else -> Unit
                    }
                }
            } catch (_: CancellationException) {
            } catch (_: Throwable) {
            } finally {
                inbox.close()
            }
        }
    }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close(reason: String) {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason.take(120))) }
        inbox.close()
    }
}

/**
 * Opens LAN (`ws://<addr>:<port>/rc/1`, spec §10.4) and relay
 * (`wss://<backend>/api/companion/ws?role=device&…`, spec §10) carriers.
 */
class KtorCarrierFactory(
    private val client: HttpClient,
    /** The backend base URL (`https://ai.surise.cn`). */
    private val backendBase: () -> String?,
    /** `ws_path` from `GET /config`; may fetch it the first time. */
    private val relayPath: suspend () -> String?,
    /** A current bearer for the relay. */
    private val bearer: suspend () -> String?
) : CarrierFactory {
    override suspend fun open(route: Route, target: CarrierTarget): Carrier {
        val session = try {
            when (route) {
                is Route.Lan -> client.webSocketSession(lanUrl(route))
                Route.Relay -> {
                    val token = bearer() ?: throw CarrierUnavailable("未登录，无法使用中继")
                    val url = relayUrl(target, relayPath()) ?: throw CarrierUnavailable("没有中继地址")
                    client.webSocketSession(url) { header(HttpHeaders.Authorization, "Bearer $token") }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: CarrierUnavailable) {
            throw error
        } catch (error: Throwable) {
            throw CarrierUnavailable(error.message ?: "无法连接", error)
        }
        return KtorCarrier(session, route.label)
    }

    internal fun lanUrl(route: Route.Lan): String {
        val host = if (route.address.contains(':') && !route.address.startsWith("[")) "[${route.address}]" else route.address
        return "ws://$host:${route.port}${Protocol.LAN_PATH}"
    }

    internal fun relayUrl(target: CarrierTarget, configuredPath: String? = null): String? {
        val base = backendBase() ?: return null
        val deviceId = target.deviceId ?: return null
        val builder = URLBuilder().takeFrom(base.trimEnd('/'))
        builder.protocol = if (builder.protocol == URLProtocol.HTTP) URLProtocol.WS else URLProtocol.WSS
        val path = configuredPath?.takeIf { it.isNotBlank() } ?: ("/" + Protocol.RELAY_PATH)
        builder.encodedPath = (builder.encodedPath.trimEnd('/') + "/" + path.trimStart('/'))
        builder.parameters.append("role", "device")
        builder.parameters.append("host_id", target.hostId)
        builder.parameters.append("device_id", deviceId)
        return builder.buildString()
    }
}
