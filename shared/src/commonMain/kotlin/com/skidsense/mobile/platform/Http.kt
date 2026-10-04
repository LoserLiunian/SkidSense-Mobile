package com.skidsense.mobile.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * An HTTP client on the platform's engine: OkHttp on Android, Darwin
 * (NSURLSession) on iOS. [forWebSockets] clients get no request timeout (a
 * WebSocket lives for hours) but do get transport-level pings.
 */
expect fun platformHttpClient(forWebSockets: Boolean, block: HttpClientConfig<*>.() -> Unit = {}): HttpClient
