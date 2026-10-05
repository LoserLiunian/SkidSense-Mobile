package com.skidsense.mobile

import androidx.compose.runtime.Composable
import com.skidsense.mobile.api.BackendClient
import com.skidsense.mobile.app.SkidSenseApp
import com.skidsense.mobile.platform.IosDevicePlatform
import com.skidsense.mobile.platform.platformHttpClient
import com.skidsense.mobile.transport.KtorCarrierFactory
import com.skidsense.mobile.transport.RcJson
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json

/**
 * The iOS composition root, the counterpart of `AndroidEnvironment`.
 *
 * TODO(iOS): the device platform below still uses in-memory stores, so nothing
 * survives a relaunch, and the biometric gate is a stub. Once the Keychain and
 * a file store are in place — and a QR scanner — this can be handed to
 * `iosApp/iosApp/ContentView.swift` unchanged, since every screen is common.
 */
class IosEnvironment {
    private val platform = IosDevicePlatform()

    private val apiClient = platformHttpClient(forWebSockets = false) {
        install(ContentNegotiation) { json(RcJson) }
    }

    private val socketClient = platformHttpClient(forWebSockets = true) {
        install(WebSockets) { maxFrameSize = 2L * 1024 * 1024 }
    }

    private val backend = BackendClient(apiClient, platform.secrets)

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

    @Composable
    operator fun invoke() {
        App(
            backend = backend,
            carriers = carriers,
            platform = platform,
            biometrics = com.skidsense.mobile.platform.iosBiometricGate()
        )
    }
}
