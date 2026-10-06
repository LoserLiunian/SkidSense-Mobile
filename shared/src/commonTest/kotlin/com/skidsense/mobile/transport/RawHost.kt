package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.DataFrame
import com.skidsense.mobile.rc.FrameCrypto
import com.skidsense.mobile.rc.FrameOpener
import com.skidsense.mobile.rc.HandshakeMode
import com.skidsense.mobile.rc.Initiator
import com.skidsense.mobile.rc.KeyPair
import com.skidsense.mobile.rc.OuterFrame
import com.skidsense.mobile.rc.OuterFrames
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.rc.TestResponder
import com.skidsense.mobile.rc.utf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A host that sends exactly what a test tells it to. Unlike [FakeHost]
 * it seals by hand (own counter), so a test can put any inner message on the
 * wire — including one a conforming sealer would refuse.
 */
class RawHost(val hostId: String, val hostStatic: KeyPair) {
    val requests = Channel<JsonObject>(Channel.UNLIMITED)
    private lateinit var carrier: Carrier
    private lateinit var sendKey: ByteArray
    private lateinit var opener: FrameOpener
    private var counter = 0L
    private val lock = Mutex()

    suspend fun serve(carrier: Carrier) {
        this.carrier = carrier
        val hs1 = (OuterFrames.parse(carrier.incoming.receive()) as OuterFrame.Hs1).frame
        val responder = TestResponder(hostId, hostStatic, hs1, null)
        val (hs2, keys) = responder.complete()
        carrier.send(OuterFrames.encode(hs2.toJson()))
        sendKey = keys.send
        opener = FrameOpener(keys.recv)
        opener.open((OuterFrames.parse(carrier.incoming.receive()) as OuterFrame.Data).frame) // hello
        sendInner(buildJsonObject {
            put("t", "welcome"); put("v", 1)
            putJsonObject("host") { put("id", hostId); put("name", "raw") }
            putJsonObject("device") { put("id", "dev-1"); putJsonArray("scopes") { add(JsonPrimitive("sessions")) } }
            putJsonArray("methods") { }
        }.toString())
        while (true) {
            val text = carrier.incoming.receiveCatching().getOrNull() ?: break
            val inner = RcJson.parseToJsonElement(opener.open((OuterFrames.parse(text) as OuterFrame.Data).frame)).jsonObject
            if (inner.str("t") == "req") requests.send(inner)
        }
    }

    /** Seal [text] under the next counter value without any size check, and send it. */
    suspend fun sendInner(text: String) = lock.withLock {
        val n = counter++
        val sealed = Primitives.seal(sendKey, FrameCrypto.nonce(n), FrameCrypto.aad(n), utf8(text))
        carrier.send(OuterFrames.encode(DataFrame(n, B64u.encode(sealed)).toJson()))
    }

    companion object {
        /** A live phone-side connection to a fresh raw host over an in-memory pipe. */
        suspend fun connect(
            scope: CoroutineScope,
            config: ConnectionConfig = ConnectionConfig()
        ): Pair<RcConnection, RawHost> {
            val hostStatic = Primitives.generateKeyPair()
            val hostId = B64u.encode(Primitives.randomBytes(16))
            val host = RawHost(hostId, hostStatic)
            val (client, server) = MemoryCarrier.pair("局域网 192.168.1.20")
            scope.launch { runCatching { host.serve(server) } }
            val initiator = Initiator(HandshakeMode.CONNECT, hostId, hostStatic.pub, Primitives.generateKeyPair())
            val connection = RcConnection.establish(client, Route.Lan("192.168.1.20", 47290), initiator, "grant", null, config, scope)
            return connection to host
        }
    }
}
