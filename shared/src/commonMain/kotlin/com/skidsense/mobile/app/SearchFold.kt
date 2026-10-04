package com.skidsense.mobile.app

import com.skidsense.mobile.model.SearchFileResult
import com.skidsense.mobile.model.SearchProgressPush

/**
 * One files search's progress, folded as `search.progress` frames arrive.
 *
 * A search is identified by an id **this device chose** (spec §6.4): the
 * desktop prefixes it with the connection id for its own bookkeeping and echoes
 * ours back on every frame, so the id here is what matches — and another
 * device's search, or another connection's, is not ours to display.
 *
 * Separated from the controller because the fold is the part with rules in it
 * (id matching, accumulation, and the fact that `done`/`error` are terminal),
 * and it can be tested without a connection.
 */
class SearchFold(val id: String, val query: String) {
    data class State(
        val id: String,
        val query: String,
        val files: List<SearchFileResult> = emptyList(),
        val done: Boolean = false,
        val error: String? = null,
        val totalMatches: Int = 0,
        val fileCount: Int = 0,
        val elapsedMs: Long = 0
    ) {
        val matches: Int get() = files.sumOf { it.matches.size }
    }

    var state: State = State(id = id, query = query)
        private set

    /** Apply one frame. Returns true when the state changed. */
    fun apply(push: SearchProgressPush): Boolean {
        if (push.id != id) return false
        // A search that has ended does not reopen: a late frame from the
        // desktop, or one that crossed our cancel, must not add results.
        if (state.done) return false
        state = when (push.kind) {
            "files" -> state.copy(files = state.files + push.files)
            "done" -> state.copy(
                done = true,
                totalMatches = push.totalMatches,
                fileCount = push.fileCount,
                elapsedMs = push.elapsedMs
            )
            "error" -> state.copy(done = true, error = push.error.ifBlank { "搜索失败" })
            else -> return false
        }
        return true
    }

    /** Mark it finished because we asked the host to stop. */
    fun cancelled() {
        if (!state.done) state = state.copy(done = true)
    }

    /** Mark it finished because the request itself failed. */
    fun failed(message: String) {
        if (!state.done) state = state.copy(done = true, error = message)
    }
}
