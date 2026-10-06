package com.skidsense.mobile.transport

import com.skidsense.mobile.app.UploadManager
import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.rc.Protocol
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The upload contract, from the client side, against a host that implements the
 * desktop's own rules (`src/main/remote/uploads.ts`): a declared size, chunks at
 * exactly consecutive offsets, 384 KiB raw per chunk, 20 MiB per file, 8 open
 * at once, and a `turn.prompt` that consumes the ids.
 */
class UploadTest {
    private val hostStatic = Primitives.generateKeyPair()
    private val identity = Primitives.generateKeyPair()
    private val hostId = B64u.encode(Primitives.randomBytes(16))

    private class Harness(val client: RcClient, val host: FakeHost, val uploads: UploadManager)

    private suspend fun TestScope.setUp(
        configure: FakeHost.() -> Unit = {}
    ): Harness {
        val host = FakeHost(hostId, hostStatic, backgroundScope).apply(configure)
        host.handler = { method, _ -> JsonPrimitive(method) }
        val carriers = FakeCarriers(backgroundScope).apply { lan = { host } }
        val client = RcClient(
            HostEndpoint(hostId, hostStatic.pub, "dev-1", listOf("192.168.1.20"), 47290),
            identity,
            object : Credentials {
                override suspend fun grant(fresh: Boolean): String = "grant"
            },
            carriers,
            backgroundScope,
            ClientConfig(lanConnectTimeoutMs = 2_500, backoffBaseMs = 1_000, callWaitMs = 20_000)
        )
        client.start()
        withTimeout(30_000) { client.state.filterIsInstance<ClientState.Connected>().first() }
        val uploads = UploadManager(
            { method, params -> client.call(method, params) as? JsonObject },
            { client.connectionMarker }
        )
        return Harness(client, host, uploads)
    }

    private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun aSmallFileGoesInOneChunkAndIsConsumedByThePrompt() = runTest {
        val h = setUp()
        val upload = h.uploads.begin("note.txt", "text/plain", bytes(1000))
        assertTrue(upload.complete)
        assertEquals(1000, h.host.uploads[upload.id]?.received)
        assertEquals(B64u.encode(bytes(1000)), h.host.uploads[upload.id]?.bytes?.toString())
        assertEquals(listOf("upload.begin", "upload.chunk"), h.host.calls.map { it.first })

        val ids = h.uploads.take("claude:1").map { it.id }
        assertEquals(listOf(upload.id), ids)
        assertEquals(0, h.uploads.count, "taking the ids forgets them")
        h.client.stop()
    }

    /**
     * A prompt the desktop does not accept leaves the uploads there (spec §7),
     * so the client puts its copies back: a retry names the same ids and sends
     * none of the bytes again.
     */
    @Test
    fun takenUploadsGoBackWhenThePromptIsRefused() = runTest {
        val h = setUp()
        val upload = h.uploads.begin("keep.txt", "text/plain", bytes(10))
        val taken = h.uploads.take("claude:1")
        assertEquals(0, h.uploads.count)
        h.uploads.restore(taken)
        assertEquals(listOf(upload.id), h.uploads.take("claude:1").map { it.id }, "the same id, not a fresh upload")
        assertEquals(listOf("upload.begin", "upload.chunk"), h.host.calls.map { it.first }, "nothing was sent again")
        h.client.stop()
    }

    @Test
    fun aLargeFileIsSlicedAtTheChunkLimit() = runTest {
        val h = setUp()
        val size = Protocol.UPLOAD_CHUNK * 2 + 123
        val upload = h.uploads.begin("photo.jpg", "image/jpeg", bytes(size))
        assertTrue(upload.complete)
        assertEquals(size.toLong(), h.host.uploads[upload.id]?.received)
        val chunkCalls = h.host.calls.filter { it.first == "upload.chunk" }
        assertEquals(3, chunkCalls.size)
        // Every chunk but the last is exactly the limit; offsets are contiguous.
        val offsets = chunkCalls.map { ((it.second as JsonObject)["offset"] as JsonPrimitive).content.toInt() }
        assertEquals(listOf(0, Protocol.UPLOAD_CHUNK, Protocol.UPLOAD_CHUNK * 2), offsets)
        val sizes = chunkCalls.map { B64u.decode(((it.second as JsonObject)["data"] as JsonPrimitive).content).size }
        assertEquals(listOf(Protocol.UPLOAD_CHUNK, Protocol.UPLOAD_CHUNK, 123), sizes)
        h.client.stop()
    }

    @Test
    fun aHostThatDisagreesAboutTheOffsetKillsTheUpload() = runTest {
        val h = setUp()
        // The host reports a `received` that is not where we are: the next
        // offset cannot be repaired in place, so the upload must fail rather
        // than continue at a guessed offset.
        h.host.uploadReceivedOverride = 7
        val error = assertFailsWith<RcException> { h.uploads.begin("c.bin", null, bytes(500)) }
        assertEquals("upload-desync", error.code)
        assertTrue(h.host.uploads.isEmpty(), "an aborted upload is not left open on the host")
        h.client.stop()
    }

    @Test
    fun tooManyOpenUploadsAreRefused() = runTest {
        val h = setUp()
        repeat(8) { index -> h.uploads.begin("f$index.bin", null, bytes(16)) }
        val error = assertFailsWith<RcException> { h.uploads.begin("ninth.bin", null, bytes(16)) }
        assertEquals("too-many", error.code)
        h.client.stop()
    }

    @Test
    fun oversizeFilesAreRefusedBeforeAnythingIsSent() = runTest {
        val h = setUp()
        val error = assertFailsWith<RcException> {
            h.uploads.begin("huge.bin", null, ByteArray((Protocol.MAX_UPLOAD + 1).toInt()))
        }
        assertEquals("too-large", error.code)
        assertEquals(emptyList(), h.host.calls.map { it.first }, "nothing reaches the host")
        h.client.stop()
    }

    @Test
    fun aPromptNamingAnUnfinishedUploadFailsInsteadOfSendingWithoutIt() = runTest {
        val h = setUp()
        // A host that stops mid-transfer: the upload never completes.
        h.host.uploadReceivedOverride = 0
        val error = assertFailsWith<RcException> { h.uploads.begin("half.bin", null, bytes(Protocol.UPLOAD_CHUNK + 10)) }
        assertEquals("upload-desync", error.code)
        // The manager holds nothing unfinished: taking ids must not silently
        // hand over a partial upload.
        assertEquals(emptyList(), h.uploads.take("claude:1"))
        h.client.stop()
    }

    @Test
    fun takingIdsRefusesAnIncompleteUpload() = runTest {
        val h = setUp()
        // A host that reports less received than we sent: `begin` fails and
        // aborts, so nothing stays open to be referenced.
        val partial = com.skidsense.mobile.app.UploadManager(
            { method, _ ->
                when (method) {
                    "upload.begin" -> buildJsonObject { put("id", "u-partial") }
                    "upload.chunk" -> buildJsonObject { put("received", 1) }
                    else -> buildJsonObject { put("ok", true) }
                }
            },
            { null }
        )
        assertFailsWith<RcException> { partial.begin("partial.bin", null, bytes(100)) }
        assertEquals(0, partial.count)
        assertEquals(emptyList(), partial.take("claude:1"))
        h.client.stop()
    }

    @Test
    fun abortRemovesTheUploadOnBothSides() = runTest {
        val h = setUp()
        val upload = h.uploads.begin("x.bin", null, bytes(32))
        assertEquals(1, h.host.uploads.size)
        h.uploads.abort(upload.id)
        assertEquals(0, h.host.uploads.size)
        assertEquals(0, h.uploads.count)
        assertTrue(h.host.calls.any { it.first == "upload.abort" })

        // abortAll does the same for everything open.
        h.uploads.begin("y.bin", null, bytes(32))
        h.uploads.begin("z.bin", null, bytes(32))
        h.uploads.abortAll("claude:1")
        assertEquals(0, h.host.uploads.size)
        h.client.stop()
    }

    @Test
    fun aRealPromptCarriesTheUploadIds() = runTest {
        val h = setUp()
        val first = h.uploads.begin("one.txt", "text/plain", bytes(128))
        val second = h.uploads.begin("two.txt", "text/plain", bytes(Protocol.UPLOAD_CHUNK + 1))
        val ids = h.uploads.take("claude:1").map { it.id }
        assertEquals(listOf(first.id, second.id), ids)
        val result = h.client.call(
            "turn.prompt",
            buildJsonObject {
                put("sessionKey", "claude:1")
                put("prompt", "看这两个文件")
                put("uploads", kotlinx.serialization.json.JsonArray(ids.map { JsonPrimitive(it) }))
            }
        )
        assertEquals("turn.prompt", (result as JsonPrimitive).content)
        val sent = h.host.calls.last { it.first == "turn.prompt" }.second as JsonObject
        assertEquals(
            ids,
            (sent["uploads"] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitiveContent() }
        )
        // ...and the parameter really is `sessionKey`, not `key` (spec §7).
        assertTrue(sent.containsKey("sessionKey"))
        assertTrue(!sent.containsKey("key"))
        h.client.stop()
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as JsonPrimitive).content
}
