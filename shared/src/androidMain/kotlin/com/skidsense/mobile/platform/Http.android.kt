package com.skidsense.mobile.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Strips any `Origin` header, always.
 *
 * Both carriers refuse a request that carries one (spec §10, §10.4) — the LAN
 * listener 403s before reading a byte of protocol — and a browser is the only
 * thing that legitimately sends one. OkHttp does not add it for a WebSocket,
 * but an engine change or a plugin that does must not be able to break
 * connectivity in a way that only shows up on a real network, so the header
 * is removed here unconditionally.
 */
private object NoOriginInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Origin") == null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().removeHeader("Origin").build())
    }
}

actual fun platformHttpClient(forWebSockets: Boolean, block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                addInterceptor(NoOriginInterceptor)
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(!forWebSockets)
                if (forWebSockets) {
                    // The host pings every 30 s and drops after 120 s idle
                    // (spec §10.4); our own pings keep NAT bindings warm too.
                    pingInterval(20, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.MILLISECONDS)
                } else {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(20, TimeUnit.SECONDS)
                    writeTimeout(20, TimeUnit.SECONDS)
                }
            }
        }
        block()
    }
