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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.skidsense.mobile.app.PairedHost
import com.skidsense.mobile.rc.fingerprint
import com.skidsense.mobile.rc.B64u
import kotlinx.coroutines.launch

/**
 * 我的电脑: the desktops this account has registered (`GET /hosts`) and the ones
 * this phone has paired with. Only a paired host can be connected to — a host
 * row alone is not enough, since the pin is what makes the connection
 * trustworthy (spec §8.3).
 */
@Composable
fun HostListScreen(
    app: AppController,
    onOpenHost: (String) -> Unit,
    onPair: () -> Unit,
    onSettings: () -> Unit
) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { app.refreshHosts() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("我的电脑", style = MaterialTheme.typography.titleMedium)
                Hint("已登录：${state.user ?: "—"} · ${state.baseUrl}")
            }
            TextButton(onClick = onSettings) { Text("设置") }
        }

        Button(
            onClick = onPair,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) { Text("扫描电脑上的二维码配对") }

        ErrorBanner(state.hostsError.orEmpty()) { app.clearError() }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.hostsLoading && state.hosts.isEmpty()) {
                item { LoadingRow("正在读取电脑列表…") }
            }

            val pairedIds = state.paired.map { it.hostId }.toSet()
            val paired = state.paired
            if (paired.isNotEmpty()) {
                item { SectionHeader("已配对", "可以直接连接") }
                items(paired, key = { "paired-${it.hostId}" }) { host ->
                    PairedHostCard(
                        host = host,
                        online = state.hosts.firstOrNull { it.hostId == host.hostId }?.online,
                        onOpen = { onOpenHost(host.hostId) },
                        onForget = { scope.launch { app.forgetHost(host.hostId) } }
                    )
                }
            }

            val unpairedHosts = state.hosts.filterNot { it.hostId in pairedIds }
            if (unpairedHosts.isNotEmpty()) {
                item { SectionHeader("这个账号的其他电脑", "还没有和这台手机配对") }
                items(unpairedHosts, key = { "host-${it.hostId}" }) { host ->
                    ItemCard {
                        Column {
                            CardTitle(host.name.ifBlank { host.hostId }) {
                                Pill(if (host.online) "在线" else "离线", if (host.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(6.dp))
                            Hint("${host.platform} · ${host.appVersion} · 配对前需要在电脑上生成二维码")
                        }
                    }
                }
            }

            if (paired.isEmpty() && state.hosts.isEmpty() && !state.hostsLoading) {
                item {
                    EmptyState(
                        "还没有可用的电脑",
                        "先在电脑端 SkidSense 里打开「远程控制」并生成配对二维码，再回到这里扫描。"
                    )
                }
            }
            item { VSpace(8) }
            item {
                OutlinedButton(onClick = { scope.launch { app.refreshHosts() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("刷新")
                }
            }
        }
    }
}

@Composable
private fun PairedHostCard(host: PairedHost, online: Boolean?, onOpen: () -> Unit, onForget: () -> Unit) {
    var confirmForget by remember { mutableStateOf(false) }
    ItemCard(onClick = onOpen) {
        Column {
            CardTitle(host.name.ifBlank { host.machine.ifBlank { host.hostId } }) {
                when (online) {
                    true -> Pill("在线", MaterialTheme.colorScheme.primary, filled = true)
                    false -> Pill("离线", MaterialTheme.colorScheme.onSurfaceVariant)
                    null -> Unit
                }
            }
            Spacer(Modifier.height(6.dp))
            KeyValue("指纹", runCatching { fingerprint(B64u.decode(host.hostKey, 32)) }.getOrElse { "—" }, mono = true)
            KeyValue("局域网", if (host.lanAddrs.isEmpty()) "—" else host.lanAddrs.joinToString("、"))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("连接") }
                if (confirmForget) {
                    OutlinedButton(onClick = onForget) { Text("确认忘记") }
                    TextButton(onClick = { confirmForget = false }) { Text("取消") }
                } else {
                    TextButton(onClick = { confirmForget = true }) { Text("忘记") }
                }
            }
            if (confirmForget) {
                Spacer(Modifier.height(4.dp))
                Hint("只在这台手机上删除记录；电脑端仍保留这台设备的登记，要彻底撤销请到设置里操作。")
            }
        }
    }
}
