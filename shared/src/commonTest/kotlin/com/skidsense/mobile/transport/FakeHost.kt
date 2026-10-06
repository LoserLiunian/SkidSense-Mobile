package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.FrameOpener
import com.skidsense.mobile.rc.FrameSealer
import com.skidsense.mobile.rc.HandshakeMode
import com.skidsense.mobile.rc.HsRejectFrame
import com.skidsense.mobile.rc.KeyPair
import com.skidsense.mobile.rc.OuterFrame
import com.skidsense.mobile.rc.OuterFrames
import com.skidsense.mobile.rc.RelayErrorFrame
import com.skidsense.mobile.model.SearchFileResult
import com.skidsense.mobile.rc.TestResponder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A duplex in-memory pipe: what one end sends, the other receives. */
class MemoryCarrier(
    private val outbox: Channel<String>,
    private val inbox: Channel<String>,
    override val label: String
) : Carrier {
    override val incoming: ReceiveChannel<String> = inbox
    var closed = false
        private set

    override suspend fun send(text: String) {
        if (closed) throw ConnectionClosed("carrier closed")
        outbox.send(text)
    }

    override suspend fun close(reason: String) {
        closed = true
        outbox.close()
        inbox.close()
    }

    companion object {
        fun pair(label: String): Pair<MemoryCarrier, MemoryCarrier> {
            val a = Channel<String>(Channel.UNLIMITED)
            val b = Channel<String>(Channel.UNLIMITED)
            return MemoryCarrier(a, b, label) to MemoryCarrier(b, a, "host")
        }
    }
}

/**
 * An in-process stand-in for the desktop, built from [TestResponder]: it
 * answers the handshake, checks `hello`, then serves requests from [handler]
 * and can push events, parted responses and deliberate protocol violations.
 */
class FakeHost(
    val hostId: String,
    val hostStatic: KeyPair,
    private val scope: CoroutineScope,
    /** The grant `hello` must carry for `connect`. */
    var acceptGrant: (String?) -> Boolean = { it != null },
    /** The ticket `hello` must carry for `enroll`. */
    var acceptTicket: (String?) -> Boolean = { it != null },
    /** Pairing codes accepted in `enroll` mode. */
    var pairingCode: ByteArray? = null,
    /** When set, every hs1 is answered with this `hsr`. */
    var rejectWith: String? = null,
    /** When set, only `connect` hs1s are refused with it: the host forgot the key but still takes an enrol. */
    var rejectConnectWith: String? = null,
    /** Responses whose JSON is longer than this are sent in parts. */
    var partSize: Int = 512 * 1024,
    /** Delay between parts: a budgeted relay draining a large response. */
    var partDelayMs: Long = 0,
    val scopes: List<String> = listOf("sessions", "prompt", "approve", "files", "git"),
    var handler: suspend (method: String, params: JsonElement?) -> JsonElement = { _, _ -> JsonPrimitive(true) }
) {
    val calls = mutableListOf<Pair<String, JsonElement?>>()

    // --- uploads, mirroring the desktop's UploadStash (src/main/remote/uploads.ts) ---

    class Upload(val name: String, val size: Long, val mimeType: String?, var received: Long = 0) {
        val bytes = StringBuilder()
        val complete: Boolean get() = received == size
    }

    /** Open uploads, by id. `begin` refuses past 8; a chunk must be exactly contiguous. */
    val uploads = LinkedHashMap<String, Upload>()

    /** Ids handed to `turn.prompt`. */
    val consumedUploads = mutableListOf<List<String>>()

    /**
     * When set, `upload.chunk` reports this as `received` instead of the truth.
     * It is how a host that disagrees about the offset is simulated.
     */
    var uploadReceivedOverride: Long? = null

    /** The declared size is refused past 20 MiB, and the chunk raw size is capped at 384 KiB. */
    var maxUpload: Long = 20L * 1024 * 1024
    var maxOpen: Int = 8
    var maxChunk: Int = 384 * 1024

    // --- terminal -------------------------------------------------------------

    /** Runs between accepting `tui.open` and answering it. */
    var beforeTuiOpenReply: (suspend () -> Unit)? = null

    /** Terminal writes received, in order, for the key that opened one. */
    val terminalInput = mutableListOf<String>()
    var terminalOpen = false

    // --- events a host emits on its own ---------------------------------------

    var searchFiles: List<Pair<String, String>> = emptyList()
    val helloGrants = mutableListOf<String?>()
    val enrolledKeys = mutableListOf<ByteArray>()
    var connections = 0
        private set

    private var live: Live? = null

    inner class Live(val carrier: Carrier, val sealer: FrameSealer, val opener: FrameOpener) {
        val lock = Mutex()
        suspend fun sendInner(text: String) = lock.withLock { carrier.send(OuterFrames.encode(sealer.seal(text).toJson())) }
    }

    fun serve(carrier: Carrier) {
        scope.launch { runConnection(carrier) }
    }

    private suspend fun runConnection(carrier: Carrier) {
        try {
            val hs1 = (OuterFrames.parse(carrier.incoming.receive()) as OuterFrame.Hs1).frame
            (rejectWith ?: rejectConnectWith?.takeIf { hs1.mode == "connect" })?.let {
                carrier.send(OuterFrames.encode(HsRejectFrame(it, "rejected").toJson()))
                carrier.close()
                return
            }
            val responder = try {
                TestResponder(hostId, hostStatic, hs1, if (hs1.mode == "enroll") pairingCode else null)
            } catch (error: com.skidsense.mobile.rc.CryptoError) {
                carrier.send(OuterFrames.encode(HsRejectFrame(error.code, error.message ?: "").toJson()))
                carrier.close()
                return
            }
            val (hs2, keys) = responder.complete()
            carrier.send(OuterFrames.encode(hs2.toJson()))
            val sealer = FrameSealer(keys.send)
            val opener = FrameOpener(keys.recv)
            val hello = RcJson.parseToJsonElement(opener.open((OuterFrames.parse(carrier.incoming.receive()) as OuterFrame.Data).frame)).jsonObject
            val ok = if (responder.mode == HandshakeMode.ENROLL) acceptTicket(hello.str("ticket")) else acceptGrant(hello.str("grant"))
            helloGrants += hello.str("grant") ?: hello.str("ticket")
            if (!ok) {
                carrier.send(OuterFrames.encode(sealer.seal(Inner.bye("凭证无效")).toJson()))
                carrier.close()
                return
            }
            if (responder.mode == HandshakeMode.ENROLL) enrolledKeys += responder.clientStatic
            val conn = Live(carrier, sealer, opener)
            live = conn
            connections += 1
            conn.sendInner(buildJsonObject {
                put("t", "welcome")
                put("v", 1)
                putJsonObject("host") { put("id", hostId); put("name", "书房的 Mac"); put("version", "1.2.0"); put("platform", "darwin") }
                putJsonObject("device") { put("id", "dev-1"); putJsonArray("scopes") { scopes.forEach { add(JsonPrimitive(it)) } } }
                putJsonObject("user") { put("id", 42); put("name", "liunian") }
                putJsonArray("methods") { add(JsonPrimitive("sessions.list")); add(JsonPrimitive("subscribe")) }
                put("future-field", "ignored")
            }.toString())

            try {
                serveRequests(carrier, conn, opener)
            } finally {
                // The desktop's stash belongs to one connection (connection.ts
                // clears it on close), so an id from a dead connection is gone.
                uploads.clear()
            }
        } catch (_: Throwable) {
            carrier.close()
        }
    }

    private suspend fun serveRequests(carrier: Carrier, conn: Live, opener: FrameOpener) {
        while (true) {
            val text = carrier.incoming.receiveCatching().getOrNull() ?: break
            val inner = RcJson.parseToJsonElement(opener.open((OuterFrames.parse(text) as OuterFrame.Data).frame)).jsonObject
            when (inner.str("t")) {
                "req" -> {
                    val id = inner.str("id")!!
                    val method = inner.str("m")!!
                    calls += method to inner["p"]
                    val params = inner["p"] as? kotlinx.serialization.json.JsonObject
                    if (method.startsWith("upload.") || method == "tui.open" || method == "tui.input" || method == "search.start") {
                        scope.launch { respondUploadOrTui(conn, id, method, params) }
                    } else {
                        scope.launch { respond(conn, id, method, inner["p"]) }
                    }
                }
                "ping" -> conn.sendInner(Inner.pong((inner["ts"] as JsonPrimitive).content.toLong()))
                "pong" -> pongs += 1
                "bye" -> {
                    carrier.close()
                    break
                }
            }
        }
    }

    var pongs = 0
        private set

    /**
     * The upload and terminal methods, with the desktop's own refusals: an
     * offset that is not exactly what has arrived so far is `bad-request`, an
     * oversized chunk is refused, and `tui.open` starts a stream.
     */
    private suspend fun respondUploadOrTui(
        conn: Live,
        id: String,
        method: String,
        params: kotlinx.serialization.json.JsonObject?
    ) {
        fun str(key: String): String? = (params?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun long(key: String): Long? = (params?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

        suspend fun fail(code: String, message: String) = conn.sendInner(
            buildJsonObject {
                put("t", "res"); put("id", id); put("ok", false)
                putJsonObject("e") { put("code", code); put("message", message) }
            }.toString()
        )

        suspend fun ok(value: JsonElement) = conn.sendInner(
            buildJsonObject { put("t", "res"); put("id", id); put("ok", true); put("r", value) }.toString()
        )

        when (method) {
            "upload.begin" -> {
                val size = long("size") ?: -1
                when {
                    uploads.size >= maxOpen -> fail("bad-request", "同时上传的附件太多")
                    size !in 0..maxUpload -> fail("bad-request", "单个附件不能超过 20 MiB")
                    uploads.values.sumOf { it.size } + size > maxUpload -> fail("bad-request", "附件合计不能超过 20 MiB")
                    else -> {
                        val uploadId = "u${uploads.size + 1}${B64u.encode(com.skidsense.mobile.rc.Primitives.randomBytes(6))}"
                        uploads[uploadId] = Upload(str("name") ?: "file", size, str("mimeType"))
                        ok(buildJsonObject { put("id", uploadId) })
                    }
                }
            }
            "upload.chunk" -> {
                val uploadId = str("id")
                val upload: Upload? = if (uploadId == null) null else uploads[uploadId]
                val offset = long("offset")
                if (upload == null) {
                    fail("bad-request", "上传不存在或已过期")
                } else if (offset != upload.received) {
                    fail("bad-request", "上传分块的偏移不连续")
                } else {
                    val data: ByteArray? = try {
                        B64u.decode(str("data"))
                    } catch (_: Exception) {
                        null
                    }
                    when {
                        data == null -> fail("bad-request", "分块内容无效")
                        data.size > maxChunk -> fail("bad-request", "分块过大")
                        upload.received + data.size > upload.size -> fail("bad-request", "上传的内容比声明的大")
                        else -> {
                            upload.received += data.size
                            upload.bytes.append(B64u.encode(data))
                            ok(buildJsonObject { put("received", uploadReceivedOverride ?: upload.received) })
                        }
                    }
                }
            }
            "upload.abort" -> {
                str("id")?.let { uploads.remove(it) }
                ok(JsonPrimitive(true))
            }
            "tui.open" -> {
                terminalOpen = true
                // The desktop's first PTY output can be on the wire before the reply is read.
                beforeTuiOpenReply?.invoke()
                ok(buildJsonObject { put("ok", true); put("attached", false); put("command", "claude") })
            }
            "tui.input" -> {
                if (!terminalOpen) {
                    fail("not-found", "没有打开这个终端")
                } else {
                    terminalInput += str("data") ?: ""
                    ok(JsonPrimitive(true))
                }
            }
            "search.start" -> {
                searchId = str("id")
                ok(JsonPrimitive(true))
            }
            else -> fail("unknown-method", method)
        }
    }

    /** The id the device chose for the search in flight, so progress can be addressed. */
    var searchId: String? = null

    /** Push one `search.progress` chunk set, then the done frame (as the desktop does). */
    suspend fun emitSearchProgress(files: List<SearchFileResult>, totalMatches: Int, id: String? = searchId) {
        val payload = buildJsonObject {
            put("id", id ?: "?")
            put("kind", "files")
            put("files", RcJson.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(SearchFileResult.serializer()), files
            ))
            put("done", false)
        }
        emit("search.progress", payload)
        emit(
            "search.progress",
            buildJsonObject {
                put("id", id ?: "?")
                put("kind", "done")
                put("totalMatches", totalMatches)
                put("truncated", false)
                put("elapsedMs", 12)
                put("fileCount", files.size)
            }
        )
    }

    /** Terminal output from the host, as `tui.data`. */
    suspend fun emitTerminal(key: String, data: String) {
        emit("tui.data", buildJsonObject { put("key", key); put("data", data) })
    }

    private suspend fun respond(conn: Live, id: String, method: String, params: JsonElement?) {
        val body: JsonObject = try {
            buildJsonObject { put("ok", true); put("r", handler(method, params)) }
        } catch (error: RemoteCallError) {
            buildJsonObject {
                put("ok", false)
                putJsonObject("e") { put("code", error.code); put("message", error.message ?: "") }
            }
        }
        val text = body.toString()
        if (text.length <= partSize) {
            conn.sendInner(JsonObject(mapOf("t" to JsonPrimitive("res"), "id" to JsonPrimitive(id)) + body).toString())
            return
        }
        val chunks = text.chunked(partSize)
        chunks.forEachIndexed { index, chunk ->
            if (index > 0 && partDelayMs > 0) kotlinx.coroutines.delay(partDelayMs)
            conn.sendInner(buildJsonObject {
                put("t", "res"); put("id", id); put("part", index); put("parts", chunks.size); put("d", chunk)
            }.toString())
        }
    }

    suspend fun emit(kind: String, payload: JsonElement) {
        live!!.sendInner(buildJsonObject { put("t", "ev"); put("k", kind); put("p", payload) }.toString())
    }

    suspend fun ping(ts: Long) {
        live!!.sendInner(Inner.ping(ts))
    }

    /** Seal a frame and throw it away: the next one arrives with a gap in the counter. */
    suspend fun skipFrame() {
        live!!.lock.withLock { live!!.sealer.seal("{\"t\":\"pong\",\"ts\":0}") }
    }

    /** Send a frame whose ciphertext was altered in flight. */
    suspend fun sendTampered(text: String) {
        val conn = live!!
        conn.lock.withLock {
            val frame = conn.sealer.seal(text)
            val bytes = B64u.decode(frame.c).also { it[0] = (it[0].toInt() xor 1).toByte() }
            conn.carrier.send(OuterFrames.encode(frame.copy(c = B64u.encode(bytes)).toJson()))
        }
    }

    suspend fun sendRaw(text: String) {
        live!!.carrier.send(text)
    }

    suspend fun relayError(code: String) {
        live!!.carrier.send("{\"t\":\"relay-error\",\"code\":\"$code\",\"message\":\"x\"}")
        live!!.carrier.close()
    }

    suspend fun drop() {
        live?.carrier?.close()
    }

    /** Say a sealed `bye` with a machine-readable [code] (spec §6.5), then close — how the desktop kicks a device. */
    suspend fun kick(code: String?, reason: String = "这台设备的权限已更改，请重新连接") {
        val conn = live ?: return
        conn.sendInner(buildJsonObject {
            put("t", "bye")
            put("reason", reason)
            if (code != null) put("code", code)
        }.toString())
        conn.carrier.close()
    }

    @Suppress("unused")
    private fun unusedRelayFrame() = RelayErrorFrame("", "")
}

/** Routes each carrier request to a fake host, or fails, per route. */
class FakeCarriers(private val scope: CoroutineScope) : CarrierFactory {
    val opened = mutableListOf<Route>()
    var lan: (Route.Lan) -> FakeHost? = { null }
    var relay: () -> FakeHost? = { null }
    /** LAN addresses that never answer (to exercise the per-address timeout). */
    var blackhole: Set<String> = emptySet()

    override suspend fun open(route: Route, target: CarrierTarget): Carrier {
        opened += route
        if (route is Route.Lan && route.address in blackhole) {
            kotlinx.coroutines.awaitCancellation()
        }
        val host = when (route) {
            is Route.Lan -> lan(route)
            Route.Relay -> relay()
        } ?: throw CarrierUnavailable("connection refused")
        val (client, server) = MemoryCarrier.pair(route.label)
        host.serve(server)
        return client
    }
}

internal fun JsonElement.arrayLen(): Int = jsonArray.size
