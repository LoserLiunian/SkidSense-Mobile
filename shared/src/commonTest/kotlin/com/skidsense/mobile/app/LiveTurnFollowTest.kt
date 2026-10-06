package com.skidsense.mobile.app

import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.transport.FakeHost
import com.skidsense.mobile.transport.RemoteCallError
import kotlinx.coroutines.delay
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The real controller following a live turn through the gap rule (spec §6.4,
 * C1), against a host whose `turn.snapshot` answers what the desktop's does:
 * the engine's snapshot, which already holds every patch published so far —
 * and, from a current desktop, the `seq` of the last of them.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveTurnFollowTest {
    private val key = "claude:follow"

    /** The host's truth for one turn, and the patches it publishes. */
    private inner class HostTurn(val withSeq: Boolean = true) {
        var seq = 5L
        var text = "hello "
        var phase = "running"
        var toolCalls = 0
        var ended = false
        var snapshotBehaviour: suspend () -> JsonElement = { snapshotJson() }

        fun snapshotJson(): JsonObject = buildJsonObject {
            put("taskId", "task-1"); put("agent", "claude"); put("workdir", "/w"); put("phase", phase)
            put("prompt", "do it"); put("text", text); put("reasoning", "")
            putJsonArray("toolCalls") {
                repeat(toolCalls) { n -> add(buildJsonObject { put("id", "t$n"); put("name", "Bash"); put("status", "done") }) }
            }
            // C1: the seq of the last patch this snapshot already contains.
            if (withSeq) put("seq", seq)
        }

        fun patch(delta: String): JsonObject {
            val from = seq
            seq += 1
            text += delta
            return buildJsonObject {
                put("sessionKey", key); put("taskId", "task-1"); put("seq", seq); put("fromSeq", from)
                putJsonObject("patch") { put("textDelta", delta) }
            }
        }

        /** What the desktop sends instead of a patch that does not fit one frame. */
        fun gapFrame(): JsonObject = buildJsonObject {
            put("sessionKey", key); put("taskId", "task-1"); put("seq", seq); put("fromSeq", -1)
            putJsonObject("patch") { }
        }

        fun row(): JsonObject = buildJsonObject {
            put("key", key); put("agent", "claude"); put("workdir", "/w"); put("title", "follow")
            put("runState", if (ended) "idle" else "running")
        }

        /** `sessions.open`: the live turn while it runs, its record once it has been written. */
        fun opened(): JsonObject = buildJsonObject {
            put("row", row())
            putJsonArray("turns") {
                if (ended) add(buildJsonObject {
                    put("taskId", "task-1"); put("prompt", "do it"); put("ok", phase == "done")
                    put("snapshot", snapshotJson())
                })
            }
            put("live", if (ended) JsonNull else snapshotJson())
        }
    }

    private class Rig(val app: TestApp, val host: FakeHost)

    private suspend fun TestScope.setUp(turn: HostTurn, sessionsList: (suspend () -> JsonElement)? = null): Rig {
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        host.handler = { method, _ ->
            when (method) {
                "workspaces.list" -> JsonArray(emptyList())
                "sessions.list" -> sessionsList?.invoke() ?: JsonArray(listOf(turn.row()))
                "sessions.open" -> turn.opened()
                "turn.snapshot" -> turn.snapshotBehaviour()
                else -> JsonPrimitive(true)
            }
        }
        val app = TestApp(backgroundScope)
        app.connectTo(host)
        app.controller.openSession(key)
        return Rig(app, host)
    }

    private suspend fun TestApp.awaitText(text: String, limitMs: Long = 120_000) =
        withTimeout(limitMs) { while (controller.liveTurn.snapshot?.text != text) delay(5) }

    /** In step: one gap, re-read with nothing in flight. */
    private suspend fun syncUp(rig: Rig, turn: HostTurn) {
        rig.host.emit("session.patch", turn.patch("w${turn.seq + 1} "))
        rig.app.awaitText(turn.text)
    }

    /**
     * Patches published while the re-read is on its way are already in the
     * snapshot it brings back. Applying them again on top used to duplicate
     * text (`hello w6 w7 w8 w7 w8 w9`).
     */
    @Test
    fun patchesInFlightDuringTheReReadAreNotAppliedTwice() = runTest(timeout = 5.minutes) {
        val turn = HostTurn()
        var host: FakeHost? = null
        var first = true
        turn.snapshotBehaviour = {
            if (first) {
                first = false
                host!!.emit("session.patch", turn.patch("w7 "))
                host!!.emit("session.patch", turn.patch("w8 "))
            }
            turn.snapshotJson()
        }
        val rig = setUp(turn)
        host = rig.host
        rig.host.emit("session.patch", turn.patch("w6 "))
        delay(1_000) // the re-read is in flight when w9 is published, like w7/w8
        rig.host.emit("session.patch", turn.patch("w9 "))
        delay(5_000)
        assertEquals("hello w6 w7 w8 w9 ", turn.text)
        assertEquals(turn.text, rig.app.controller.liveTurn.snapshot?.text)
    }

    /** A desktop from before C1 answers without `seq`: the gap frame's own seq is the baseline, as before. */
    @Test
    fun aSnapshotWithoutSeqFallsBackToTheGapFramesSeq() = runTest(timeout = 5.minutes) {
        val turn = HostTurn(withSeq = false)
        val rig = setUp(turn)
        syncUp(rig, turn)
        rig.host.emit("session.patch", turn.patch("next "))
        rig.app.awaitText(turn.text)
        assertEquals(turn.seq, rig.app.controller.liveTurn.seq)
    }

    /**
     * The last frame of a turn did not fit (the whole tool list at stop) and
     * became a gap; by the time the phone asks, the host has written the turn
     * and `turn.snapshot` is null. The phone used to accept the seq and keep
     * showing the turn as running, composer in 插话 mode, until reopened.
     */
    @Test
    fun aNullReReadEndsTheTurnFromSessionsOpen() = runTest(timeout = 5.minutes) {
        val turn = HostTurn()
        val rig = setUp(turn)
        syncUp(rig, turn)

        turn.text += "final words"
        turn.phase = "aborted"
        turn.toolCalls = 100
        turn.seq += 1
        turn.ended = true
        turn.snapshotBehaviour = { JsonNull }
        rig.host.emit("session.patch", turn.gapFrame())
        delay(5_000)

        val phone = rig.app.controller.liveTurn.snapshot
        assertFalse(phone?.running ?: false, "phase=${phone?.phase}")
        assertEquals("aborted", phone?.phase)
        assertTrue(phone!!.text.endsWith("final words"))
        assertEquals(listOf("task-1"), rig.app.controller.liveTurn.history.map { it.taskId }, "the finished turn is in the history it came from")
    }

    /**
     * A re-read that times out says nothing about the turn. It used to be
     * taken as null — the seq accepted, the snapshot kept — so the next patch
     * applied onto a base missing everything in between.
     */
    @Test
    fun aFailedReReadDoesNotAdvanceTheBaseline() = runTest(timeout = 5.minutes) {
        val turn = HostTurn()
        val rig = setUp(turn)
        syncUp(rig, turn)

        var slow = true
        turn.snapshotBehaviour = {
            if (slow) {
                slow = false
                delay(120_000)
            }
            turn.snapshotJson()
        }
        turn.text += "w7 "
        turn.toolCalls = 100
        turn.seq += 1
        rig.host.emit("session.patch", turn.gapFrame())
        delay(31_000) // past the request timeout
        rig.host.emit("session.patch", turn.patch("w8 "))
        delay(5_000)

        val phone = rig.app.controller.liveTurn.snapshot
        assertEquals(turn.text, phone?.text, "nothing applied onto a stale base")
        assertEquals(100, phone?.toolCalls?.size)
    }

    /**
     * The last frame was a gap and its re-read failed: no later frame will
     * come to trigger another. `sessions.changed` is the backstop — the row
     * says the turn is over, so the phone reopens the session.
     */
    @Test
    fun sessionsChangedHealsATurnStuckRunning() = runTest(timeout = 5.minutes) {
        val turn = HostTurn()
        val rig = setUp(turn)
        syncUp(rig, turn)

        turn.text += "done now"
        turn.phase = "done"
        turn.seq += 1
        turn.snapshotBehaviour = { throw RemoteCallError("internal", "暂时读不到") }
        rig.host.emit("session.patch", turn.gapFrame())
        delay(2_000)
        assertTrue(rig.app.controller.liveTurn.snapshot?.running == true, "precondition: the re-read failed")

        turn.ended = true
        rig.host.emit("sessions.changed", JsonObject(emptyMap()))
        delay(5_000)
        val phone = rig.app.controller.liveTurn.snapshot
        assertEquals("done", phone?.phase)
        assertEquals(turn.text, phone?.text)
    }

    /**
     * N09: the event collector used to make its own requests. While it waited
     * on one, events filled every buffer between it and the read loop, the
     * read loop blocked on `emit`, and the very response being waited for sat
     * unread until the 30 s timeout.
     */
    @Test
    fun aBurstOfEventsDuringTheReReadDoesNotStallIt() = runTest(timeout = 5.minutes) {
        val turn = HostTurn()
        lateinit var host: FakeHost
        val rig = setUp(turn)
        host = rig.host
        syncUp(rig, turn)
        turn.snapshotBehaviour = {
            repeat(1_100) { n -> host.emit("tui.data", buildJsonObject { put("key", "claude:other"); put("data", "line $n\r\n") }) }
            turn.snapshotJson()
        }
        turn.text += "w7 "
        turn.seq += 1
        val started = testScheduler.currentTime
        rig.host.emit("session.patch", turn.gapFrame())
        rig.app.awaitText(turn.text)
        val waited = testScheduler.currentTime - started
        assertTrue(waited < 1_000, "re-read took $waited ms (virtual)")
    }

    @Test
    fun aBurstOfEventsWhileTheSessionListLoadsDoesNotStallIt() = runTest(timeout = 5.minutes) {
        val turn = HostTurn()
        lateinit var host: FakeHost
        var burst = false
        val rig = setUp(turn) {
            if (burst) {
                burst = false
                repeat(1_100) { n -> host.emit("tui.data", buildJsonObject { put("key", "claude:other"); put("data", "line $n\r\n") }) }
            }
            JsonArray(listOf(turn.row().let { JsonObject(it + ("title" to JsonPrimitive("renamed"))) }))
        }
        host = rig.host
        burst = true
        val started = testScheduler.currentTime
        rig.host.emit("sessions.changed", JsonObject(emptyMap()))
        withTimeout(120_000) { while (rig.app.controller.state.value.sessions.firstOrNull()?.title != "renamed") delay(5) }
        val waited = testScheduler.currentTime - started
        assertTrue(waited < 1_000, "sessions.list took $waited ms (virtual)")
    }
}
