package com.skidsense.mobile.platform

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.EOFException
import java.net.Socket
import javax.net.SocketFactory

/**
 * The guard OkHttp cannot be asked for directly (S30).
 *
 * OkHttp's own WebSocket reader buffers a whole message — and, with the
 * permessage-deflate it offers by default, its whole *inflated* size — before
 * the application sees a byte, so the `MAX_FRAME` check KtorCarrier applies
 * at `readText()` was after the fact: a hostile peer at a QR-listed LAN
 * address (pre-authentication, by design) could hold the app's heap hostage
 * with one frame, or a compressed bomb with a small one. Ktor's
 * `maxFrameSize` option does not help either — the OkHttp engine throws on
 * any assignment to it, which is why D1 removed it.
 *
 * Two pieces, each at the layer that owns the problem:
 *
 * - [NoDeflateInterceptor] strips `Sec-WebSocket-Extensions` from the upgrade
 *   *response*, so RealWebSocket never turns inflation on (it is parsed from
 *   the response headers after the network interceptors ran). OkHttp still
 *   *offers* the extension in its request, but with the answer erased no
 *   context is negotiated; a server that compresses anyway yields garbage
 *   frames and a loud failure, not a heap bomb.
 * - [CappedSocketFactory] counts bytes at the socket and kills the connection
 *   once one inbound span (between our writes) passes the cap, so a hostile
 *   first message cannot grow the heap by its length — the failure surfaces as
 *   a dead route, and the route walk moves on, exactly like an honest timeout.
 *
 * The after-the-fact check in KtorCarrier stays as the seatbelt: these two
 * are what make the cap true *before* the bytes land.
 */
object OkHttpGuards {
    /** Refuse a server's permessage-deflate by hiding its answer from the engine. */
    object NoDeflateInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (response.header("Sec-WebSocket-Extensions") == null) return response
            val headers = response.headers.newBuilder().removeAll("Sec-WebSocket-Extensions").build()
            return response.newBuilder().headers(headers).build()
        }
    }
}

/** Byte cap per inbound span; the socket is closed once it is crossed. */
class CappedSocketFactory(
    private val delegate: SocketFactory,
    private val maxBytes: Long
) : SocketFactory() {
    override fun createSocket(): Socket = CappedSocket(delegate.createSocket(), maxBytes)
    override fun createSocket(host: String?, port: Int): Socket = CappedSocket(delegate.createSocket(host, port), maxBytes)
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
        CappedSocket(delegate.createSocket(host, port, localHost, localPort), maxBytes)
    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = CappedSocket(delegate.createSocket(host, port), maxBytes)
    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
        CappedSocket(delegate.createSocket(address, port, localAddress, localPort), maxBytes)
}

/**
 * The byte-counting socket. The count resets on any outbound write — our
 * handshake, our `hello`, our pings — so the cap applies to one reply, not
 * to the lifetime of an hours-long connection.
 */
private class CappedSocket(
    private val delegate: Socket,
    private val maxBytes: Long
) : Socket() {
    private val readSoFar = java.util.concurrent.atomic.AtomicLong(0)

    override fun getInputStream(): java.io.InputStream {
        val upstream = delegate.getInputStream()
        return object : java.io.InputStream() {
            private fun counted(n: Int): Int {
                if (n <= 0) return n
                if (readSoFar.addAndGet(n.toLong()) > maxBytes) {
                    runCatching { delegate.close() }
                    throw EOFException("frame over the cap")
                }
                return n
            }

            override fun read(): Int {
                val b = upstream.read()
                if (b < 0) return -1
                counted(1)
                return b
            }

            override fun read(b: ByteArray): Int = counted(upstream.read(b))
            override fun read(b: ByteArray, off: Int, len: Int): Int = counted(upstream.read(b, off, len))
            override fun close() = upstream.close()
            override fun available(): Int = upstream.available()
            override fun skip(n: Long): Long = upstream.skip(n)
        }
    }

    override fun getOutputStream(): java.io.OutputStream {
        val upstream = delegate.getOutputStream()
        return object : java.io.OutputStream() {
            private fun sent() {
                readSoFar.set(0)
            }

            override fun write(b: Int) { sent(); upstream.write(b) }
            override fun write(b: ByteArray) { sent(); upstream.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { sent(); upstream.write(b, off, len) }
            override fun flush() = upstream.flush()
            override fun close() = upstream.close()
        }
    }

    // The rest is plumbing through to the real socket.
    override fun connect(endpoint: java.net.SocketAddress?) = delegate.connect(endpoint)
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun bind(bindpoint: java.net.SocketAddress?) = delegate.bind(bindpoint)
    override fun getInetAddress() = delegate.inetAddress
    override fun getLocalAddress() = delegate.localAddress
    override fun getPort() = delegate.port
    override fun getLocalPort() = delegate.localPort
    override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
    override fun getLocalSocketAddress() = delegate.localSocketAddress
    override fun getChannel() = delegate.channel
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = delegate.soLinger
    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { delegate.setOOBInline(on) }
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun close() = delegate.close()
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun toString(): String = delegate.toString()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
        delegate.setPerformancePreferences(connectionTime, latency, bandwidth)
}
