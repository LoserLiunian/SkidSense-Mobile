package com.skidsense.mobile.app

import android.content.Context
import com.skidsense.mobile.api.BackendClient
import com.skidsense.mobile.platform.AndroidDevicePlatform
import com.skidsense.mobile.platform.DevicePlatform
import com.skidsense.mobile.platform.platformHttpClient
import com.skidsense.mobile.transport.KtorCarrierFactory
import com.skidsense.mobile.transport.RcJson
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json

/**
 * Everything the Android app needs to start, built here rather than in the app
 * module so Ktor and serialization stay in one build file.
 *
 * Two HTTP clients on purpose: the API client has timeouts, the socket client
 * has none (a WebSocket lives for hours) and transport pings instead.
 */
class AndroidEnvironment(context: Context) {
    val platform: DevicePlatform = AndroidDevicePlatform(context.applicationContext)

    private val apiClient = platformHttpClient(forWebSockets = false) {
        install(ContentNegotiation) { json(RcJson) }
    }

    private val socketClient = platformHttpClient(forWebSockets = true) {
        install(WebSockets) {
            pingIntervalMillis = 20_000
            // OkHttp's `OkHttpWebsocketSession` rejects any `maxFrameSize`
            // assignment (Ktor 3.6.0, jvmMain `OkHttpWebsocketSession.kt:48` is
            // a hard `WebSocketException`), so setting it here made every
            // `webSocketSession` call fail on Android while the same welcome
            // page on iOS kept working. The literal was therefore omitted by
            // default review (#D1, androidDeviceTest `AppEnvironmentCarrierTest`).
            // Inbound size is still bounded twice: the backend caps a relay
            // frame at `FrameBytes * 8` (`hub_host.go:104`) and `KtorCarrier`
            // drops a frame whose decoded text exceeds `Protocol.MAX_FRAME`
            // before it ever reaches the connection, so nothing below relies
            // on the removed client-side cap.
        }
    }

    val backend: BackendClient = BackendClient(apiClient, platform.secrets)

    /** `ws_path` from `GET /config`, fetched once and remembered. */
    private var relayPath: String? = null

    val carriers = KtorCarrierFactory(
        client = socketClient,
        backendBase = { backend.session.value?.baseUrl ?: backend.baseUrl },
        relayPath = {
            relayPath ?: runCatching { backend.companionConfig().wsPath }.getOrNull()?.also { relayPath = it }
        },
        bearer = { backend.accessToken() }
    )

    fun close() {
        apiClient.close()
        socketClient.close()
    }
}
