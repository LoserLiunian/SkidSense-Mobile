package com.skidsense.mobile.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skidsense.mobile.app.AppController
import com.skidsense.mobile.model.GitDiffResult
import com.skidsense.mobile.model.GitFile
import com.skidsense.mobile.model.GitSnapshot
import com.skidsense.mobile.rc.Scopes
import kotlinx.coroutines.launch

/**
 * Git: 状态 and 差异, and — only with `git.write` — the mutations. Staging and
 * committing are separate steps on purpose, as on the desktop: there is no
 * commit-and-push, so each step can fail with its own explanation.
 */
@Composable
fun GitScreen(app: AppController, root: String, onBack: () -> Unit) {
    val state = app.state.value
    val scope = rememberCoroutineScope()
    var snapshot by remember(root) { mutableStateOf<GitSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var diff by remember { mutableStateOf<GitDiffResult?>(null) }
    var diffPath by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    var branches by remember { mutableStateOf<List<com.skidsense.mobile.model.GitBranchInfo>>(emptyList()) }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            snapshot = app.gitSnapshot(root)
        } catch (failure: Throwable) {
            error = failure.message ?: "无法读取 Git 状态"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(root, state.connected) { if (state.connected) refresh() }

    val canWrite = state.canScope(Scopes.GIT_WRITE)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Column(Modifier.weight(1f)) {
                Text("Git", style = MaterialTheme.typography.titleSmall)
                val repo = snapshot?.repo
                Hint(
                    when {
                        repo == null -> "这个工作区不是 Git 仓库"
                        repo.detached -> "游离 HEAD ${repo.shortSha ?: ""}"
                        else -> buildString {
                            append(repo.branch ?: "(未命名分支)")
                            if (repo.ahead > 0) append(" ↑${repo.ahead}")
                            if (repo.behind > 0) append(" ↓${repo.behind}")
                            repo.operation?.let { append(" · ${it}") }
                        }
                    }
                )
            }
            TextButton(onClick = { scope.launch { refresh() } }) { Text("刷新") }
        }

        ErrorBanner(error.orEmpty()) { error = null }
        snapshot?.error?.let { ErrorBanner(it) }
        if (loading) LoadingRow("读取 Git 状态…")

        if (diff != null) {
            DiffView(
                diff = diff!!,
                onClose = { diff = null; diffPath = null }
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val repo = snapshot?.repo
            if (repo == null) {
                item { EmptyState("不是 Git 仓库", "这个工作区里没有 .git。") }
                if (canWrite) {
                    item {
                        Button(onClick = {
                            scope.launch {
                                app.gitMutate(root, "init")
                                refresh()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("在这里初始化仓库") }
                    }
                }
                return@LazyColumn
            }

            item {
                ItemCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("工作区", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            if (repo.upstream != null) Hint("上游 ${repo.upstream}")
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        showBranches = !showBranches
                                        if (showBranches) branches = app.gitBranches(root)
                                    }
                                },
                                enabled = !busy
                            ) { Text("分支") }
                            if (canWrite) {
                                OutlinedButton(onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            app.gitMutate(root, "fetch")
                                            refresh()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }, enabled = !busy) { Text("获取") }
                                OutlinedButton(onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            app.gitMutate(root, "pull")
                                            refresh()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }, enabled = !busy) { Text("拉取") }
                                OutlinedButton(onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            app.gitMutate(root, "push")
                                            refresh()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }, enabled = !busy) { Text("推送") }
                            }
                        }
                        if (showBranches) {
                            Spacer(Modifier.height(6.dp))
                            branches.take(30).forEach { branch ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (branch.current) Pill("当前", MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(branch.name, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.weight(1f))
                                    if (canWrite && !branch.current) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                app.gitMutate(root, "switchBranch", branch = branch.name)
                                                refresh()
                                                branches = app.gitBranches(root)
                                            }
                                        }) { Text("切换") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val files = snapshot?.files.orEmpty()
            if (files.isEmpty()) {
                item { EmptyState("工作区是干净的", "没有未提交的改动。") }
            } else {
                item { SectionHeader("改动", "${files.size} 项") }
                items(files, key = { "${it.path}-${it.staged}" }) { file ->
                    GitFileCard(
                        file = file,
                        canWrite = canWrite,
                        onOpen = {
                            diffPath = file.path
                            scope.launch {
                                diff = app.gitDiff(root, file.path, if (file.staged) "staged" else "head")
                            }
                        },
                        onStage = { scope.launch { app.gitMutate(root, if (file.staged) "unstage" else "stage", paths = listOf(file.path)); refresh() } },
                        onDiscard = {
                            scope.launch {
                                app.gitMutate(root, if (file.status == "untracked") "discardUntracked" else "discard", paths = listOf(file.path))
                                refresh()
                            }
                        }
                    )
                }
                if (canWrite) {
                    item {
                        ItemCard {
                            Column {
                                Text("提交", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = message,
                                    onValueChange = { message = it },
                                    label = { Text("提交说明") },
                                    minLines = 2,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { busy = true; scope.launch { try { app.gitMutate(root, "stageAll"); refresh() } finally { busy = false } } },
                                        enabled = !busy
                                    ) { Text("全部暂存") }
                                    Button(
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                try {
                                                    val result = app.gitMutate(root, "commit", message = message)
                                                    if (result?.ok == true) {
                                                        message = ""
                                                        error = null
                                                    } else {
                                                        error = result?.error ?: "提交失败"
                                                    }
                                                    refresh()
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        },
                                        enabled = !busy && message.isNotBlank(),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("提交已暂存的改动") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitFileCard(
    file: GitFile,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onStage: () -> Unit,
    onDiscard: () -> Unit
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    ItemCard(onClick = onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(gitStatusLabel(file.status), statusColor(file.status))
                Spacer(Modifier.width(6.dp))
                Text(
                    file.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (file.insertions != null || file.deletions != null) {
                    Hint("+${file.insertions ?: 0} −${file.deletions ?: 0}")
                }
            }
            file.from?.let { Hint("来自 $it") }
            if (canWrite) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onStage) { Text(if (file.staged) "取消暂存" else "暂存") }
                    if (confirmingDiscard) {
                        TextButton(onClick = onDiscard) { Text("确认丢弃", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmingDiscard = false }) { Text("取消") }
                    } else {
                        TextButton(onClick = { confirmingDiscard = true }) { Text("丢弃改动") }
                    }
                }
            }
        }
    }
}

/** A unified diff, coloured by line kind. */
@Composable
private fun DiffView(diff: GitDiffResult, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("返回") }
            Column(Modifier.weight(1f)) {
                Text(diff.path, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (diff.truncated) Hint("差异太大，只显示了一部分")
            }
        }
        if (diff.binary) {
            EmptyState("二进制文件", "这里不显示二进制差异。")
            return
        }
        if (diff.error != null) {
            ErrorBanner(diff.error)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding
        ) {
            diff.hunks.forEach { hunk ->
                item {
                    Text(
                        hunk.header,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
                items(hunk.lines.size) { index ->
                    val line = hunk.lines[index]
                    val background = when (line.kind) {
                        "add" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        "del" -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Row(Modifier.fillMaxWidth().background(background)) {
                        Text(
                            (line.newLine ?: line.oldLine)?.toString() ?: "",
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp)
                        )
                        Text(
                            line.text,
                            style = MonoStyle,
                            color = when (line.kind) {
                                "add" -> statusColor("ok")
                                "del" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
            if (diff.hunks.isEmpty()) {
                item { EmptyState("没有差异", "这个文件和 HEAD 一致。") }
            }
        }
    }
}
