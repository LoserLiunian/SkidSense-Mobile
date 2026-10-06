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
import com.skidsense.mobile.app.HistoryEntry
import com.skidsense.mobile.app.HistoryRepository
import kotlinx.coroutines.launch

/**
 * 历史: the encrypted history the backend stores (spec §11), decrypted on the
 * phone and read-only. The backend cannot search it, so the search box here
 * filters what has already been decrypted locally.
 */
@Composable
fun HistoryScreen(
    app: AppController,
    repository: HistoryRepository?,
    onBack: () -> Unit
) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<HistoryEntry?>(null) }

    LaunchedEffect(repository, state.activeHostId) {
        if (repository == null) return@LaunchedEffect
        val host = state.activeHost ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            repository.refreshKeys(host.deviceId)
            entries = repository.load()
        } catch (failure: Throwable) {
            error = failure.message ?: "无法读取历史"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (open != null) open = null else onBack() }) { Text("返回") }
            Column(Modifier.weight(1f)) {
                Text("历史", style = MaterialTheme.typography.titleSmall)
                Hint("存在后端上的密文，只有这台手机能解开")
            }
            TextButton(
                onClick = {
                    scope.launch {
                        val host = state.activeHost ?: return@launch
                        loading = true
                        try {
                            repository?.refreshKeys(host.deviceId)
                            entries = repository?.load().orEmpty()
                            error = null
                        } catch (failure: Throwable) {
                            error = failure.message
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && repository != null
            ) { Text("刷新") }
        }

        if (repository == null) {
            EmptyState("还没有可读的历史", "先连接一台电脑，历史密钥会在连接后从后端取回。")
            return
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("在本机搜索（后端读不到正文）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )

        ErrorBanner(error.orEmpty()) { error = null }
        if (loading) LoadingRow("取回并解密历史…")

        open?.let { entry ->
            HistoryDetail(entry)
            return
        }

        val shown = HistoryRepository.search(entries, query)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (entries.isNotEmpty() && shown.isEmpty()) {
                item { EmptyState("没有匹配的会话", "换个词试试。") }
            }
            items(shown, key = { it.sessionKey }) { entry ->
                ItemCard(onClick = {
                    // The blob is fetched only now, for the session the user
                    // actually opened (S25), and decryption results are cached
                    // by the repository.
                    scope.launch {
                        loading = true
                        try {
                            open = repository.open(entry.sessionKey, entry.epoch, entry.updatedAt, entry.size)
                            error = null
                        } catch (failure: Throwable) {
                            error = failure.message
                        } finally {
                            loading = false
                        }
                    }
                }) {
                    Column {
                        CardTitle(entry.row?.title?.ifBlank { entry.sessionKey } ?: entry.sessionKey) {
                            if (entry.error != null) Pill("无法读取", MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(4.dp))
                        if (query.isNotBlank()) {
                            Text(HistoryRepository.excerpt(entry, query), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        } else {
                            entry.row?.preview?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hint("${entry.row?.agent ?: entry.sessionKey.substringBefore(':')} · 第 ${entry.epoch} 代密钥 · ${formatBytes(entry.size)}")
                            Spacer(Modifier.weight(1f))
                            if (entry.turns.isNotEmpty()) Hint("${entry.turns.size} 回合")
                        }
                        entry.error?.let {
                            Spacer(Modifier.height(4.dp))
                            Hint(it)
                        }
                    }
                }
            }
            if (entries.isEmpty() && !loading) {
                item {
                    EmptyState(
                        "还没有历史",
                        "历史是电脑端主动上传的：只有它下次上传之后，这段对话才会出现在这里。"
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryDetail(entry: HistoryEntry) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(entry.row?.title ?: entry.sessionKey, style = MaterialTheme.typography.titleSmall)
            Hint("第 ${entry.epoch} 代密钥 · ${entry.row?.workdir.orEmpty()}")
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                TranscriptView(records = entry.turns, live = null)
            }
            entry.error?.let { message -> item { ErrorBanner(message) } }
        }
    }
}

/** Settings: this phone's devices on the active host, scopes, revoke, logout. */
@Composable
fun SettingsScreen(
    app: AppController,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onHistory: () -> Unit,
    onBiometric: () -> Unit
) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingRevoke by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.activeHostId) {
        state.activeHost?.let { app.devicesFor(it.hostId) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ItemCard {
                    Column {
                        Text("账号", style = MaterialTheme.typography.bodyMedium, fontWeight = SemiBold)
                        Spacer(Modifier.height(4.dp))
                        KeyValue("用户", state.user ?: "未登录")
                        KeyValue("后端", state.baseUrl)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onHistory, enabled = state.activeHost != null) { Text("历史") }
                            OutlinedButton(onClick = onLogout) { Text("退出登录") }
                        }
                    }
                }
            }

            item {
                ItemCard {
                    Column {
                        Text("安全", style = MaterialTheme.typography.bodyMedium, fontWeight = SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Hint("这台手机的私钥由系统密钥库保护，不能导出。")
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("打开应用时要求生物识别", style = MaterialTheme.typography.bodySmall)
                                Hint("只是便利锁，密钥本身不依赖它。")
                            }
                            Button(onClick = onBiometric) { Text(if (state.biometricLock) "已开启" else "开启") }
                        }
                    }
                }
            }

            val host = state.activeHost
            if (host == null) {
                item { EmptyState("没有连接的电脑", "连接一台电脑后，这里会列出可以管理权限的设备。") }
            } else {
                item {
                    ItemCard {
                        Column {
                            Text("这台电脑上的设备", style = MaterialTheme.typography.bodyMedium, fontWeight = SemiBold)
                            KeyValue("主机", host.name.ifBlank { host.hostId })
                            KeyValue("指纹", runCatching { com.skidsense.mobile.rc.fingerprint(com.skidsense.mobile.rc.B64u.decode(host.hostKey, 32)) }.getOrElse { "—" }, mono = true)
                            KeyValue("本机设备 id", host.deviceId, mono = true)
                            state.welcome?.let { welcome ->
                                Spacer(Modifier.height(4.dp))
                                Hint("这台手机实际有效的权限（后端授予与电脑本地上限的交集）：")
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    welcome.device.scopes.forEach { scopeName ->
                                        Pill(com.skidsense.mobile.rc.Scopes.LABELS[scopeName] ?: scopeName, MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (welcome.device.scopes.size < com.skidsense.mobile.rc.Scopes.ALL.size) {
                                    Hint("终端等权限默认不授予：终端里的 CLI 会自己回答权限提示，审批对话框不再适用。")
                                }
                            }
                        }
                    }
                }

                item { SectionHeader("设备", "改名、改权限、撤销") }
                items(state.devicesForHost, key = { it.deviceId }) { device ->
                    DeviceCard(
                        device = device,
                        isThisDevice = device.deviceId == host.deviceId,
                        busy = false,
                        onRename = { name -> scope.launch { app.renameDevice(device.deviceId, name) } },
                        onScopes = { scopes -> scope.launch { app.setDeviceScopes(device.deviceId, scopes) } },
                        onRevoke = { scope.launch { app.revokeDevice(device.deviceId) } }
                    )
                }
                if (state.devicesForHost.isEmpty()) {
                    item { EmptyState("还没有设备", "配对的手机和被撤销的设备都会出现在这里。") }
                }
            }

            item { VSpace(8) }
            item { Hint("注销或更换账号后，需要在电脑端重新生成配对二维码。") }
        }
    }
}

@Composable
private fun DeviceCard(
    device: com.skidsense.mobile.api.DeviceRow,
    isThisDevice: Boolean,
    busy: Boolean,
    onRename: (String) -> Unit,
    onScopes: (List<String>) -> Unit,
    onRevoke: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    var name by remember(device.name) { mutableStateOf(device.name) }
    var editingScopes by remember { mutableStateOf(false) }
    var scopes by remember(device.scopes) { mutableStateOf(device.scopes.toSet()) }
    var confirmingRevoke by remember { mutableStateOf(false) }

    ItemCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(device.name.ifBlank { device.deviceId }) {
                    if (isThisDevice) {
                        Spacer(Modifier.padding(start = 4.dp))
                        Pill("这台手机", MaterialTheme.colorScheme.primary, filled = true)
                    }
                }
                StatusPill(device.status)
            }
            Spacer(Modifier.height(4.dp))
            Hint("${device.platform} · ${device.deviceId}")
            device.lastSeenAt?.let { Hint("最后在线：${formatTime(it * 1000)}") }

            if (renaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onRename(name); renaming = false }, enabled = !busy) { Text("保存") }
                }
            }

            if (editingScopes) {
                Spacer(Modifier.height(6.dp))
                // What this edits is the backend's grant. The computer
                // intersects it with its own cap for the device (spec §8.1
                // step 6), so ticking something the computer has not allowed —
                // the terminal, by default — changes nothing, and the box must
                // not read as if it did.
                Hint("这里改的是服务器上的授权。实际生效的是它与电脑本地为这台设备设的上限的交集：电脑上没放开的权限，在这里勾上也不会生效。")
                com.skidsense.mobile.rc.Scopes.ALL.forEach { scopeName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = scopeName in scopes,
                            onCheckedChange = { checked ->
                                scopes = if (checked) scopes + scopeName else scopes - scopeName
                            }
                        )
                        Text(
                            (com.skidsense.mobile.rc.Scopes.LABELS[scopeName] ?: scopeName) +
                                if (scopeName == com.skidsense.mobile.rc.Scopes.TERMINAL) "（还需在电脑上为这台设备放开）" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                TextButton(
                    onClick = { onScopes(scopes.toList()); editingScopes = false },
                    enabled = !busy
                ) { Text("保存权限") }
            } else {
                Hint("服务器授权：")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    device.scopes.forEach { scopeName ->
                        Pill(com.skidsense.mobile.rc.Scopes.LABELS[scopeName] ?: scopeName, MaterialTheme.colorScheme.primary)
                    }
                    if (device.scopes.isEmpty()) Hint("没有权限")
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { renaming = !renaming }) { Text("改名") }
                TextButton(onClick = { editingScopes = !editingScopes }) { Text("权限") }
                if (confirmingRevoke) {
                    TextButton(onClick = onRevoke) { Text("确认撤销", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { confirmingRevoke = false }) { Text("取消") }
                } else {
                    TextButton(onClick = { confirmingRevoke = true }) { Text("撤销") }
                }
            }
            if (isThisDevice && confirmingRevoke) {
                Hint("撤销后这台手机就连不上了，需要重新扫码配对。")
            }
        }
    }
}

fun formatTime(epochMs: Long): String {
    // No datetime library: the app only ever shows a local-looking clock, and
    // the numbers come from the host's own clock.
    val seconds = epochMs / 1000
    val minutes = (seconds / 60) % 60
    val hours = (seconds / 3600) % 24
    val days = seconds / 86400
    return "${days}天 ${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}
