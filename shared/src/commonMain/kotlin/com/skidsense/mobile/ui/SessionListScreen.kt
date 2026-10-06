package com.skidsense.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skidsense.mobile.app.AppController
import com.skidsense.mobile.model.SessionRow
import com.skidsense.mobile.rc.Scopes
import kotlinx.coroutines.launch

/**
 * 会话: every session in the active host's workspaces, newest first, live
 * status included. Search filters through `sessions.search`.
 */
@Composable
fun SessionListScreen(
    app: AppController,
    onOpen: (String) -> Unit,
    onOpenFiles: (String) -> Unit,
    onOpenGit: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(state.connection) {
        if (state.connected) app.loadSessions()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.activeHost?.name?.ifBlank { "会话" } ?: "会话", style = MaterialTheme.typography.titleMedium)
                ConnectionLine(app)
            }
            TextButton(onClick = onBack) { Text("电脑") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                scope.launch { app.searchSessions(it) }
            },
            label = { Text("搜索会话") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        ErrorBanner(state.sessionsError.orEmpty()) { app.clearError() }

        val workspaceByPath = state.workspaces.associateBy { it.path }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!state.connected) {
                item { OfflineCard(app) }
            }
            if (state.sessionsLoading && state.sessions.isEmpty()) {
                item { LoadingRow("正在读取会话…") }
            }
            if (state.workspaces.isNotEmpty() && state.sessions.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onOpenFiles(state.workspaces.first().path) },
                            enabled = state.connected && state.canScope(Scopes.FILES),
                            modifier = Modifier.weight(1f)
                        ) { Text("文件") }
                        OutlinedButton(
                            onClick = { onOpenGit(state.workspaces.first().path) },
                            enabled = state.connected && state.canScope(Scopes.GIT),
                            modifier = Modifier.weight(1f)
                        ) { Text("Git") }
                    }
                }
            }
            items(state.sessions, key = { it.key }) { row ->
                SessionCard(
                    row = row,
                    workspaceName = workspaceByPath[row.workdir]?.name ?: row.workdir,
                    onOpen = { onOpen(row.key) },
                    onRename = { title -> scope.launch { app.renameSession(row.key, title) } },
                    onDelete = { scope.launch { app.deleteSession(row.key) } },
                    canPrompt = state.canScope(Scopes.PROMPT)
                )
            }
            if (state.sessions.isEmpty() && state.connected && !state.sessionsLoading) {
                item {
                    EmptyState(
                        "这个工作区还没有会话",
                        if (state.canScope(Scopes.PROMPT)) "在电脑端开始一个会话，或在下面的输入框里发第一条消息。" else "这台手机没有发消息的权限。"
                    )
                }
                if (state.canScope(Scopes.PROMPT)) {
                    item { NewSessionCard(app) }
                }
            }
        }
    }
}

@Composable
fun ConnectionLine(app: AppController) {
    val appState by app.state.collectAsState()
    when (val connection = appState.connection) {
        is com.skidsense.mobile.transport.ClientState.Connected -> {
            // The relay is budgeted per account (§10): say so, or a slow
            // transcript over it reads as the app being broken.
            val budget = appState.relayBytesPerSecond
            val note = if (connection.route is com.skidsense.mobile.transport.Route.Relay && budget != null) {
                "（限速 ${(budget + 512) / 1024} KB/s）"
            } else ""
            Hint("已连接 · ${connection.route.label}$note")
        }
        is com.skidsense.mobile.transport.ClientState.Connecting ->
            Hint("正在连接 · ${connection.via}")
        is com.skidsense.mobile.transport.ClientState.Waiting ->
            Hint("${connection.error} · ${connection.retryInMs / 1000} 秒后重试")
        is com.skidsense.mobile.transport.ClientState.Failed ->
            Hint("连接失败：${connection.error}")
        com.skidsense.mobile.transport.ClientState.Idle -> Hint("未连接")
    }
}

@Composable
private fun OfflineCard(app: AppController) {
    ItemCard {
        Column {
            Text("还没有连上电脑", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Hint("确认电脑上的 SkidSense 正在运行，且这台手机与它配对过。局域网不可用时会自动走中继。")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { app.retry() }, modifier = Modifier.fillMaxWidth()) { Text("重试") }
        }
    }
}

@Composable
private fun SessionCard(
    row: SessionRow,
    workspaceName: String,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    canPrompt: Boolean
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var title by remember(row.title) { mutableStateOf(row.title) }

    ItemCard(onClick = onOpen) {
        Column {
            CardTitle(row.title.ifBlank { row.key }) {
                if (row.runState.isNotBlank() && row.runState != "idle") StatusPill(row.runState)
            }
            Spacer(Modifier.height(4.dp))
            if (row.preview.isNotBlank()) {
                Text(row.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("${row.agent} · $workspaceName")
                Spacer(Modifier.weight(1f))
                if (row.backgroundJobs.isNotEmpty()) {
                    Pill("${row.backgroundJobs.size} 个后台任务", statusColor("running"))
                }
            }
            if (canPrompt) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { renaming = true }) { Text("重命名") }
                    if (confirmingDelete) {
                        TextButton(onClick = onDelete) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
                    } else {
                        TextButton(onClick = { confirmingDelete = true }) { Text("删除") }
                    }
                }
            }
            if (renaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("标题") },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onRename(title)
                            renaming = false
                        }
                    ) { Text("保存") }
                }
            }
        }
    }
}

/** Start a session from the phone: pick a workspace and an agent, then say what for. */
@Composable
private fun NewSessionCard(app: AppController) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    var workdir by remember(state.workspaces) { mutableStateOf(state.workspaces.firstOrNull()?.path.orEmpty()) }
    var agent by remember { mutableStateOf("claude") }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ItemCard {
        Column {
            Text("新建会话", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Hint("工作区：")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.workspaces.forEach { workspace ->
                    OutlinedButton(
                        onClick = { workdir = workspace.path },
                        enabled = workdir != workspace.path
                    ) { Text(workspace.name.ifBlank { workspace.path }) }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = agent,
                onValueChange = { agent = it.trim() },
                label = { Text("代理（claude / codex / …）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题（可空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ErrorBanner(error.orEmpty())
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            app.newSession(agent, workdir, title.ifBlank { null })
                            title = ""
                        } catch (failure: Throwable) {
                            error = failure.message ?: "新建失败"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && workdir.isNotBlank() && agent.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "创建中…" else "创建") }
        }
    }
}
