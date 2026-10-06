package com.skidsense.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The desktop payload types this app displays, modelled leniently: unknown
 * keys are ignored and almost everything is defaulted, because the desktop
 * keeps adding fields (the brief says so, and the reference types in
 * `src/shared/ipc.ts` show it). Only fields this UI actually shows are here.
 */

/** `SessionRow` — `src/store/index-db.ts`. */
@Serializable
data class SessionRow(
    val key: String,
    val agent: String = "",
    @SerialName("sessionId") val sessionId: String = "",
    val workdir: String = "",
    val title: String = "",
    val preview: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val runState: String = "idle",
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val backgroundJobs: List<BackgroundJob> = emptyList()
)

@Serializable
data class BackgroundJob(
    val id: String = "",
    val label: String = "",
    val status: String = "",
    val kind: String? = null,
    @SerialName("endedAt") val endedAt: Long? = null
)

@Serializable
data class Workspace(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val order: Int = 0,
    @SerialName("addedAt") val addedAt: Long = 0
)

@Serializable
data class AgentStatus(
    val id: String = "",
    val label: String = "",
    val driven: Boolean = false,
    val installed: Boolean = false,
    @SerialName("binPath") val binPath: String? = null,
    val version: String? = null,
    val notes: String? = null,
    val tui: Boolean? = null
)

@Serializable
data class ModelOption(val id: String = "", val label: String = "", val description: String? = null, val live: Boolean = false)

@Serializable
data class CatalogRoute(
    val kind: String = "cli",
    @SerialName("accountName") val accountName: String? = null,
    @SerialName("defaultModel") val defaultModel: String? = null
)

@Serializable
data class ModelCatalog(
    val agent: String = "",
    val models: List<ModelOption> = emptyList(),
    val source: String = "static",
    val route: CatalogRoute? = null
)

/** `ToolCall` — `src/core/events.ts`. */
@Serializable
data class ToolCall(
    val id: String = "",
    val name: String = "tool",
    val summary: String? = null,
    /** Truncated tool IO arrives as `{"__truncated":true,"bytes":N,"preview":"…"}`. */
    val input: JsonElement? = null,
    val result: JsonElement? = null,
    val status: String = "pending",
    val startedAt: Long = 0,
    @SerialName("endedAt") val endedAt: Long? = null
)

@Serializable
data class PlanStep(val text: String = "", val status: String = "pending")

@Serializable
data class Plan(val explanation: String? = null, val steps: List<PlanStep> = emptyList())

@Serializable
data class Usage(
    @SerialName("contextTokens") val contextTokens: Long? = null,
    @SerialName("inputTokens") val inputTokens: Long? = null,
    @SerialName("outputTokens") val outputTokens: Long? = null,
    @SerialName("cacheReadTokens") val cacheReadTokens: Long? = null,
    @SerialName("cacheWriteTokens") val cacheWriteTokens: Long? = null,
    @SerialName("reasoningTokens") val reasoningTokens: Long? = null,
    @SerialName("costUsd") val costUsd: Double? = null
)

@Serializable
data class Artifact(val kind: String = "file", val title: String = "", val path: String? = null, val uri: String? = null, val bytes: Long? = null)

@Serializable
data class SubAgent(
    val id: String = "",
    @SerialName("callId") val callId: String? = null,
    val kind: String? = null,
    val description: String? = null,
    /** The task it was given. */
    val prompt: String? = null,
    val model: String? = null,
    val status: String = "running",
    @SerialName("toolCalls") val toolCalls: List<ToolCall> = emptyList(),
    val text: String? = null,
    @SerialName("textIsLatest") val textIsLatest: Boolean? = null,
    val report: String? = null,
    val activity: String? = null,
    @SerialName("durationMs") val durationMs: Long? = null,
    @SerialName("totalTokens") val totalTokens: Long? = null,
    val background: Boolean? = null
)

@Serializable
data class WorkflowRun(
    val id: String = "",
    val name: String? = null,
    val status: String = "running",
    val phases: List<WorkflowPhase> = emptyList(),
    val agents: List<WorkflowAgent> = emptyList(),
    val result: String? = null,
    val summary: String? = null
)

@Serializable
data class WorkflowPhase(val index: Int = 0, val title: String = "", val status: String = "pending")

@Serializable
data class WorkflowAgent(
    val id: String = "",
    val label: String = "",
    val status: String = "pending",
    val model: String? = null
)

@Serializable
data class ServicedModel(val id: String = "", val label: String? = null, val provider: String? = null)

@Serializable
data class ModelRoute(
    val requested: String? = null,
    val served: ServicedModel? = null,
    val rerouted: Boolean = false
)

@Serializable
data class QueuedPrompt(val id: String = "", val text: String = "", @SerialName("queuedAt") val queuedAt: Long = 0)

@Serializable
data class Steer(val id: String = "", val text: String = "", @SerialName("sentAt") val sentAt: Long = 0)

@Serializable
data class Compaction(val trigger: String = "auto", @SerialName("atTokens") val atTokens: Long? = null)

/** One stretch of a turn, in order — `Segment` in `src/core/snapshot.ts`. */
@Serializable
data class Segment(
    val kind: String,
    /** Byte-strings' offsets: `text`/`reasoning` for text kinds, ids for the grouped kinds. */
    val from: Int = 0,
    val ids: List<String> = emptyList()
)

/**
 * `TurnSnapshot`. Everything but the identity fields is optional-and-defaulted
 * so a turn recorded by an older desktop still parses.
 */
@Serializable
data class TurnSnapshot(
    val taskId: String = "",
    val agent: String = "",
    @SerialName("sessionId") val sessionId: String? = null,
    val workdir: String = "",
    val phase: String = "idle",
    val prompt: String = "",
    @SerialName("startedAt") val startedAt: Long = 0,
    @SerialName("updatedAt") val updatedAt: Long = 0,
    val error: String? = null,
    val incomplete: Boolean = false,
    val text: String = "",
    val reasoning: String = "",
    val activity: String? = null,
    val segments: List<Segment> = emptyList(),
    val plan: Plan? = null,
    @SerialName("toolCalls") val toolCalls: List<ToolCall> = emptyList(),
    @SerialName("subAgents") val subAgents: List<SubAgent> = emptyList(),
    val workflows: List<WorkflowRun> = emptyList(),
    @SerialName("backgroundJobs") val backgroundJobs: List<BackgroundJob> = emptyList(),
    val usage: Usage? = null,
    val artifacts: List<Artifact> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val queued: List<QueuedPrompt> = emptyList(),
    val compaction: Compaction? = null,
    val steers: List<Steer> = emptyList(),
    @SerialName("modelRoute") val modelRoute: ModelRoute? = null
) {
    val running: Boolean get() = phase == "starting" || phase == "running" || phase == "awaiting-input"

    /** The pending question, if the turn is waiting on one. */
    val openInteraction: Interaction? get() = interactions.lastOrNull { it.answeredAt == null }
}

/** `Interaction` — `src/core/interaction.ts`. */
@Serializable
data class Interaction(
    val id: String = "",
    val question: AskUserQuestion = AskUserQuestion(),
    @SerialName("askedAt") val askedAt: Long = 0,
    @SerialName("answeredAt") val answeredAt: Long? = null,
    val answer: JsonElement? = null,
    /** Which paired device answered, when it was not the desktop. */
    @SerialName("answeredBy") val answeredBy: String? = null
)

@Serializable
data class AskUserQuestion(
    val kind: String = "permission",
    val title: String = "",
    val detail: String? = null,
    val subject: InteractionSubject? = null,
    val choices: List<AskUserChoice> = emptyList(),
    @SerialName("allowFreeText") val allowFreeText: Boolean = false,
    val placeholder: String? = null
)

@Serializable
data class InteractionSubject(
    val type: String = "tool",
    val command: String? = null,
    val cwd: String? = null,
    val path: String? = null,
    val diff: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    val scopes: List<String> = emptyList()
)

@Serializable
data class AskUserChoice(
    val id: String = "",
    val label: String = "",
    val description: String? = null,
    /** `allow | allow_always | deny | deny_always | neutral`. */
    val kind: String = "neutral"
)

/** `TurnRecord` — `src/store/sessions.ts`. */
@Serializable
data class TurnRecord(
    val taskId: String = "",
    val prompt: String = "",
    @SerialName("startedAt") val startedAt: Long = 0,
    @SerialName("endedAt") val endedAt: Long = 0,
    val ok: Boolean = true,
    val error: String? = null,
    @SerialName("stopReason") val stopReason: String = "",
    val snapshot: TurnSnapshot = TurnSnapshot()
)

/** `OpenSessionResponse` — `src/shared/ipc.ts`. */
@Serializable
data class OpenSessionResponse(
    val row: SessionRow = SessionRow(key = ""),
    val turns: List<TurnRecord> = emptyList(),
    val live: TurnSnapshot? = null,
    val origin: String = "own",
    @SerialName("nativeSessionId") val nativeSessionId: String? = null
)

/** `PromptResponse`: `{ok:true,…}` or `{ok:false,error}`. */
@Serializable
data class PromptResponse(val ok: Boolean = false, @SerialName("sessionKey") val sessionKey: String? = null, val taskId: String? = null, val error: String? = null)

/**
 * One transport-level timestamp: the desktop sends Node's `mtimeMs`, which is
 * a *fractional* millisecond on APFS/ext4/NTFS — and a `Long` decoder fails
 * fs.list wholesale on the first such file (N01, spec §7 C7: decode as
 * floating point, the fraction may be dropped).
 */
object MtimeSerializer : kotlinx.serialization.KSerializer<Long> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("Mtime", kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Long = decoder.decodeDouble().toLong()
}

object NullableMtimeSerializer : kotlinx.serialization.KSerializer<Long?> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("Mtime?", kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Long?) { if (value != null) encoder.encodeLong(value) }
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Long? = decoder.decodeDouble().toLong()
}

/** One directory row — `DirEntry` in `src/shared/ipc.ts`. */
@Serializable
data class DirEntry(
    val name: String = "",
    val path: String = "",
    val kind: String = "file",
    val size: Long = 0,
    @Serializable(with = MtimeSerializer::class) val mtime: Long = 0,
    val git: String? = null
)

@Serializable
data class ListDirResult(val entries: List<DirEntry> = emptyList(), val truncated: Boolean = false)

@Serializable
data class ReadFileResult(
    val path: String = "",
    val encoding: String = "utf8",
    val text: String? = null,
    val base64: String? = null,
    val mime: String = "",
    val size: Long = 0,
    @Serializable(with = MtimeSerializer::class) val mtime: Long = 0,
    val lines: Int = 0,
    val etag: String = ""
)

/** `{ok, error, conflict}` — the two variants differ only in which fields exist. */
@Serializable
data class WriteFileResult(
    val ok: Boolean = false,
    val etag: String? = null,
    @Serializable(with = NullableMtimeSerializer::class) val mtime: Long? = null,
    val error: String? = null,
    val conflict: Boolean? = null
)

@Serializable
data class FileOpResult(val ok: Boolean = false, val path: String? = null, val error: String? = null)

// --- git ---------------------------------------------------------------------

@Serializable
data class GitFile(
    val path: String = "",
    val from: String? = null,
    val status: String = "modified",
    val staged: Boolean = false,
    val insertions: Int? = null,
    val deletions: Int? = null,
    val binary: Boolean? = null
)

@Serializable
data class GitRepo(
    val path: String = "",
    val branch: String? = null,
    val detached: Boolean = false,
    @SerialName("shortSha") val shortSha: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    @SerialName("defaultBranch") val defaultBranch: String? = null,
    val operation: String? = null,
    val empty: Boolean = false
)

@Serializable
data class GitFetchState(@SerialName("lastOkAt") val lastOkAt: Long? = null, @SerialName("lastError") val lastError: String? = null, val pending: Boolean = false)

@Serializable
data class GitSnapshot(
    val repo: GitRepo? = null,
    val files: List<GitFile> = emptyList(),
    val conflicted: List<String> = emptyList(),
    val fetch: GitFetchState? = null,
    val truncated: Boolean = false,
    val error: String? = null,
    val reason: String? = null
)

@Serializable
data class GitHunkLine(val kind: String = "context", val text: String = "", val oldLine: Int? = null, val newLine: Int? = null)

@Serializable
data class GitHunk(
    val header: String = "",
    @SerialName("oldStart") val oldStart: Int = 0,
    @SerialName("oldLines") val oldLines: Int = 0,
    @SerialName("newStart") val newStart: Int = 0,
    @SerialName("newLines") val newLines: Int = 0,
    val lines: List<GitHunkLine> = emptyList()
)

@Serializable
data class GitDiffResult(
    val path: String = "",
    val binary: Boolean = false,
    val hunks: List<GitHunk> = emptyList(),
    @SerialName("originalText") val originalText: String? = null,
    @SerialName("currentText") val currentText: String? = null,
    val truncated: Boolean = false,
    val error: String? = null
)

@Serializable
data class GitBranchInfo(
    val name: String = "",
    val current: Boolean = false,
    val remote: Boolean = false,
    val upstream: String? = null,
    val at: Long = 0
)

@Serializable
data class GitMutateResult(val ok: Boolean = false, val error: String? = null, val reason: String? = null, val detail: String? = null)

@Serializable
data class ArtifactRow(
    val root: String = "",
    val path: String = "",
    @SerialName("sessionKey") val sessionKey: String = "",
    @SerialName("sessionTitle") val sessionTitle: String = "",
    val agent: String = "",
    val at: Long = 0,
    val edits: Int = 0,
    val added: Int? = null,
    val removed: Int? = null
)

/** One file's hits, from `search.progress` (`SearchFileResult` in `src/shared/ipc.ts`). */
@Serializable
data class SearchFileResult(
    val path: String = "",
    val matches: List<SearchMatch> = emptyList(),
    val truncated: Boolean = false
)

@Serializable
data class SearchMatch(
    val line: Int = 0,
    val column: Int = 0,
    /** The full line, trimmed of indentation. */
    val text: String = "",
    val ranges: List<SearchRange> = emptyList()
)

@Serializable
data class SearchRange(val start: Int = 0, val end: Int = 0)

/**
 * One `search.progress` message. The desktop serialises a union of three
 * shapes; `kind` says which one, and the fields of the others are absent. A
 * single lenient class beats a sealed hierarchy here because the event is
 * produced by `{...push, id}` on the desktop side, so the discriminated fields
 * are flat rather than nested.
 */
@Serializable
data class SearchProgressPush(
    val id: String = "",
    val kind: String = "",
    val files: List<SearchFileResult> = emptyList(),
    val done: Boolean = false,
    @SerialName("totalMatches") val totalMatches: Int = 0,
    val truncated: Boolean = false,
    @SerialName("elapsedMs") val elapsedMs: Long = 0,
    @SerialName("fileCount") val fileCount: Int = 0,
    val error: String = ""
)

/** `SessionPatchPush` — `src/shared/ipc.ts`. */
@Serializable
data class SessionPatchPush(
    @SerialName("sessionKey") val sessionKey: String = "",
    val taskId: String = "",
    val seq: Long = 0,
    @SerialName("fromSeq") val fromSeq: Long = 0,
    val patch: SnapshotPatch = SnapshotPatch(),
    val base: TurnSnapshot? = null
)

/** The wire patch: deltas for the two append-only fields, wholesale for the rest. */
@Serializable
data class SnapshotPatch(
    val textDelta: String? = null,
    val reasoningDelta: String? = null,
    val fields: Map<String, JsonElement>? = null
)
