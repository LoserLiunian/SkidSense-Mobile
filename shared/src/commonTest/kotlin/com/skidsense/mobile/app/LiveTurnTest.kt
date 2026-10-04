package com.skidsense.mobile.app

import com.skidsense.mobile.model.Interaction
import com.skidsense.mobile.model.Segment
import com.skidsense.mobile.model.SessionPatchPush
import com.skidsense.mobile.model.SnapshotPatch
import com.skidsense.mobile.model.ToolCall
import com.skidsense.mobile.model.TurnRecord
import com.skidsense.mobile.model.TurnSnapshot
import com.skidsense.mobile.transport.RcJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The transcript fold, ported from the desktop's `applyPatch` and `useLiveTurn`
 * (spec §6.4). The gap rule is the subtle part: a patch whose `fromSeq` is not
 * the seq we applied has no usable base, so the caller re-reads — and when the
 * re-read answers null, the new seq is accepted anyway.
 */
class LiveTurnTest {
    private fun snapshot(text: String = "") = TurnSnapshot(
        taskId = "t1",
        agent = "claude",
        workdir = "/tmp",
        phase = "running",
        prompt = "做点事",
        text = text,
        toolCalls = listOf(ToolCall(id = "c1", name = "Bash", summary = "ls", status = "ok"))
    )

    private fun push(seq: Long, fromSeq: Long, patch: SnapshotPatch = SnapshotPatch(), base: TurnSnapshot? = null) =
        SessionPatchPush(sessionKey = "claude:1", taskId = "t1", seq = seq, fromSeq = fromSeq, patch = patch, base = base)

    @Test
    fun baseReplacesTheSnapshotAndSetsTheSeq() {
        val turn = LiveTurn()
        assertEquals(LiveTurn.Applied.Ok, turn.apply(push(5, 0, base = snapshot())))
        assertEquals(5, turn.seq)
        assertEquals("做点事", turn.snapshot?.prompt)
    }

    @Test
    fun deltasAppendAndFieldsReplace() {
        val turn = LiveTurn()
        turn.apply(push(1, 0, base = snapshot("第一段")))
        val patch = SnapshotPatch(
            textDelta = "，第二段",
            reasoningDelta = "思考",
            fields = mapOf(
                "phase" to JsonPrimitive("done"),
                "activity" to JsonPrimitive("正在收尾"),
                "toolCalls" to RcJson.encodeToJsonElement(
                    ListSerializer(ToolCall.serializer()),
                    listOf(ToolCall(id = "c1", name = "Bash", summary = "ls", status = "ok"), ToolCall(id = "c2", name = "Read", status = "running"))
                ),
                "segments" to RcJson.encodeToJsonElement(
                    ListSerializer(Segment.serializer()),
                    listOf(Segment(kind = "text", from = 0), Segment(kind = "tools", ids = listOf("c1", "c2")))
                ),
                // A field this app does not model must not break the merge.
                "futureField" to buildJsonObject { put("nested", true) }
            )
        )
        assertEquals(LiveTurn.Applied.Ok, turn.apply(push(2, 1, patch)))
        val merged = assertNotNull(turn.snapshot)
        assertEquals("第一段，第二段", merged.text)
        assertEquals("思考", merged.reasoning)
        assertEquals("done", merged.phase)
        assertEquals("正在收尾", merged.activity)
        assertEquals(listOf("c1", "c2"), merged.toolCalls.map { it.id })
        assertEquals(listOf("text", "tools"), merged.segments.map { it.kind })
        assertEquals(2, turn.seq)
    }

    @Test
    fun aGapIsReportedRatherThanApplied() {
        val turn = LiveTurn()
        turn.apply(push(4, 0, base = snapshot("x")))
        assertEquals(LiveTurn.Applied.Gap, turn.apply(push(9, 8, SnapshotPatch(textDelta = "丢了"))))
        assertEquals("x", turn.snapshot?.text, "the patch is not applied onto a stale base")
        assertEquals(4, turn.seq)
    }

    @Test
    fun aPatchWithNoSnapshotAtAllIsAGap() {
        val turn = LiveTurn()
        assertEquals(LiveTurn.Applied.Gap, turn.apply(push(1, 0, SnapshotPatch(textDelta = "x"))))
        assertNull(turn.snapshot)
    }

    @Test
    fun acceptingASeqWithoutApplyingUnblocksTheNextFrame() {
        // What happens when `turn.snapshot` answers null: the turn ended, and
        // holding the old seq would make every later frame look like a gap.
        val turn = LiveTurn()
        turn.apply(push(2, 0, base = snapshot("a")))
        turn.acceptSeq(7)
        assertEquals(LiveTurn.Applied.Ok, turn.apply(push(8, 7, SnapshotPatch(textDelta = "b"))))
        assertEquals("ab", turn.snapshot?.text)
    }

    @Test
    fun resetClearsEverythingAndBumpsTheRevision() {
        val turn = LiveTurn()
        turn.apply(push(3, 0, base = snapshot("a")))
        val before = turn.revision
        turn.reset()
        assertNull(turn.snapshot)
        assertEquals(0, turn.seq)
        assertEquals(before + 1, turn.revision)
    }

    @Test
    fun historyIsReplacedWholesale() {
        val turn = LiveTurn()
        turn.setHistory(listOf(TurnRecord(taskId = "t0", prompt = "旧")))
        assertEquals(1, turn.history.size)
        turn.setHistory(emptyList())
        assertEquals(0, turn.history.size)
    }

    /**
     * The latest unanswered question is the one on screen: a turn can ask more
     * than once (a second permission while the first is unanswered), and the
     * new one is what the user has to answer.
     */
    @Test
    fun theOpenInteractionIsTheLatestUnansweredOne() {
        val withThree = snapshot().copy(
            interactions = listOf(
                Interaction(id = "i1", answeredAt = 1),
                Interaction(id = "i2"),
                Interaction(id = "i3")
            )
        )
        assertEquals("i3", withThree.openInteraction?.id)
        assertEquals("i2", withThree.copy(interactions = withThree.interactions.take(2)).openInteraction?.id)
        assertNull(withThree.copy(interactions = withThree.interactions.take(1)).openInteraction)
    }
}
