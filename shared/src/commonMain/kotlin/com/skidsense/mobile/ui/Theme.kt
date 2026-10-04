package com.skidsense.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One theme, two schemes. Colours are named for the job they do rather than
 * for a hue, so a change here is a change everywhere — the same discipline the
 * desktop's CSS variables enforce.
 */
object RcColors {
    val accentLight = Color(0xFF3B5BDB)
    val accentDark = Color(0xFF8DA2FF)
    val okLight = Color(0xFF1F7A4D)
    val okDark = Color(0xFF6FD39B)
    val warnLight = Color(0xFF9A6400)
    val warnDark = Color(0xFFE8B458)
    val dangerLight = Color(0xFFB42318)
    val dangerDark = Color(0xFFFF8A80)
}

private val Light = lightColorScheme(
    primary = RcColors.accentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E8FF),
    onPrimaryContainer = Color(0xFF11215A),
    secondary = Color(0xFF5A6178),
    surface = Color(0xFFFBFBFD),
    surfaceVariant = Color(0xFFEFF0F4),
    onSurface = Color(0xFF1A1C22),
    onSurfaceVariant = Color(0xFF5A5F6B),
    outlineVariant = Color(0xFFDCDFE6),
    error = RcColors.dangerLight,
    background = Color(0xFFF6F7FA)
)

private val Dark = darkColorScheme(
    primary = RcColors.accentDark,
    onPrimary = Color(0xFF16204A),
    primaryContainer = Color(0xFF28336B),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFA8AEC2),
    surface = Color(0xFF15171C),
    surfaceVariant = Color(0xFF23262E),
    onSurface = Color(0xFFE7E9EF),
    onSurfaceVariant = Color(0xFFA9AFBC),
    outlineVariant = Color(0xFF343842),
    error = RcColors.dangerDark,
    background = Color(0xFF101216)
)

@Composable
fun RcTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = MaterialTheme.typography.copy(
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
        ),
        content = content
    )
}

/** Monospace, for transcripts, diffs, terminal output and keys. */
val MonoStyle: TextStyle
    @Composable get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

@Composable
fun statusColor(status: String): Color {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        "ok", "completed", "active", "done" -> if (isSystemInDarkTheme()) RcColors.okDark else RcColors.okLight
        "error", "failed", "revoked", "conflicted" -> scheme.error
        "running", "pending", "starting", "awaiting-input" -> if (isSystemInDarkTheme()) RcColors.warnDark else RcColors.warnLight
        "cancelled", "killed" -> scheme.onSurfaceVariant
        else -> scheme.onSurfaceVariant
    }
}

/** A single-line label for a turn phase, a tool status, a device status. */
fun statusLabel(status: String): String = when (status) {
    "idle" -> "空闲"
    "starting" -> "启动中"
    "running" -> "进行中"
    "awaiting-input" -> "等待回应"
    "done" -> "已完成"
    "error" -> "出错"
    "aborted" -> "已中止"
    "pending" -> "等待中"
    "ok" -> "成功"
    "cancelled" -> "已取消"
    "active" -> "已启用"
    "revoked" -> "已撤销"
    "completed" -> "已完成"
    "killed" -> "已终止"
    "failed" -> "失败"
    "in_progress" -> "进行中"
    else -> status
}

val SemiBold = FontWeight.SemiBold

/** Shown when the user turned on the biometric lock and it is engaged. */
@Composable
fun AppLockedScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SkidSense 已锁定", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "用系统锁屏验证身份后继续。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock) { Text("解锁") }
    }
}
