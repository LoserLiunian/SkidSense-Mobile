package com.skidsense.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skidsense.mobile.app.AppController
import com.skidsense.mobile.platform.rememberPairScanner
import com.skidsense.mobile.rc.Pairing
import com.skidsense.mobile.rc.PairingPayload
import kotlinx.coroutines.launch

/**
 * 配对. Either scan the QR code with the camera or paste the link by hand.
 *
 * The fingerprint is shown for the user to compare against the desktop's
 * screen *before* trusting it (spec §3) — it is the one check that makes the
 * pinned key meaningful, so it is not skipped when the code came from a scan.
 */
@Composable
fun PairingScreen(
    app: AppController,
    onPaired: (String) -> Unit,
    onBack: () -> Unit,
    /** A `skidsense://` link the app was opened with, decoded here like a paste. */
    initialLink: String? = null
) {
    val scanner = rememberPairScanner()
    val scope = rememberCoroutineScope()
    var pasted by remember(initialLink) { mutableStateOf(initialLink.orEmpty()) }
    var payload by remember(initialLink) {
        mutableStateOf(initialLink?.let { runCatching { Pairing.decode(it) }.getOrNull() })
    }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("配对电脑", style = MaterialTheme.typography.titleMedium)
        Hint("在电脑端 SkidSense 打开「远程控制」，用这里的相机扫描窗口里的二维码。二维码 10 分钟内有效，且只能用一次。")

        Button(
            onClick = {
                if (scanner == null) {
                    error = "这个平台还没有扫码功能，请把配对链接粘贴到下面。"
                    return@Button
                }
                scanner.scan { scanned ->
                    if (scanned == null) return@scan
                    error = null
                    payload = runCatching { Pairing.decode(scanned) }.getOrElse { failure ->
                        error = failure.message
                        null
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("扫描二维码") }

        SectionHeader("或者粘贴配对链接")
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it.trim() },
            label = { Text("skidsense://pair/1?d=…") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = {
                error = null
                payload = runCatching { Pairing.decode(pasted) }.getOrElse { failure ->
                    error = failure.message
                    null
                }
            },
            enabled = pasted.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("读取链接") }

        payload?.let { decoded ->
            ItemCard {
                Column {
                    Text("确认这台电脑", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    KeyValue("名称", decoded.machine.ifBlank { decoded.hostId })
                    KeyValue("指纹", decoded.fingerprint, mono = true)
                    KeyValue("后端", decoded.server)
                    KeyValue("局域网", decoded.lanAddrs.joinToString("、").ifBlank { "—" })
                    Spacer(Modifier.height(8.dp))
                    Hint("请与电脑屏幕上显示的指纹逐组核对。不一致就说明这个二维码不是你正在使用的那台电脑。")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        app.pair(decoded) { message -> progress = message }.let { onPaired(it.hostId) }
                                    } catch (failure: Throwable) {
                                        error = failure.message ?: "配对失败"
                                    } finally {
                                        busy = false
                                        progress = null
                                    }
                                }
                            },
                            enabled = !busy && payload != null,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (busy) "配对中…" else "确认并配对") }
                        OutlinedButton(onClick = { payload = null }, enabled = !busy) { Text("取消") }
                    }
                }
            }
        }

        progress?.let { LoadingRow(it) }
        ErrorBanner(error.orEmpty())

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}
