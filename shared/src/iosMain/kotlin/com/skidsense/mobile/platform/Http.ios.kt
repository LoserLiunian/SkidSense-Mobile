package com.skidsense.mobile.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

actual fun platformHttpClient(forWebSockets: Boolean, block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        engine {
            configureRequest {
                setTimeoutInterval(if (forWebSockets) 0.0 else 20.0)
            }
        }
        block()
    }
