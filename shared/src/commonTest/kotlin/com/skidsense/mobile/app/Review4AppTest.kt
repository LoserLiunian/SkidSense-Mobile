package com.skidsense.mobile.app

import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.transport.ClientState
import com.skidsense.mobile.transport.FakeHost
import com.skidsense.mobile.transport.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 4 (app layer): what the real three-way runs found in how the phone
 * follows a live turn and the session list across drops and reloads.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Review4AppTest {
    private val key = "echo:r4"

    /** The desktop's truth for one turn. */
    private inner class Turn {
        var seq = 5L
        var phase = "running"
        var asking = false
        var ended = false
        var title = "r4"
        /** What the desktop's index said about a live turn before round 4. */
        var rowRunState: String? = null

        fun snapshot(): JsonObject = buildJsonObject {
            put("taskId", "task-1"); put("agent", "echo"); put("workdir", "/w"); put("phase", phase)
            put("prompt", "do it"); put("text", "hello"); put("reasoning", "")
            putJsonArray("interactions") {
                if (asking) add(buildJsonObject {
                    put("id", "ask-1"); put("askedAt", 1)
                    putJsonObject("question") { put("kind", "permission"); put("title", "允许吗") }
                })
            }
            put("seq", seq)
        }

        fun row(): JsonObject = buildJsonObject {
            put("key", key); put("agent", "echo"); put("workdir", "/w"); put("title", title)
            put("runState", rowRunState ?: if (ended) "idle" else "running")
        }

        fun opened(): JsonObject = buildJsonObject {
            put("row", row())
            putJsonArray("turns") {
                if (ended) add(buildJsonObject {
                    put("taskId", "task-1"); put("prompt", "do it"); put("ok", true); put("stopReason", "complete")
                    put("snapshot", snapshot())
                })
            }
            put("live", if (ended) JsonNull else snapshot())
        }

        fun donePatch(): JsonObject {
            val from = seq
            seq += 1
            phase = "done"
            return buildJsonObject {
                put("sessionKey", key); put("taskId", "task-1"); put("seq", seq); put("fromSeq", from)
                putJsonObject("patch") { putJsonObject("fields") { put("phase", "done") } }
            }
        }
    }

    private class Rig(val app: TestApp, val host: FakeHost)

    private suspend fun TestScope.rig(
        turn: Turn,
        override: (suspend (String, JsonElement?) -> JsonElement?)? = null
    ): Rig {
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        host.handler = { method, params ->
            override?.invoke(method, params) ?: when (method) {
                "workspaces.list" -> JsonArray(emptyList())
                "sessions.list" -> JsonArray(listOf(turn.row()))
                "sessions.open" -> turn.opened()
                "turn.snapshot" -> if (turn.ended) JsonNull else turn.snapshot()
                else -> JsonPrimitive(true)
            }
        }
        val app = TestApp(backgroundScope)
        app.connectTo(host)
        app.controller.openSession(key)
        return Rig(app, host)
    }

    private suspend fun awaitReconnect(rig: Rig, before: Int) = withTimeout(120_000) {
        while (rig.host.connections <= before || !rig.app.controller.state.value.connected) delay(10)
    }

    /**
     * F11: an approval asked while the phone was away. Nothing re-read the
     * turn after the reconnect — the next patch would have shown the gap, but
     * a turn waiting on an answer publishes none — so the card never appeared
     * and the turn waited forever.
     */
    @Test
    fun aReconnectReReadsTheTurnAndShowsAQuestionAskedMeanwhile() = runTest {
        val turn = Turn()
        val rig = rig(turn)
        assertEquals("running", rig.app.controller.liveTurn.snapshot?.phase)
        val before = rig.host.connections
        rig.host.drop()
        turn.phase = "awaiting-input"; turn.asking = true; turn.seq = 9
        awaitReconnect(rig, before)
        withTimeout(60_000) { while (rig.app.controller.liveTurn.snapshot?.openInteraction == null) delay(10) }
        assertEquals("ask-1", rig.app.controller.liveTurn.snapshot?.openInteraction?.id)
    }

    /** F11: a turn that ended while the phone was away is shown ended, not waiting on an answer. */
    @Test
    fun aTurnThatEndedWhileAwayIsShownEndedAfterTheReconnect() = runTest {
        val turn = Turn()
        val rig = rig(turn)
        val before = rig.host.connections
        rig.host.drop()
        turn.phase = "done"; turn.ended = true
        awaitReconnect(rig, before)
        withTimeout(60_000) { while (rig.app.controller.liveTurn.snapshot?.running != false) delay(10) }
        assertEquals("done", rig.app.controller.liveTurn.snapshot?.phase)
    }

    /**
     * F1: the list said `idle` mid-turn; the heal re-fetched with
     * `sessions.open`, the turn's last patch arrived meanwhile, and the
     * older answer — computed before the turn ended — was painted over it.
     */
    @Test
    fun anOlderReReadDoesNotOverwriteANewerPatch() = runTest {
        val turn = Turn()
        val gate = CompletableDeferred<Unit>()
        var gated = false
        val rig = rig(turn) { method, _ ->
            if (method == "sessions.open" && gated) {
                val computed = turn.opened()
                gate.await()
                computed
            } else null
        }
        // Follow the turn from a base frame, as a phone that watched it start
        // does: its baseline then matches the desktop's, and the turn's last
        // patch applies directly rather than as a gap.
        turn.seq = 6
        rig.host.emit("session.patch", buildJsonObject {
            put("sessionKey", key); put("taskId", "task-1"); put("seq", 6); put("fromSeq", 0)
            put("base", turn.snapshot()); putJsonObject("patch") { }
        })
        withTimeout(60_000) { while (rig.app.controller.liveTurn.seq != 6L) delay(5) }
        turn.rowRunState = "idle"
        gated = true
        val mark = rig.host.calls.size
        rig.host.emit("sessions.changed", buildJsonObject { })
        // Whichever re-read the phone chose, the turn ends while it is out.
        withTimeout(60_000) {
            while (rig.host.calls.drop(mark).none { it.first == "sessions.open" || it.first == "turn.snapshot" }) delay(5)
        }
        delay(50)
        rig.host.emit("session.patch", turn.donePatch())
        turn.ended = true
        delay(50)
        gate.complete(Unit)
        delay(5_000)
        assertEquals("done", rig.app.controller.liveTurn.snapshot?.phase, "an older re-read painted over the turn's end")
    }

    /**
     * F1: a `sessions.changed` that arrived while a list reload was in flight
     * was dropped, and the list kept whatever that reload had read — a rename
     * made in between never showed.
     */
    @Test
    fun aChangeDuringAReloadIsNotLost() = runTest {
        val turn = Turn()
        turn.ended = true; turn.phase = "done"
        val hold = CompletableDeferred<Unit>()
        var holding = false
        val rig = rig(turn) { method, _ ->
            if (method == "sessions.list" && holding) {
                holding = false
                val computed = JsonArray(listOf(turn.row()))
                hold.await()
                computed
            } else null
        }
        holding = true
        rig.host.emit("sessions.changed", buildJsonObject { })
        delay(100)
        turn.title = "renamed"
        rig.host.emit("sessions.changed", buildJsonObject { })
        delay(100)
        hold.complete(Unit)
        withTimeout(60_000) { while (rig.app.controller.state.value.sessions.firstOrNull()?.title != "renamed") delay(10) }
        assertEquals("renamed", rig.app.controller.state.value.sessions.first().title)
    }

    /** A search running when the connection drops ends there, instead of spinning for ever. */
    @Test
    fun aSearchCutOffByADropEnds() = runTest {
        val turn = Turn()
        val rig = rig(turn)
        rig.app.controller.startSearch("/w", "needle")
        assertTrue(rig.app.controller.search.value?.fold?.state?.done == false)
        val before = rig.host.connections
        rig.host.drop()
        awaitReconnect(rig, before)
        withTimeout(60_000) { while (rig.app.controller.search.value?.fold?.state?.done != true) delay(10) }
        assertTrue(rig.app.controller.search.value?.fold?.state?.error != null)
    }

    /**
     * Over the budgeted relay an attachment goes in small chunks, so the
     * phone's other requests are not queued behind a third of a megabyte.
     */
    @Test
    fun attachmentsGoInSmallChunksOverTheRelay() = runTest {
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        host.maxChunk = 64 * 1024
        host.handler = { method, _ -> if (method == "workspaces.list" || method == "sessions.list") JsonArray(emptyList()) else JsonPrimitive(true) }
        val app = TestApp(backgroundScope)
        TestApp.writePaired(app.files, listOf(TestApp.pairedHost(host.hostId, host.hostStatic, "dev-1", base = app.backend.base)))
        app.carriers.relay = { host }
        app.controller.start()
        app.controller.connect(host.hostId)
        val connected = withTimeout(60_000) { app.controller.state.first { it.connected } }
        assertTrue((connected.connection as ClientState.Connected).route is Route.Relay)
        app.controller.attach("big.bin", "application/octet-stream", ByteArray(300 * 1024) { 7 }, null)
        assertEquals(300L * 1024, host.uploads.values.single().received)
    }
}
