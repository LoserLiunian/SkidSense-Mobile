package com.skidsense.mobile.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skidsense.mobile.app.AppController
import com.skidsense.mobile.app.SearchFold
import com.skidsense.mobile.model.DirEntry
import com.skidsense.mobile.model.ReadFileResult
import com.skidsense.mobile.rc.Scopes
import kotlinx.coroutines.launch

/**
 * 文件: browse a workspace, read a file, and (with `files.write`) edit and save
 * it. Saving sends the etag the text was loaded from, so an edit made outside
 * the app is a conflict the UI can offer to resolve rather than a silent
 * overwrite (the desktop's `WriteFileRequest` contract).
 */
@Composable
fun FilesScreen(app: AppController, root: String, onBack: () -> Unit) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    var path by remember(root) { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var openFile by remember { mutableStateOf<String?>(null) }
    var openLine by remember { mutableStateOf<Int?>(null) }
    var showIgnored by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val searchState = app.search.collectAsState().value

    // Leaving the screen cancels the search: the desktop keeps streaming
    // progress until it is told otherwise, and nobody would be reading it.
    DisposableEffect(Unit) {
        onDispose { scope.launch { app.cancelSearch(); app.clearSearch() } }
    }

    suspend fun load(target: String) {
        loading = true
        error = null
        try {
            val result = app.listDir(root, target, showIgnored)
            entries = result?.entries.orEmpty()
            truncated = result?.truncated == true
            path = target
        } catch (failure: Throwable) {
            error = failure.message ?: "无法读取目录"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(root, showIgnored) { load(path) }

    val canWrite = state.canScope(Scopes.FILES_WRITE)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (path.isEmpty()) onBack() else scope.launch { load(path.substringBeforeLast('/', "")) } }) {
                Text(if (path.isEmpty()) "返回" else "上一级")
            }
            Column(Modifier.weight(1f)) {
                Text("文件", style = MaterialTheme.typography.titleSmall)
                Hint(if (path.isEmpty()) root else "$root/$path")
            }
            TextButton(onClick = { showIgnored = !showIgnored }) { Text(if (showIgnored) "隐藏已忽略" else "显示全部") }
            TextButton(onClick = { searching = !searching }) { Text(if (searching) "浏览" else "搜索") }
        }

        if (searching) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索文件内容") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { regex = !regex },
                    enabled = true
                ) { Text(if (regex) "正则" else "文本") }
                Button(
                    onClick = {
                        searchError = null
                        scope.launch {
                            try {
                                app.startSearch(root, query, regex)
                            } catch (failure: Throwable) {
                                searchError = failure.message ?: "搜索失败"
                            }
                        }
                    },
                    enabled = query.isNotBlank()
                ) { Text("开始") }
                if (searchState != null && !searchState.state.done) {
                    TextButton(onClick = { scope.launch { app.cancelSearch() } }) { Text("取消") }
                }
            }
            ErrorBanner(searchError.orEmpty()) { searchError = null }
            SearchResults(
                fold = searchState,
                onOpen = { path, line ->
                    openFile = path
                    openLine = line
                },
                onBack = { scope.launch { app.clearSearch(); searching = false } }
            )
            return
        }

        if (searchState != null && !searchState.state.done) {
            TextButton(onClick = { searching = true }) { Text("搜索进行中…点这里看结果") }
        }

        if (openFile != null) {
            FileViewer(
                app = app,
                root = root,
                path = openFile!!,
                initialLine = openLine,
                canWrite = canWrite,
                onClose = { openFile = null; openLine = null; scope.launch { load(path) } }
            )
            return
        }

        ErrorBanner(error.orEmpty()) { error = null }
        if (loading) LoadingRow("读取目录…")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (truncated) {
                item { Hint("这个目录的项目太多，只显示了一部分。") }
            }
            items(entries, key = { it.path }) { entry ->
                ItemCard(onClick = {
                    if (entry.kind == "dir") scope.launch { load(entry.path) } else openFile = entry.path
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (entry.kind) {
                                "dir" -> "📁"
                                "symlink" -> "🔗"
                                else -> "📄"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (entry.kind == "dir") FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (entry.kind != "dir") Hint(formatBytes(entry.size))
                        }
                        entry.git?.let { Pill(gitStatusLabel(it), statusColor(entry.git)) }
                    }
                }
            }
            if (entries.isEmpty() && !loading) {
                item { EmptyState("这里是空的", "没有可显示的文件。") }
            }
        }
    }
}

/** Search hits, grouped per file, with each match's line and text. */
@Composable
private fun SearchResults(
    fold: SearchFold?,
    onOpen: (String, Int) -> Unit,
    onBack: () -> Unit
) {
    if (fold == null) {
        EmptyState("还没有搜索", "输入要查找的内容，回车开始。搜索在电脑上进行，结果随找随到。")
        return
    }
    val state = fold.state
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("「${state.query}」")
                Spacer(Modifier.weight(1f))
                if (state.done) {
                    Pill("${state.totalMatches} 处匹配 · ${state.files.size} 个文件", MaterialTheme.colorScheme.primary, filled = true)
                } else {
                    Pill("搜索中…", statusColor("running"), filled = true)
                }
            }
        }
        state.error?.let { message -> item { ErrorBanner(message) } }
        items(state.files, key = { it.path }) { file ->
            ItemCard {
                Column {
                    CardTitle(file.path) { if (file.truncated) Pill("结果被截断", statusColor("running")) }
                    file.matches.take(12).forEach { match ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(file.path, match.line) },
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                match.line.toString(),
                                style = MonoStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(40.dp)
                            )
                            Text(match.text.trim(), style = MonoStyle, maxLines = 2)
                        }
                    }
                    if (file.matches.size > 12) Hint("还有 ${file.matches.size - 12} 处")
                }
            }
        }
        if (state.files.isEmpty() && state.done && state.error == null) {
            item { EmptyState("没有匹配", "换个词，或确认搜索的是正确的工作区。") }
        }
        if (!state.done) {
            item { LoadingRow("正在搜索…") }
        }
        item { TextButton(onClick = onBack) { Text("清空结果") } }
    }
}

@Composable
private fun FileViewer(
    app: AppController,
    root: String,
    path: String,
    initialLine: Int? = null,
    canWrite: Boolean,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var file by remember(path) { mutableStateOf<ReadFileResult?>(null) }
    var text by remember(path) { mutableStateOf("") }
    var dirty by remember(path) { mutableStateOf(false) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var notice by remember(path) { mutableStateOf<String?>(null) }
    var editing by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        loading = true
        try {
            val result = app.readFile(root, path)
            file = result
            text = result?.text.orEmpty()
            if (result != null && result.encoding != "utf8") {
                notice = when (result.encoding) {
                    "binary" -> "这是二进制文件，只能预览。"
                    "too-large" -> "文件太大，无法在这里打开。"
                    else -> null
                }
            }
        } catch (failure: Throwable) {
            error = failure.message ?: "无法读取文件"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("返回") }
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Hint(
                    "${file?.lines ?: 0} 行 · ${formatBytes(file?.size ?: 0)}" +
                        (initialLine?.let { " · 跳到第 $it 行" } ?: "")
                )
            }
            if (canWrite && file?.encoding == "utf8") {
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "预览" else "编辑") }
            }
        }

        ErrorBanner(error.orEmpty()) { error = null }
        notice?.let { ErrorBanner(it) { notice = null } }
        if (loading) LoadingRow("读取文件…")

        if (editing && canWrite) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; dirty = true },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                textStyle = MonoStyle
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val result = app.writeFile(root, path, text, file?.etag)
                                when {
                                    result == null -> error = "电脑没有回应"
                                    result.ok -> {
                                        dirty = false
                                        notice = "已保存"
                                        // Re-read, so the etag and the line count move on.
                                        file = app.readFile(root, path)
                                    }
                                    result.conflict == true ->
                                        error = "文件在别处被改过，为避免覆盖别人，保存被拒绝。请重新打开后再改。"
                                    else -> error = result.error ?: "保存失败"
                                }
                            } catch (failure: Throwable) {
                                error = failure.message ?: "保存失败"
                            }
                        }
                    },
                    enabled = dirty,
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val result = app.readFile(root, path)
                            file = result
                            text = result?.text.orEmpty()
                            dirty = false
                        }
                    }
                ) { Text("重新加载") }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
            ) {
                SelectionContainer {
                    Text(text, style = MonoStyle)
                }
            }
        }
    }
}

fun gitStatusLabel(status: String): String = when (status) {
    "modified" -> "已修改"
    "added" -> "新增"
    "deleted" -> "已删除"
    "renamed" -> "重命名"
    "untracked" -> "未跟踪"
    "conflicted" -> "冲突"
    "ignored" -> "已忽略"
    else -> status
}
