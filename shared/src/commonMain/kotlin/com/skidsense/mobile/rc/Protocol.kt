package com.skidsense.mobile.rc

/**
 * `skidsense-rc/1` as constants — the Kotlin twin of the desktop's
 * `src/shared/remote/protocol.ts`. The normative text is the desktop repo's
 * `docs/remote-control.md`; where this file and that one disagree, the
 * known-answer vectors decide.
 */
object Protocol {
    const val NAME = "skidsense-rc/1"
    const val VERSION = 1

    /** LAN carrier path (spec §10.4): `ws://<addr>:<port>/rc/1`. */
    const val LAN_PATH = "/rc/1"

    /** Relay carrier path (spec §9/§10). */
    const val RELAY_PATH = "api/companion/ws"

    const val PAIRING_URL_PREFIX = "skidsense://pair/1?d="

    /** Largest inner message one `d` frame carries, in UTF-8 bytes. */
    const val MAX_PLAINTEXT = 1024 * 1024

    /** Largest outer frame a carrier accepts. */
    const val MAX_FRAME = 2 * 1024 * 1024

    /** A response in parts is refused past this. */
    const val MAX_RESPONSE = 64L * 1024 * 1024

    /** Upload chunk size, raw bytes. */
    const val UPLOAD_CHUNK = 384 * 1024

    /** One attachment, and a turn's attachments together. */
    const val MAX_UPLOAD = 20L * 1024 * 1024

    /** Frames / bytes one key may seal before the carrier must be re-established. */
    const val MAX_FRAMES_PER_KEY: Long = 1L shl 32
    const val MAX_BYTES_PER_KEY: Long = 8L * 1024 * 1024 * 1024

    /** The handshake must finish within this on the host's side (spec §10.4). */
    const val HANDSHAKE_TIMEOUT_MS = 10_000L
}

enum class HandshakeMode(val wire: String) {
    ENROLL("enroll"),
    CONNECT("connect");

    companion object {
        fun fromWire(value: String?): HandshakeMode? = entries.firstOrNull { it.wire == value }
    }
}

/** What a device may do (spec §7). Unknown values are dropped, not errors. */
object Scopes {
    const val SESSIONS = "sessions"
    const val PROMPT = "prompt"
    const val APPROVE = "approve"
    const val FILES = "files"
    const val FILES_WRITE = "files.write"
    const val GIT = "git"
    const val GIT_WRITE = "git.write"
    const val TERMINAL = "terminal"

    val ALL: List<String> = listOf(SESSIONS, PROMPT, APPROVE, FILES, FILES_WRITE, GIT, GIT_WRITE, TERMINAL)

    /** Everything but the terminal: inside a TUI the CLI answers its own permission prompts. */
    val DEFAULT: List<String> = ALL - TERMINAL

    fun isScope(value: String): Boolean = value in ALL

    val LABELS: Map<String, String> = mapOf(
        SESSIONS to "查看会话",
        PROMPT to "发送消息",
        APPROVE to "审批权限请求",
        FILES to "查看文件",
        FILES_WRITE to "修改文件",
        GIT to "查看 Git",
        GIT_WRITE to "Git 写操作",
        TERMINAL to "终端"
    )
}

/** Every request method and the scope it needs — `METHODS` in protocol.ts. */
object Methods {
    val SCOPE: Map<String, String> = mapOf(
        "subscribe" to "sessions",
        "unsubscribe" to "sessions",
        "agents.list" to "sessions",
        "models.list" to "sessions",
        "workspaces.list" to "sessions",
        "sessions.list" to "sessions",
        "sessions.search" to "sessions",
        "sessions.open" to "sessions",
        "sessions.new" to "prompt",
        "sessions.rename" to "prompt",
        "sessions.delete" to "prompt",
        "turn.snapshot" to "sessions",
        "turn.prompt" to "prompt",
        "turn.stop" to "prompt",
        "turn.steer" to "prompt",
        "turn.interact" to "approve",
        "upload.begin" to "prompt",
        "upload.chunk" to "prompt",
        "upload.abort" to "prompt",
        "fs.list" to "files",
        "fs.listAll" to "files",
        "fs.read" to "files",
        "fs.write" to "files.write",
        "fs.op" to "files.write",
        "search.start" to "files",
        "search.cancel" to "files",
        "git.snapshot" to "git",
        "git.diff" to "git",
        "git.branches" to "git",
        "git.mutate" to "git.write",
        "artifacts.list" to "files",
        "tui.open" to "terminal",
        "tui.input" to "terminal",
        "tui.resize" to "terminal",
        "tui.close" to "terminal"
    )
}

/** Events, each gated by a scope — `EVENTS` in protocol.ts. */
object Events {
    const val SESSION_PATCH = "session.patch"
    const val SESSIONS_CHANGED = "sessions.changed"
    const val BACKGROUND_JOBS = "background.jobs"
    const val AGENTS_CHANGED = "agents.changed"
    const val FS_CHANGED = "fs.changed"
    const val SEARCH_PROGRESS = "search.progress"
    const val GIT_CHANGED = "git.changed"
    const val TUI_DATA = "tui.data"
    const val TUI_EXIT = "tui.exit"
}
