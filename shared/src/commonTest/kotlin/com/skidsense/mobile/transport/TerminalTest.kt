package com.skidsense.mobile.transport

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.ui.TerminalChannel
import com.skidsense.mobile.ui.TerminalSink
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
 * The terminal's two directions against a host that implements the desktop's
 * `tui.*` handlers: `tui.open` starts a stream, output arrives as `tui.data`,
 * and keystrokes go back as `tui.input`.
 *
 * What this covers: the request/response shapes, the parameter names (`key`),
 * the event routing through the controller's sink, the accumulate-before-ready
 * buffer, and the input path back out.
 *
 * What it does **not** cover, and cannot: xterm.js actually rendering in a
 * WebView. The `evaluateJavascript` call and the page's own bridge are Android
 * platform code that needs a real WebView — see the note in
 * `platform/TerminalView.android.kt`. The seam tested here is everything up to
 * `TerminalHost.write`.
 */
class TerminalTest {
    private val hostStatic = Primitives.generateKeyPair()
    private val identity = Primitives.generateKeyPair()
    private val hostId = B64u.encode(Primitives.randomBytes(16))

    /** Records what the page would have been told to draw. */
    private class FakePage : TerminalSink {
        val drawn = StringBuilder()
        val events = mutableListOf<TerminalChannel.ExitInfo>()
        override fun onData(key: String, data: String) {
            drawn.append(data)
        }

        override fun onExit(key: String, code: Int, reason: String, tail: String) {
            events += TerminalChannel.ExitInfo(key, code, reason, tail)
        }
    }

    private class Harness(val client: RcClient, val host: FakeHost)

    private suspend fun TestScope.setUp(): Harness {
        val host = FakeHost(hostId, hostStatic, backgroundScope)
        // Anything not handled specially answers with its own method name.
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
        return Harness(client, host)
    }

    @Test
    fun openingATerminalUsesKeyAndStartsTheStream() = runTest {
        val h = setUp()
        val opened = h.client.call("tui.open", buildJsonObject { put("key", "claude:1"); put("cols", 100); put("rows", 30) })
        assertTrue((opened as JsonObject)["ok"] == JsonPrimitive(true))
        assertTrue(h.host.terminalOpen)
        val params = h.host.calls.first { it.first == "tui.open" }.second as JsonObject
        // `key`, not `sessionKey` — spec §7's exception is only `turn.prompt`.
        assertTrue(params.containsKey("key"))
        assertTrue(!params.containsKey("sessionKey"))
        assertEquals("100", (params["cols"] as JsonPrimitive).content)
        h.client.stop()
    }

    @Test
    fun outputArrivesAsEventsAndInputGoesBack() = runTest {
        val h = setUp()
        val page = RecordingHost()
        // The controller's routing, which the screen wires to the sink: every
        // `tui.data` event goes through it and on to the page.
        val channel = TerminalChannel("claude:1")
        channel.attach(page)
        val routing = backgroundScope.launch {
            h.client.events.collect { event ->
                if (event.kind == "tui.data") {
                    TerminalChannel.decodeData(event.payload)?.let { (key, data) -> channel.onData(key, data) }
                } else if (event.kind == "tui.exit") {
                    TerminalChannel.decodeExit(event.payload)?.let { exit -> channel.onExit(exit.key, exit.code, exit.reason, exit.tail) }
                }
            }
        }
        h.client.call("tui.open", buildJsonObject { put("key", "claude:1"); put("cols", 80); put("rows", 24) })

        // An event the host sends unprompted, as the PTY produces output.
        h.host.emitTerminal("claude:1", "\u001b[32m$\u001b[0m ")
        withTimeout(5_000) { while (page.written.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals("\u001b[32m$\u001b[0m ", page.written.toString())

        val afterFirst = page.written.length
        h.host.emitTerminal("claude:1", "files")
        withTimeout(5_000) { while (page.written.length <= afterFirst) kotlinx.coroutines.delay(5) }
        assertEquals("\u001b[32m$\u001b[0m files", page.written.toString())

        // ...and a keystroke back.
        h.client.call("tui.input", buildJsonObject { put("key", "claude:1"); put("data", "ls\r") })
        withTimeout(5_000) { while (h.host.terminalInput.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(listOf("ls\r"), h.host.terminalInput)
        h.client.stop()
    }

    @Test
    fun outputBeforeThePageIsReadyIsKeptAndFlushed() = runTest {
        // A PTY usually has a prompt ready before xterm.js has finished loading.
        val channel = TerminalChannel("claude:1")
        channel.onData("claude:1", "first ")
        channel.onData("claude:1", "second ")
        val page = RecordingHost()
        channel.attach(page)
        assertEquals("", page.written.toString(), "nothing to draw yet")
        channel.flush()
        assertEquals("first second ", page.written.toString())
    }

    @Test
    fun outputForAnotherSessionIsIgnored() = runTest {
        val channel = TerminalChannel("claude:1")
        val page = RecordingHost()
        channel.attach(page)
        channel.onData("codex:2", "not ours")
        channel.flush()
        assertEquals("", page.written.toString())
    }

    @Test
    fun theBufferIsBoundedSoAShellCannotFloodIt() = runTest {
        val channel = TerminalChannel("claude:1")
        val chunk = "x".repeat(50_000)
        repeat(10) { channel.onData("claude:1", chunk) }
        val page = RecordingHost()
        channel.attach(page)
        channel.flush()
        assertEquals(200_000, page.written.length, "the tail is kept, not everything")
    }

    @Test
    fun anExitIsDrawnAsAFooter() = runTest {
        val channel = TerminalChannel("claude:1")
        val page = RecordingHost()
        channel.attach(page)
        channel.onExit("claude:1", 0, "process exited", "")
        assertTrue(page.written.toString().contains("终端已结束"))
        assertTrue(page.written.toString().contains("process exited"))
    }

    @Test
    fun inputWithoutAnOpenTerminalIsRefusedByTheHost() = runTest {
        val h = setUp()
        val error = assertFailsWith<RemoteCallError> {
            h.client.call("tui.input", buildJsonObject { put("key", "claude:1"); put("data", "x") })
        }
        assertEquals("not-found", error.code)
        h.client.stop()
    }

    @Test
    fun theEventPayloadsDecode() {
        // The two shapes the controller routes on.
        assertEquals(
            "claude:1" to "hi",
            TerminalChannel.decodeData(buildJsonObject { put("key", "claude:1"); put("data", "hi") })
        )
        val decoded = TerminalChannel.decodeExit(buildJsonObject {
            put("key", "claude:1"); put("code", 3); put("reason", "boom")
        })
        assertEquals("claude:1", decoded?.key)
        assertEquals(3, decoded?.code)
        assertEquals("boom", decoded?.reason)
        assertEquals("", decoded?.tail)
        assertEquals(null, TerminalChannel.decodeData(buildJsonObject { put("key", "claude:1") }))
        assertEquals(null, TerminalChannel.decodeExit(JsonPrimitive("nonsense")))
    }

}

/** Stands in for the Compose `TerminalHost` the screen passes in. */
private class RecordingHost : com.skidsense.mobile.platform.TerminalHost {
    val written = StringBuilder()
    override var onSizeChange: ((Int, Int) -> Unit)? = null
    override var onInput: ((String) -> Unit)? = null
    override fun write(data: String) {
        written.append(data)
    }
}
