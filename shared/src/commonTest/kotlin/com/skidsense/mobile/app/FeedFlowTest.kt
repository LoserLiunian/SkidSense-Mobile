package com.skidsense.mobile.app

import com.skidsense.mobile.model.SearchFileResult
import com.skidsense.mobile.model.SearchMatch
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.transport.FakeHost
import com.skidsense.mobile.transport.RemoteCallError
import com.skidsense.mobile.ui.TerminalSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone-side followings that used to go silently stale: a terminal's first
 * bytes (N04), a search's results (N02), the composer's attachment chips
 * (N06), and a prompt whose answer never arrived (S28).
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class FeedFlowTest {
    private class Rig(val app: TestApp, val host: FakeHost)

    private suspend fun TestScope.setUp(): Rig {
        val host = FakeHost(TestApp.newHostId(), Primitives.generateKeyPair(), backgroundScope)
        host.handler = { method, _ ->
            when (method) {
                "workspaces.list" -> JsonArray(emptyList())
                "sessions.list" -> JsonArray(emptyList())
                else -> JsonPrimitive(true)
            }
        }
        val app = TestApp(backgroundScope)
        app.connectTo(host)
        return Rig(app, host)
    }

    private class RecordingSink : TerminalSink {
        val data = StringBuilder()
        val exits = mutableListOf<Triple<Int, String, Int>>()
        override fun onData(key: String, data: String) {
            this.data.append(data)
        }

        override fun onExit(key: String, code: Int, reason: String, tail: String) {
            exits += Triple(code, reason, tail.length)
        }
    }

    /**
     * The desktop pushes the CLI's banner milliseconds after `tui.open`, before
     * any screen could have mounted: it used to drop on the floor
     * (`terminalSink?.onData` with a null sink). The controller holds it until
     * a sink for that key registers, then hands it over.
     */
    @Test
    fun theFirstPtyBytesSurviveUntilTheSinkRegisters() = runTest {
        val rig = setUp()
        rig.host.beforeTuiOpenReply = { rig.host.emitTerminal("claude:1", "banner\r\n$ ") }
        rig.app.controller.openTerminal("claude:1", 80, 24)
        delay(1_000)
        // No sink yet: nothing was delivered anywhere, but nothing was lost either.
        assertTrue(rig.app.controller.bufferedTerminalData("claude:1").isNotEmpty())

        val sink = RecordingSink()
        rig.app.controller.attachTerminal("claude:1", sink)
        assertTrue(sink.data.toString().contains("banner"), "the pre-registration bytes arrive with the sink")
        assertEquals("", rig.app.controller.bufferedTerminalData("claude:1"))

        rig.host.emit("tui.exit", buildJsonObject {
            put("key", "claude:1"); put("code", 0); put("signal", 9); put("tail", "last words")
        })
        delay(1_000)
        assertEquals(listOf(Triple(0, "被信号 9 终止", 10)), sink.exits, "a desktop exit without `reason` is rebuilt from signal (C2)")
        rig.app.controller.disconnect()
    }

    /**
     * The `search.progress` fold is a mutable object; the flow the UI collects
     * used to be reassigned that *same object*, which its identity compare
     * drops — the results stayed invisible until some unrelated recomposition.
     */
    @Test
    fun searchResultsAreEmittedAsTheyArrive() = runTest {
        val rig = setUp()
        val seen = mutableListOf<Int>()
        backgroundScope.launch {
            rig.app.controller.search.collect { view ->
                if (view != null) seen += view.fold.state.files.sumOf { it.matches.size }
            }
        }

        val id = rig.app.controller.startSearch("/w", "find-me-token")
        // The desktop answers search.start before streaming progress; wait for
        // the host to learn the device's chosen search id first.
        withTimeout(60_000) { while (rig.host.searchId == null) delay(5) }
        rig.host.emitSearchProgress(
            listOf(SearchFileResult("notes.md", listOf(SearchMatch(2, 1, "find-me-token")))),
            totalMatches = 1
        )
        withTimeout(60_000) {
            while (rig.app.controller.search.value?.fold?.state?.done != true) delay(5)
        }
        assertTrue(seen.any { it > 0 }, "the files frame's contents were visible to the collector, got $seen")
        rig.app.controller.disconnect()
    }

    /** Attachment chips are a flow: attaching and removing recomposes (N06). */
    @Test
    fun draftsAreObservable() = runTest {
        val rig = setUp()
        val emissions = mutableListOf<List<String>>()
        backgroundScope.launch {
            rig.app.controller.uploads.draftFlow.collect { drafts -> emissions += drafts.map { it.name } }
        }
        delay(100)
        rig.app.controller.attach("attach-A.txt", "text/plain", "hello".encodeToByteArray(), "claude:1")
        withTimeout(60_000) { while (emissions.none { it.contains("attach-A.txt") }) delay(5) }
        rig.app.controller.detach(rig.app.controller.uploads.drafts.first { it.name == "attach-A.txt" }.id)
        withTimeout(60_000) { while (emissions.last().isNotEmpty()) delay(5) }
        assertTrue(emissions.any { it == listOf("attach-A.txt") }, "the chip appeared")
        assertEquals(emptyList(), emissions.last(), "and it went when removed")
        rig.app.controller.disconnect()
    }

    /**
     * `turn.prompt` answered with a definite refusal keeps the attachments
     * (spec §7); one that *died without an answer* may already be running —
     * the attachments drop out instead of being replayed onto the next
     * connection's stash, where their ids no longer exist (S28).
     */
    @Test
    fun aRefusedPromptRestoresAndALostAnswerDoesNot() = runTest {
        val rig = setUp()

        // Definite refusal on the same connection.
        rig.host.handler = { method, _ ->
            when (method) {
                "workspaces.list" -> JsonArray(emptyList())
                "sessions.list" -> JsonArray(emptyList())
                "turn.prompt" -> throw RemoteCallError("bad-request", "不批准")
                else -> JsonPrimitive(true)
            }
        }
        rig.app.controller.attach("one.txt", "text/plain", "1".encodeToByteArray(), "claude:1")
        val refused = rig.app.controller.prompt("claude:1", "hi", null, null, null)
        assertEquals(false, refused.ok)
        assertEquals(listOf("one.txt"), rig.app.controller.uploads.drafts.map { it.name }, "a refusal restores (spec §7)")

        // The answer dies with the connection instead: uncertain, drop them.
        rig.host.handler = { method, params ->
            if (method == "turn.prompt") {
                rig.host.drop()
                kotlinx.coroutines.awaitCancellation()
            }
            when (method) {
                "workspaces.list" -> JsonArray(emptyList())
                "sessions.list" -> JsonArray(emptyList())
                else -> JsonPrimitive(true)
            }
        }
        val uncertain = rig.app.controller.prompt("claude:1", "hi again", null, null, null)
        assertEquals(false, uncertain.ok)
        assertEquals(emptyList(), rig.app.controller.uploads.drafts, "no replay of ids the next connection does not know (S28)")
        assertTrue(uncertain.error?.contains("不确定") == true, "the user is told it is uncertain: ${uncertain.error}")

        rig.app.controller.disconnect()
    }

    /** Leaving a session aborts its uploads and only its uploads (S29). */
    @Test
    fun detachAllScopesToTheSession() = runTest {
        val rig = setUp()
        rig.app.controller.attach("a.txt", null, "a".encodeToByteArray(), "claude:1")
        rig.app.controller.attach("b.txt", null, "b".encodeToByteArray(), "claude:2")
        rig.app.controller.detachAll("claude:1")
        assertEquals(listOf("b.txt"), rig.app.controller.uploads.drafts.map { it.name })
        val aborted = rig.host.calls.filter { it.first == "upload.abort" }.size
        assertEquals(1, aborted, "only the leaving session's upload was aborted")
        rig.app.controller.disconnect()
    }
}
