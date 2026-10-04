package com.skidsense.mobile.app

import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.Protocol
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.rc.u32be
import com.skidsense.mobile.transport.RcException
import com.skidsense.mobile.transport.RemoteCallError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Attachments, as the desktop's `UploadStash` (`src/main/remote/uploads.ts`)
 * accepts them: `begin` with a declared size, then numbered chunks at exactly
 * consecutive offsets, then the id is handed to `turn.prompt`.
 *
 * The client side of the contract, which is what this class enforces before
 * anything goes on the wire:
 *
 * - one chunk is at most `UPLOAD_CHUNK` (384 KiB) of raw bytes, base64'd into
 *   the frame;
 * - `offset` is exactly what has been sent so far — the desktop refuses a gap,
 *   an overlap or a rewrite with `bad-request`, and there is no way to repair
 *   that on a live upload, so **a failed chunk is never retried at a different
 *   offset**: the upload is aborted and started again;
 * - a file is at most `MAX_UPLOAD` (20 MiB), and at most 8 are open at once;
 * - the desktop expires an upload after 10 minutes idle, so a started upload is
 *   finished promptly or aborted.
 *
 * One upload is consumed by exactly one `turn.prompt`; an id that is still
 * unfinished when the prompt is sent fails the whole turn on the desktop, which
 * this class prevents by refusing to hand over an unfinished id.
 */
class UploadManager(
    private val call: suspend (method: String, params: JsonObject) -> JsonObject?
) {
    /** How many files may be open at once (the desktop's `MAX_OPEN`). */
    val maxOpen: Int = 8

    private val open = LinkedHashMap<String, Upload>()

    class Upload(
        val id: String,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val bytes: ByteArray,
        var sent: Long = 0
    ) {
        val complete: Boolean get() = sent >= size
        val progress: Float get() = if (size == 0L) 1f else (sent.toDouble() / size.toDouble()).toFloat()
    }

    /** An attachment the user has picked but not sent yet. */
    data class Draft(val id: String, val name: String, val size: Long, val mimeType: String?)

    val drafts: List<Draft> get() = open.values.map { Draft(it.id, it.name, it.size, it.mimeType) }

    val count: Int get() = open.size

    val totalBytes: Long get() = open.values.sumOf { it.size }

    /**
     * Register a file and send it. Small enough files go in one chunk; anything
     * larger is sliced at `UPLOAD_CHUNK`.
     *
     * The whole file is held in memory: the phone's picker gives bytes, the
     * desktop's cap is 20 MiB, and a partial stream would need a second source
     * of truth for the offset.
     */
    suspend fun begin(name: String, mimeType: String?, bytes: ByteArray): Upload {
        if (open.size >= maxOpen) throw RcException("too-many", "同时上传的附件不能超过 $maxOpen 个")
        if (bytes.size > Protocol.MAX_UPLOAD) {
            throw RcException("too-large", "单个附件不能超过 ${Protocol.MAX_UPLOAD / 1024 / 1024} MiB")
        }
        val existing = open.values.sumOf { it.size } + bytes.size
        if (existing > Protocol.MAX_UPLOAD) {
            throw RcException("too-large", "附件合计不能超过 ${Protocol.MAX_UPLOAD / 1024 / 1024} MiB")
        }
        val id = startId(name)
        val started = call(
            "upload.begin",
            buildJsonObject {
                put("name", name)
                if (mimeType != null) put("mimeType", mimeType)
                put("size", bytes.size)
            }
        )
        val uploadId = (started?.get("id") as? JsonPrimitive)?.contentOrNull
            ?: throw RcException("bad-response", "电脑没有给出上传 id")
        val upload = Upload(if (uploadId.isEmpty()) id else uploadId, name, mimeType, bytes.size.toLong(), bytes)
        open[upload.id] = upload
        try {
            sendChunks(upload)
        } catch (error: Throwable) {
            // Never leave a half-uploaded id on the desktop: it would be
            // referenced as if it were complete, or expire on its own later.
            runCatching { abort(upload.id) }
            throw error
        }
        return upload
    }

    private suspend fun sendChunks(upload: Upload) {
        var offset = upload.sent.toInt()
        while (offset < upload.bytes.size) {
            val end = minOf(offset + Protocol.UPLOAD_CHUNK, upload.bytes.size)
            val slice = upload.bytes.copyOfRange(offset, end)
            val result = call(
                "upload.chunk",
                buildJsonObject {
                    put("id", upload.id)
                    put("offset", offset)
                    put("data", B64u.encode(slice))
                }
            )
            val received = (result?.get("received") as? JsonPrimitive)?.intOrNull
            if (received != null && received.toLong() != end.toLong()) {
                // The desktop's count disagrees with ours. The offset it wants
                // next is not the one we would send, and it cannot be repaired
                // in place — so this upload is dead and must be started over.
                throw RcException("upload-desync", "上传进度与电脑不一致，请重新添加这个附件")
            }
            offset = end
            upload.sent = end.toLong()
        }
    }

    /**
     * The ids for `turn.prompt`, which consumes them. Refuses to hand over an
     * id that is unfinished, and forgets the ones it returns.
     */
    fun takeIds(): List<String> {
        val unfinished = open.values.filterNot { it.complete }
        if (unfinished.isNotEmpty()) {
            throw RcException("upload-incomplete", "附件「${unfinished.first().name}」还没传完")
        }
        val ids = open.keys.toList()
        open.clear()
        return ids
    }

    /** Put back the ids of a prompt that failed, so they can be sent again. */
    fun restore(uploads: List<Upload>) {
        for (upload in uploads) open[upload.id] = upload
    }

    suspend fun abort(id: String) {
        open.remove(id)
        runCatching { call("upload.abort", buildJsonObject { put("id", id) }) }
    }

    /** Abort everything open — called when leaving the screen, since the desktop expires them anyway. */
    suspend fun abortAll() {
        val ids = open.keys.toList()
        open.clear()
        for (id in ids) runCatching { call("upload.abort", buildJsonObject { put("id", id) }) }
    }

    /**
     * The desktop generates the real id; this is only used when a response
     * arrives without one, so the local map still has a key.
     */
    private fun startId(name: String): String =
        B64u.encode(Primitives.sha256(utf8Bytes(name), u32be(open.size.toLong())).copyOfRange(0, 12))

    private fun utf8Bytes(text: String): ByteArray = text.encodeToByteArray()

    companion object {
        /** The wire form of a failed upload, for a test or a UI message. */
        fun describe(error: Throwable): String = when (error) {
            is RemoteCallError -> error.message ?: error.code
            is RcException -> error.message ?: error.code
            else -> error.message ?: "上传失败"
        }
    }
}
