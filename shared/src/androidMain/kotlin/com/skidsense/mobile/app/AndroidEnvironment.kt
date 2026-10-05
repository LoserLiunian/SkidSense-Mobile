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
            // No `maxFrameSize`: Ktor's OkHttp session throws on any assignment
            // to it (`OkHttpWebsocketSession`, "Max frame size switch is not
            // supported in OkHttp engine"), and the plugin assigns it to every
            // session — so with it set, no carrier ever opened on Android while
            // iOS (Darwin supports it) worked. The bound is enforced where the
            // text arrives instead: `KtorCarrier` closes on a frame over
            // `Protocol.MAX_FRAME`, and the relay caps frames on its side.
            // Guarded by `AppEnvironmentCarrierTest`.
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
