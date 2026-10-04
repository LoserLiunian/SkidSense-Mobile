package com.skidsense.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skidsense.mobile.model.SubAgent
import com.skidsense.mobile.model.ToolCall
import com.skidsense.mobile.model.TurnSnapshot

/**
 * Tool calls, rendered compactly: one row each — status, name, summary — that
 * expands to the input and the result. A turn can have dozens, so the collapsed
 * form is what makes a transcript readable on a phone.
 */
@Composable
fun ToolCallList(calls: List<ToolCall>, onOpenToolCall: ((ToolCall) -> Unit)? = null) {
    if (calls.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        calls.forEach { call -> ToolCallRow(call, onOpenToolCall) }
    }
}

@Composable
fun ToolCallRow(call: ToolCall, onOpen: ((ToolCall) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(call.status == "error") }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = onOpen == null) { expanded = !expanded }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusMark(call.status), color = statusColor(call.status), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text(
                call.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                call.summary.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onOpen != null) {
                Hint("详情")
            }
        }
        if (expanded) {
            ToolIo("输入", call.input)
            ToolIo("结果", call.result)
            Hint("${call.name} · ${statusLabel(call.status)}")
        }
    }
}

private fun statusMark(status: String): String = when (status) {
    "ok" -> "✓"
    "error" -> "✕"
    "cancelled" -> "—"
    "running" -> "▸"
    else -> "·"
}

/** Sub-agents and dynamic workflows, as rows that expand to what they did. */
@Composable
fun SubAgentList(snapshot: TurnSnapshot, ids: List<String>) {
    val agents = snapshot.subAgents.filter { it.id in ids }
    if (agents.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        agents.forEach { agent -> SubAgentRow(agent) }
    }
}

@Composable
fun SubAgentRow(agent: SubAgent) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), shape)
            .clickable { expanded = !expanded }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(agent.status)
            Spacer(Modifier.width(8.dp))
            Text(
                agent.description ?: agent.kind ?: "子代理",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            agent.durationMs?.let { Hint("${it / 1000}s") }
        }
        agent.activity?.takeIf { it.isNotBlank() }?.let { Hint(it) }
        if (expanded) {
            agent.prompt?.let { ToolIo("任务", kotlinx.serialization.json.JsonPrimitive(it)) }
            agent.text?.takeIf { it.isNotBlank() }?.let { ToolIo("最近发言", kotlinx.serialization.json.JsonPrimitive(it)) }
            agent.report?.let { ToolIo("结果", kotlinx.serialization.json.JsonPrimitive(it)) }
            if (agent.toolCalls.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                agent.toolCalls.take(20).forEach { ToolCallRow(it) }
            }
        }
    }
}

@Composable
fun WorkflowList(snapshot: TurnSnapshot, ids: List<String>) {
    val runs = snapshot.workflows.filter { it.id in ids }
    if (runs.isEmpty()) return
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
        runs.forEach { run ->
            ItemCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(run.status)
                        Spacer(Modifier.width(8.dp))
                        CardTitle(run.name ?: run.summary ?: "工作流")
                    }
                    run.phases.forEach { phase ->
                        Hint("${phase.title} · ${statusLabel(phase.status)}")
                    }
                    run.agents.take(12).forEach { agent ->
                        Hint("· ${agent.label} · ${statusLabel(agent.status)}")
                    }
                    run.result?.takeIf { it.isNotBlank() }?.let { MonoBlock(it, maxHeight = 180) }
                }
            }
        }
    }
}

/** The activity line: what the turn is doing right now, in one row. */
@Composable
fun ActivityRow(snapshot: TurnSnapshot) {
    val runningAgent = snapshot.subAgents.lastOrNull { it.status == "running" && it.background != true }
    val runningTool = snapshot.toolCalls.lastOrNull { it.status == "running" || it.status == "pending" }
    val text = when {
        runningAgent != null -> "子代理 ${runningAgent.kind ?: "subagent"}${runningAgent.description?.let { "：$it" } ?: ""}"
        runningTool != null -> runningTool.summary ?: runningTool.name
        else -> snapshot.activity
    }
    if (text.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(50))
                .background(statusColor(snapshot.phase))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
