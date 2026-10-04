package com.skidsense.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skidsense.mobile.model.ToolCall
import com.skidsense.mobile.model.TurnRecord
import com.skidsense.mobile.model.TurnSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The transcript: the prompt, then the turn's segments in the order they
 * happened — text, thinking, tool calls, sub-agents — then what it ended with.
 *
 * Segments come from the desktop (`Segment` in `src/core/snapshot.ts`); when a
 * turn has none (recorded by an older desktop), the text and the tool calls are
 * shown in that order instead, which is what those turns always were.
 */
@Composable
fun TranscriptView(
    records: List<TurnRecord>,
    live: TurnSnapshot?,
    modifier: Modifier = Modifier,
    onOpenToolCall: ((ToolCall) -> Unit)? = null
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        records.forEach { record ->
            TurnCard(record.snapshot, record.error, onOpenToolCall)
        }
        live?.let { snapshot ->
            if (records.none { it.taskId == snapshot.taskId }) {
                TurnCard(snapshot, snapshot.error, onOpenToolCall)
            }
        }
    }
}

@Composable
private fun TurnCard(snapshot: TurnSnapshot, error: String?, onOpenToolCall: ((ToolCall) -> Unit)?) {
    if (snapshot.prompt.isNotBlank()) PromptBubble(snapshot.prompt)

    val segments = snapshot.segments
    if (segments.isEmpty()) {
        // No segment order to follow: the classic reading of a turn.
        if (snapshot.reasoning.isNotBlank()) ThinkingBlock(snapshot.reasoning)
        if (snapshot.text.isNotBlank()) TextBlock(snapshot.text)
        if (snapshot.toolCalls.isNotEmpty()) ToolCallList(snapshot.toolCalls, onOpenToolCall)
    } else {
        segments.forEach { segment ->
            when (segment.kind) {
                "text" -> TextBlock(slice(snapshot.text, segment.from, segments.after("text", segment.from)))
                "thinking" -> ThinkingBlock(slice(snapshot.reasoning, segment.from, segments.after("thinking", segment.from)))
                "tools" -> ToolCallList(snapshot.toolCalls.filter { it.id in segment.ids }, onOpenToolCall)
                "agents" -> SubAgentList(snapshot, segment.ids)
                "workflows" -> WorkflowList(snapshot, segment.ids)
            }
        }
    }

    snapshot.plan?.let { plan ->
        if (plan.steps.isNotEmpty()) {
            ItemCard {
                Column {
                    Text("计划", style = MaterialTheme.typography.bodySmall, fontWeight = SemiBold)
                    plan.explanation?.let { Hint(it) }
                    plan.steps.forEach { step ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(stepMark(step.status), style = MaterialTheme.typography.bodySmall, color = statusColor(step.status), modifier = Modifier.width(18.dp))
                            Text(step.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    snapshot.backgroundJobs.filter { it.status == "running" }.let { jobs ->
        if (jobs.isNotEmpty()) {
            ItemCard {
                Column {
                    Text("后台任务", style = MaterialTheme.typography.bodySmall, fontWeight = SemiBold)
                    jobs.forEach { job -> Hint("${job.label} · ${statusLabel(job.status)}") }
                }
            }
        }
    }

    snapshot.usage?.let { usage ->
        val parts = buildList {
            usage.contextTokens?.let { add("上下文 ${formatTokens(it)}") }
            usage.outputTokens?.let { add("输出 ${formatTokens(it)}") }
            usage.costUsd?.takeIf { it > 0 }?.let { add("≈$${((it * 100).toInt() / 100.0)}") }
        }
        if (parts.isNotEmpty()) Hint(parts.joinToString(" · "))
    }

    snapshot.steers.forEach { steer -> Hint("插话：${steer.text}") }
    snapshot.compaction?.let { Hint("上下文已压缩（${if (it.trigger == "auto") "自动" else "手动"}）") }

    if (error != null) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun List<com.skidsense.mobile.model.Segment>.after(kind: String, from: Int): Int {
    // The stretch runs to the next segment of the same kind, or to the end.
    val next = firstOrNull { it.kind == kind && it.from > from }?.from
    return next ?: Int.MAX_VALUE
}

private fun slice(text: String, from: Int, to: Int): String =
    if (from >= text.length) "" else text.substring(from, minOf(to, text.length))

private fun stepMark(status: String): String = when (status) {
    "completed" -> "✓"
    "in_progress" -> "▸"
    else -> "·"
}

@Composable
fun PromptBubble(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(12.dp)
    ) {
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun TextBlock(text: String) {
    if (text.isBlank()) return
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun ThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    if (text.isBlank()) return
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("思考过程")
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(text, style = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }
    }
}

/** A one-line status while the turn is in flight, from `activity` and the live tool. */
@Composable
fun LiveStatusLine(snapshot: TurnSnapshot) {
    val text = snapshot.activity ?: snapshot.toolCalls.lastOrNull { it.status == "running" || it.status == "pending" }?.summary
    if (text.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(snapshot.phase)
        Spacer(Modifier.width(8.dp))
        Hint(text, Modifier.weight(1f))
    }
}

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> "${(value / 100_000) / 10.0}M"
    value >= 1_000 -> "${(value / 100) / 10.0}K"
    else -> value.toString()
}

/** Everything a `ToolCall.input`/`result` can be, including the truncated form. */
@Composable
fun ToolIo(label: String, value: JsonElement?) {
    if (value == null) return
    val truncated = (value as? JsonObject)?.get("__truncated")
    if ((truncated as? JsonPrimitive)?.contentOrNull == "true") {
        val bytes = ((value["bytes"] as? JsonPrimitive)?.contentOrNull ?: "?").toLongOrNull()
        val preview = (value["preview"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Hint("$label 已截断${bytes?.let { "（原始 ${formatBytes(it)}）" } ?: ""}")
            MonoBlock(preview, maxHeight = 160)
        }
        return
    }
    val text = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        is JsonArray -> value.joinToString("\n") { element ->
            (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
        }
        else -> value.toString()
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Hint(label)
        MonoBlock(text, maxHeight = 220)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private val AllSegments = emptyList<com.skidsense.mobile.model.Segment>()
