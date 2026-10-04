package com.skidsense.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skidsense.mobile.app.AppController
import com.skidsense.mobile.platform.TerminalHost
import com.skidsense.mobile.platform.TerminalWebView
import com.skidsense.mobile.transport.RcJson
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 终端 — the lowest-priority feature, and the one with a warning attached.
 *
 * The host's PTY is rendered by xterm.js in a WebView, fed by the `tui.data`
 * events and answering with `tui.input`. It is the same PTY the desktop sees.
 *
 * **The approval cards do not apply here.** Inside a terminal the CLI answers
 * its own permission prompts, so a device with the `terminal` scope can let the
 * agent do things the approval flow would otherwise have stopped. That is a
 * property of handing over the raw CLI, not a bug, and the screen says so
 * before opening anything. The `terminal` scope is off by default and has to be
 * granted for this device on the desktop.
 */
@Composable
fun TerminalScreen(app: AppController, sessionKey: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmed by remember(sessionKey) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    var cols by remember { mutableStateOf(80) }
    var rows by remember { mutableStateOf(24) }
    val host = remember(sessionKey) { TerminalChannel(sessionKey) }
    val connected = app.state.value.connected

    LaunchedEffect(confirmed, connected) {
        if (!confirmed || !connected) return@LaunchedEffect
        try {
            app.openTerminal(sessionKey, cols, rows)
            opened = true
        } catch (failure: Throwable) {
            error = failure.message ?: "无法打开终端"
            confirmed = false
        }
    }

    // Output is delivered as `tui.data` events; the controller hands them here.
    DisposableEffect(host, opened) {
        app.terminalSink = if (opened) host else null
        onDispose { if (app.terminalSink === host) app.terminalSink = null }
    }

    DisposableEffect(sessionKey) {
        onDispose {
            scope.launch { runCatching { app.closeTerminal(sessionKey) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("终端", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (opened) {
                TextButton(onClick = {
                    scope.launch { runCatching { app.closeTerminal(sessionKey) } }
                    opened = false
                    confirmed = false
                }) { Text("关闭终端") }
            }
        }

        ErrorBanner(error.orEmpty()) { error = null }

        if (!confirmed) {
            ItemCard(Modifier.padding(12.dp)) {
                Column {
                    Text("终端会绕过审批", style = MaterialTheme.typography.bodyMedium, fontWeight = SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "终端里跑的是电脑上真正的 CLI。权限提示由它自己在终端里回应，" +
                            "手机上的审批卡片不会出现，也不会拦下任何操作。" +
                            "只有电脑端为这台手机打开了「终端」权限才能使用，默认是不给的。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { confirmed = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("我明白，打开终端")
                    }
                }
            }
            return
        }

        if (!opened) {
            LoadingRow("正在打开终端…")
            return
        }

        TerminalWebView(
            cols = cols,
            rows = rows,
            onReady = { page ->
                host.attach(page)
                page.onInput = { data -> scope.launch { runCatching { app.terminalInput(sessionKey, data) } } }
                page.onSizeChange = { newCols, newRows ->
                    cols = newCols
                    rows = newRows
                    scope.launch { runCatching { app.terminalResize(sessionKey, newCols, newRows) } }
                }
                host.flush()
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

/**
 * The terminal's two directions. Output accumulates until the page is ready —
 * a PTY usually has something to say before the WebView has finished loading
 * xterm.js, and losing that would lose the prompt.
 *
 * It holds only the session key it belongs to: the connection's job is to route
 * events here, and this one's is to get them onto the screen.
 */
class TerminalChannel(private val sessionKey: String) : TerminalSink {
    private val pending = StringBuilder()
    private var page: TerminalHost? = null

    fun attach(host: TerminalHost) {
        page = host
    }

    fun flush() {
        val host = page ?: return
        if (pending.isNotEmpty()) {
            host.write(pending.toString())
            pending.clear()
        }
    }

    override fun onData(key: String, data: String) {
        if (key != sessionKey) return
        val host = page
        if (host == null) {
            pending.append(data)
            // Keep only the tail: a shell that floods must not grow this
            // without bound. `deleteRange` is a Kotlin/JVM-only member, so the
            // tail is rebuilt instead — same result, works on every target.
            if (pending.length > 200_000) {
                val tail = pending.substring(pending.length - 200_000)
                pending.clear()
                pending.append(tail)
            }
        } else {
            host.write(data)
        }
    }

    override fun onExit(key: String, code: Int, reason: String) {
        if (key != sessionKey) return
        page?.write("\r\n" + ESC + "[2m[终端已结束：" + reason.ifBlank { "退出码 $code" } + "]" + ESC + "[0m\r\n")
    }

    /** The `tui.data` payload: `{key, data}`. */
    companion object {
        fun decodeData(payload: kotlinx.serialization.json.JsonElement): Pair<String, String>? = runCatching {
            val obj = payload as JsonObject
            val key = (obj["key"] as? JsonPrimitive)?.content ?: return null
            val data = (obj["data"] as? JsonPrimitive)?.content ?: return null
            key to data
        }.getOrNull()

        fun decodeExit(payload: kotlinx.serialization.json.JsonElement): Triple<String, Int, String>? = runCatching {
            val obj = payload as JsonObject
            val key = (obj["key"] as? JsonPrimitive)?.content ?: return null
            val code = (obj["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val reason = (obj["reason"] as? JsonPrimitive)?.content.orEmpty()
            Triple(key, code, reason)
        }.getOrNull()
    }
}

/** The ANSI escape a dimmed line needs — written out to avoid a Kotlin escape. */
private const val ESC = "\u001b"

/** What the controller calls when terminal events arrive. */
interface TerminalSink {
    fun onData(key: String, data: String)
    fun onExit(key: String, code: Int, reason: String)
}
