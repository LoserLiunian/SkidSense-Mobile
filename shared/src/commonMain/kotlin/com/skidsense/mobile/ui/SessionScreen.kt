package com.skidsense.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.skidsense.mobile.model.Interaction
import com.skidsense.mobile.model.ModelCatalog
import com.skidsense.mobile.model.OpenSessionResponse
import com.skidsense.mobile.ui.formatBytes
import com.skidsense.mobile.model.ToolCall
import com.skidsense.mobile.platform.rememberFilePicker
import com.skidsense.mobile.rc.Scopes
import kotlinx.coroutines.launch

/**
 * One session: the transcript, live patches applied as they arrive, the
 * approval card when the agent is waiting, and the composer.
 *
 * Everything that needs a scope is hidden when the device does not have it —
 * and the scopes that count are the host's `welcome.device.scopes`, which is
 * already the grant intersected with the host's own cap (spec §8.3), not what
 * the backend said when the phone registered.
 */
@Composable
fun SessionScreen(
    app: AppController,
    sessionKey: String,
    onBack: () -> Unit,
    onOpenFiles: (String) -> Unit,
    onOpenGit: (String) -> Unit,
    onOpenTerminal: (String) -> Unit
) {
    val state by app.state.collectAsState()
    val revision by app.liveRevision.collectAsState()
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenSessionResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var toolDetail by remember { mutableStateOf<ToolCall?>(null) }

    LaunchedEffect(sessionKey, state.connected) {
        if (!state.connected) return@LaunchedEffect
        loading = true
        error = null
        try {
            opened = app.openSession(sessionKey)
            if (opened == null) error = "电脑上没有这个会话"
        } catch (failure: Throwable) {
            error = failure.message ?: "无法打开会话"
        } finally {
            loading = false
        }
    }

    // Read the revision so a patch recomposes this screen.
    @Suppress("UNUSED_EXPRESSION")
    revision

    val snapshot = app.liveTurn.snapshot
    val records = opened?.turns.orEmpty()
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Column(Modifier.weight(1f)) {
                Text(
                    opened?.row?.title?.ifBlank { sessionKey } ?: sessionKey,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                ConnectionLine(app)
            }
            snapshot?.let { StatusPill(it.phase) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val workdir = opened?.row?.workdir.orEmpty()
            OutlinedButton(
                onClick = { onOpenFiles(workdir) },
                enabled = workdir.isNotBlank() && state.canScope(Scopes.FILES)
            ) { Text("文件") }
            OutlinedButton(
                onClick = { onOpenGit(workdir) },
                enabled = workdir.isNotBlank() && state.canScope(Scopes.GIT)
            ) { Text("Git") }
            OutlinedButton(
                onClick = { onOpenTerminal(sessionKey) },
                enabled = state.canScope(Scopes.TERMINAL)
            ) { Text("终端") }
        }

        ErrorBanner(error.orEmpty()) { error = null }
        ErrorBanner(state.lastError.orEmpty()) { app.clearError() }

        if (loading) {
            LoadingRow("正在打开会话…")
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column {
                    TranscriptView(records, snapshot, onOpenToolCall = { toolDetail = it })
                    if (snapshot != null && snapshot.running) {
                        Spacer(Modifier.height(8.dp))
                        ActivityRow(snapshot)
                    }
                }
            }
            snapshot?.openInteraction?.let { interaction ->
                item {
                    ApprovalCard(
                        interaction = interaction,
                        sessionKey = sessionKey,
                        canApprove = state.canScope(Scopes.APPROVE),
                        app = app
                    )
                }
            }
            toolDetail?.let { call ->
                item {
                    ToolDetailCard(call) { toolDetail = null }
                }
            }
        }

        Composer(
            app = app,
            sessionKey = sessionKey,
            running = snapshot?.running == true,
            rowWorkdir = opened?.row?.workdir.orEmpty(),
            agent = opened?.row?.agent.orEmpty(),
            onSent = { scope.launch { listState.animateScrollToItem(0) } }
        )
    }
}

/**
 * The approval card (spec §6.2, `Interaction`).
 *
 * The choices are the agent's own — `Interaction.question.choices` — rendered
 * in the order they came, with `allow`-kind choices emphasised and deny-kinds
 * in the error colour; free text is offered only when the question allows it.
 * Inside a terminal none of this applies: the CLI answers its own prompts.
 */
@Composable
private fun ApprovalCard(interaction: Interaction, sessionKey: String, canApprove: Boolean, app: AppController) {
    val scope = rememberCoroutineScope()
    var freeText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val question = interaction.question

    ItemCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (question.kind) {
                        "permission" -> "需要你的许可"
                        "question" -> "代理在提问"
                        "input" -> "代理在等输入"
                        else -> "代理需要回应"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.weight(1f))
                if (question.allowFreeText) Pill("可自由输入", MaterialTheme.colorScheme.primary)
            }
            if (question.title.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(question.title, style = MaterialTheme.typography.bodyMedium)
            }
            question.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Hint(it)
            }
            question.subject?.let { subject ->
                Spacer(Modifier.height(6.dp))
                when (subject.type) {
                    "command" -> MonoBlock((subject.command ?: "").let { cmd ->
                        if (subject.cwd.isNullOrBlank()) cmd else "$cmd\n\n# 工作目录：${subject.cwd}"
                    })
                    "fileChange" -> MonoBlock(subject.diff ?: subject.path.orEmpty(), maxHeight = 260)
                    "permissions" -> Hint("请求的权限：${subject.scopes.joinToString("、")}")
                    else -> subject.input?.let { ToolIo("工具输入", it) }
                }
            }

            if (!canApprove) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner("这台手机没有被授予审批权限，请在电脑上回应，或换一台有权限的设备。")
                return@Column
            }

            Spacer(Modifier.height(10.dp))
            val choices = question.choices
            if (choices.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { app.selectChoice(sessionKey, interaction.id, "allow") } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("允许") }
                    OutlinedButton(
                        onClick = { scope.launch { app.selectChoice(sessionKey, interaction.id, "deny") } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("拒绝") }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { choice ->
                        Column {
                            Button(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            app.selectChoice(sessionKey, interaction.id, choice.id)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(choice.label) }
                            choice.description?.let { Hint(it) }
                        }
                    }
                }
            }

            if (question.allowFreeText) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    label = { Text(question.placeholder ?: "输入你的回答") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    app.answerText(sessionKey, interaction.id, freeText)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && freeText.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("发送回答") }
                    OutlinedButton(onClick = { scope.launch { app.skipQuestion(sessionKey, interaction.id) } }, enabled = !busy) {
                        Text("跳过")
                    }
                    TextButton(onClick = { scope.launch { app.cancelQuestion(sessionKey, interaction.id) } }, enabled = !busy) {
                        Text("取消回合")
                    }
                }
            }
        }
    }
}

/** One tool call's input and result, at full width. */
@Composable
private fun ToolDetailCard(call: ToolCall, onClose: () -> Unit) {
    ItemCard {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(call.status)
                Spacer(Modifier.width(8.dp))
                CardTitle(call.name)
                TextButton(onClick = onClose) { Text("收起") }
            }
            call.summary?.let { Hint(it) }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ToolIo("输入", call.input)
                ToolIo("结果", call.result)
            }
        }
    }
}

/**
 * The composer: send, stop, or steer. `approvalMode` is offered only when the
 * device may approve at all — `bypassPermissions`/`dontAsk` additionally need
 * `approve`, since a device that cannot approve must not be able to turn
 * approval off (spec §7).
 */
@Composable
private fun Composer(
    app: AppController,
    sessionKey: String,
    running: Boolean,
    rowWorkdir: String,
    agent: String,
    onSent: () -> Unit
) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    var text by remember(sessionKey) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalog by remember(agent) { mutableStateOf<ModelCatalog?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    var effort by remember { mutableStateOf<String?>(null) }
    var approval by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }

    val canPrompt = state.canScope(Scopes.PROMPT)
    val canApprove = state.canScope(Scopes.APPROVE)
    val canCall = state.can("turn.prompt")
    val picker = rememberFilePicker()
    // A flow, not a getter: a plain read used to freeze the chips until some
    // unrelated AppState change (N06).
    val staged by app.uploads.draftFlow.collectAsState()
    var attachError by remember { mutableStateOf<String?>(null) }

    // Leaving the screen drops what this session staged: an upload the desktop
    // forgets (10 minutes idle, a dead connection) is worse than none. Not
    // `scope.launch` — this composition's scope cancels before the body runs
    // (S29), which is why attachments used to leak into the next session's
    // composer.
    DisposableEffect(sessionKey) {
        onDispose { app.detachAllBackground(sessionKey) }
    }

    LaunchedEffect(agent, state.connected) {
        if (!state.connected || agent.isBlank() || !canPrompt) return@LaunchedEffect
        catalog = runCatching { app.models(agent) }.getOrNull()
    }

    if (!canPrompt || !canCall) {
        Hint("这台手机没有发消息的权限。")
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ErrorBanner(error.orEmpty()) { error = null }
        ErrorBanner(attachError.orEmpty()) { attachError = null }
        if (staged.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                staged.forEach { draft ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(formatBytes(draft.size), MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(draft.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { app.detach(draft.id) } }) { Text("移除") }
                    }
                }
                Hint("附件会在发送时一并交给电脑；离开这一屏就会丢弃未发送的附件。")
            }
        }
        if (showOptions) {
            ModelsRow(catalog) { selected -> model = selected }
            EffortRow(agent, effort) { selected -> effort = selected }
            ApprovalRow(approval, canApprove) { selected -> approval = selected }
            Hint("工作区：${rowWorkdir.ifBlank { "—" }}")
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (picker != null) {
                TextButton(
                    onClick = {
                        picker.pick(
                            onPicked = { picked ->
                                attachError = null
                                scope.launch {
                                    try {
                                        app.attach(picked.name, picked.mimeType, picked.bytes, sessionKey)
                                    } catch (failure: Throwable) {
                                        attachError = failure.message ?: "无法添加附件"
                                    }
                                }
                            },
                            onError = { attachError = it }
                        )
                    },
                    enabled = !busy
                ) { Text("＋") }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(if (running) "插话（追加到正在进行的回合）" else "说点什么") },
                maxLines = 5,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Column {
                if (running) {
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    app.steer(sessionKey, text)
                                    text = ""
                                } catch (failure: Throwable) {
                                    error = failure.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && text.isNotBlank()
                    ) { Text("插话") }
                    OutlinedButton(
                        onClick = { scope.launch { app.stopTurn(sessionKey) } },
                        enabled = !busy
                    ) { Text("停止") }
                } else {
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val response = app.prompt(sessionKey, text, model, effort, approval)
                                    if (response.ok) {
                                        text = ""
                                        onSent()
                                    } else {
                                        error = response.error ?: "电脑没有接受这条消息"
                                    }
                                } catch (failure: Throwable) {
                                    error = failure.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && text.isNotBlank()
                    ) { Text(if (busy) "…" else "发送") }
                }
                TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "收起" else "选项") }
            }
        }
    }
}

@Composable
private fun ModelsRow(catalog: ModelCatalog?, onPick: (String?) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    if (catalog == null || catalog.models.isEmpty()) {
        Hint("没有可选的模型列表")
        return
    }
    Hint("模型${catalog.route?.let { route -> route.accountName?.let { "（$it）" } ?: "" } ?: ""}")
    LazyRowOf(
        items = listOf(null as String? to "默认") + catalog.models.map { it.id to it.label },
        selected = selected
    ) { id ->
        selected = id
        onPick(id)
    }
}

@Composable
private fun EffortRow(agent: String, current: String?, onPick: (String?) -> Unit) {
    val levels = effortLevels(agent)
    if (levels.isEmpty()) return
    Hint("思考强度")
    var selected by remember { mutableStateOf(current) }
    LazyRowOf(items = listOf(null as String? to "默认") + levels.map { it to effortLabel(it) }, selected = selected) { value ->
        selected = value
        onPick(value)
    }
}

@Composable
private fun ApprovalRow(current: String?, canApprove: Boolean, onPick: (String?) -> Unit) {
    Hint(if (canApprove) "审批方式" else "审批方式（这台手机没有审批权限，只能选默认）")
    var selected by remember { mutableStateOf(current) }
    val options = buildList {
        add(null as String? to "默认（每次询问）")
        if (canApprove) {
            add("acceptEdits" to "自动接受编辑")
            add("plan" to "仅计划")
            add("dontAsk" to "不再询问")
            add("bypassPermissions" to "绕过全部权限")
        }
    }
    LazyRowOf(items = options, selected = selected) { value ->
        selected = value
        onPick(value)
    }
}

@Composable
private fun LazyRowOf(items: List<Pair<String?, String>>, selected: String?, onPick: (String?) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items.size) { index ->
            val (value, label) = items[index]
            OutlinedButton(
                onClick = { onPick(value) },
                enabled = value != selected
            ) { Text(label) }
        }
    }
}

/** The effort levels each agent takes, matching the desktop's composer. */
fun effortLevels(agent: String): List<String> = when (agent) {
    "claude" -> listOf("low", "medium", "high", "xhigh", "max", "ultra")
    "codex" -> listOf("minimal", "low", "medium", "high", "xhigh")
    "antigravity" -> listOf("low", "medium", "high")
    else -> emptyList()
}

fun effortLabel(value: String): String = when (value) {
    "minimal" -> "最低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "很高"
    "max" -> "最高"
    "ultra" -> "极致"
    else -> value
}
