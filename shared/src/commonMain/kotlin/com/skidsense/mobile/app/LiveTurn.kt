package com.skidsense.mobile.app

import com.skidsense.mobile.model.SessionPatchPush
import com.skidsense.mobile.model.TurnRecord
import com.skidsense.mobile.model.TurnSnapshot
import com.skidsense.mobile.transport.RcJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Following one session's live turn.
 *
 * The fold is the desktop's (`applyPatch` in `src/core/snapshot.ts`): text and
 * reasoning travel as deltas, everything else is replaced wholesale by key.
 * Gap handling is the desktop renderer's (`useLiveTurn` in
 * `src/renderer/hooks.ts`): when `fromSeq` is not the seq we last applied, the
 * patch has no usable base, so the turn is re-read whole with `turn.snapshot`;
 * if that answers null the turn has ended, and the new seq is accepted anyway
 * — otherwise every later frame would look like another gap and the composer
 * would never unlock.
 */
class LiveTurn {
    var snapshot: TurnSnapshot? = null
        private set

    /** The seq of the last frame applied, as `useLiveTurn`'s `seqRef`. */
    var seq: Long = 0
        private set

    /** Bumped on every change, so Compose can read this as a state holder. */
    var revision: Long = 0
        private set

    /** Finished turns read with the session, plus any the patch stream completes. */
    var history: List<TurnRecord> = emptyList()
        private set

    fun reset() {
        snapshot = null
        seq = 0
        revision += 1
    }

    fun setHistory(turns: List<TurnRecord>) {
        history = turns
        revision += 1
    }

    fun setSnapshot(fresh: TurnSnapshot?) {
        snapshot = fresh
        revision += 1
    }

    /** The seq of the last frame applied; set when a gap is accepted without a re-read. */
    fun acceptSeq(value: Long) {
        seq = value
    }

    /** The result of applying a frame: either done, or a full re-read is needed. */
    sealed interface Applied {
        /** Applied; [deltaHistory] carries turns that finished, when known. */
        data object Ok : Applied

        /** The patch cannot be applied onto what we hold: read the turn again. */
        data object Gap : Applied
    }

    fun apply(push: SessionPatchPush): Applied {
        val base = push.base
        if (base != null) {
            seq = push.seq
            snapshot = base
            revision += 1
            return Applied.Ok
        }
        val current = snapshot ?: return Applied.Gap
        if (push.fromSeq != seq) return Applied.Gap
        seq = push.seq
        snapshot = patch(current, push)
        revision += 1
        return Applied.Ok
    }

    private fun patch(current: TurnSnapshot, push: SessionPatchPush): TurnSnapshot {
        val patch = push.patch
        var text = current.text
        var reasoning = current.reasoning
        if (!patch.textDelta.isNullOrEmpty()) text += patch.textDelta
        if (!patch.reasoningDelta.isNullOrEmpty()) reasoning += patch.reasoningDelta
        if (patch.fields.isNullOrEmpty() && text == current.text && reasoning == current.reasoning) return current

        // The fields are decoded by overlaying them onto the snapshot's own
        // JSON: one code path for every field, and unknown keys are dropped by
        // the lenient decoder rather than by a hand-written switch that would
        // drift from the model.
        val base = RcJson.encodeToJsonElement(TurnSnapshot.serializer(), current) as JsonObject
        val merged = LinkedHashMap<String, JsonElement>(base)
        merged["text"] = JsonPrimitive(text)
        merged["reasoning"] = JsonPrimitive(reasoning)
        patch.fields?.let { merged.putAll(it) }
        return RcJson.decodeFromJsonElement(TurnSnapshot.serializer(), JsonObject(merged))
    }

    /** How many fields a patch replaced, for tests to assert the merge path. */
    internal fun fieldCount(patch: com.skidsense.mobile.model.SnapshotPatch): Int = patch.fields?.size ?: 0
}
