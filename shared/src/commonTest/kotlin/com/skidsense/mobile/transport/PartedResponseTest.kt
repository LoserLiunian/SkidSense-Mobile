package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Parted responses (spec §6.3, C3): `part` and `parts` are JSON integers,
 * `parts` is in 1..4096, and a slice that repeats, moves `parts`, or falls
 * outside it fails the request with `bad-response` — without closing a
 * connection whose host has already authenticated.
 */
class PartedResponseTest {
    private fun part(id: String, part: Any?, parts: Any?, d: Any?): String = buildJsonObject {
        put("t", "res"); put("id", id)
        when (part) { null -> Unit; is Number -> put("part", part); is String -> put("part", part) }
        when (parts) { null -> Unit; is Number -> put("parts", parts); is String -> put("parts", parts) }
        when (d) { null -> Unit; is Number -> put("d", d); is String -> put("d", d) }
    }.toString()

    /** Issue one request and let [answer] reply to it; returns `ok:<r>` or the error code. */
    private suspend fun CoroutineScope.outcome(connection: RcConnection, host: RawHost, answer: suspend (id: String) -> Unit): String {
        val call = async { runCatching { connection.request("fs.read", null, timeoutMs = 5_000) } }
        val req = host.requests.receive()
        answer(req.str("id")!!)
        return call.await().fold(
            onSuccess = { "ok:" + (it as? JsonPrimitive)?.content },
            onFailure = { (it as? RcException)?.code ?: it::class.simpleName.orEmpty() }
        )
    }

    private val body = """{"ok":true,"r":"AB"}"""
    private val a = body.substring(0, 10)
    private val b = body.substring(10)

    @Test
    fun wellFormedSlicesReassembleInAnyOrder() = runTest(timeout = 5.minutes) {
        val (connection, host) = RawHost.connect(backgroundScope)
        assertEquals("ok:AB", outcome(connection, host) { id -> host.sendInner(part(id, 0, 2, a)); host.sendInner(part(id, 1, 2, b)) })
        assertEquals("ok:AB", outcome(connection, host) { id -> host.sendInner(part(id, 1, 2, b)); host.sendInner(part(id, 0, 2, a)) })
        assertEquals("ok:AB", outcome(connection, host) { id -> host.sendInner(part(id, 0, 1, body)) })
    }

    @Test
    fun indicesAndCountsMustBeJsonIntegers() = runTest(timeout = 5.minutes) {
        val (connection, host) = RawHost.connect(backgroundScope)
        // A string that reads as a number used to pass: `intOrNull` does not
        // tell `"0"` from `0`.
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, "0", 2, a)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 0, "2", a)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 1.5, 2, a)) })
        // Past Int, `parts` used to read as absent, so the frame was taken for an
        // ordinary response and failed as `internal`.
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 0, 1L shl 40, a)) })
        assertTrue(connection.isOpen, "a malformed slice fails the request, not the connection")
    }

    @Test
    fun aRepeatedSliceIsRefusedRatherThanOverwritten() = runTest(timeout = 5.minutes) {
        val (connection, host) = RawHost.connect(backgroundScope)
        val result = outcome(connection, host) { id ->
            host.sendInner(part(id, 0, 2, "{\"ok\":fals"))
            host.sendInner(part(id, 0, 2, a))
            host.sendInner(part(id, 1, 2, b))
        }
        assertEquals("bad-response", result)
    }

    @Test
    fun countsAndBoundsAreChecked() = runTest(timeout = 5.minutes) {
        val (connection, host) = RawHost.connect(backgroundScope)
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, -1, 2, a)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 2, 2, a)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, null, 2, a)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 0, 0, a)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 0, 2, null)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 0, 2, 7)) })
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 0, 2, a)); host.sendInner(part(id, 1, 3, b)) })
        assertTrue(connection.isOpen)
    }

    @Test
    fun theCapIsFourThousandAndNinetySixSlices() = runTest(timeout = 5.minutes) {
        val (connection, host) = RawHost.connect(backgroundScope)
        val atCap = """{"ok":true,"r":"${"z".repeat(Protocol.MAX_RESPONSE_PARTS - 18)}"}"""
        val result = outcome(connection, host) { id ->
            for (i in 0 until Protocol.MAX_RESPONSE_PARTS) host.sendInner(part(id, i, Protocol.MAX_RESPONSE_PARTS, atCap.substring(i, i + 1)))
        }
        assertEquals("ok:" + "z".repeat(Protocol.MAX_RESPONSE_PARTS - 18), result)
        assertEquals("bad-response", outcome(connection, host) { id -> host.sendInner(part(id, 0, Protocol.MAX_RESPONSE_PARTS + 1, "{")) })
    }
}
