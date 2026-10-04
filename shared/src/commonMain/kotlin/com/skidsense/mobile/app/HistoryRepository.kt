package com.skidsense.mobile.app

import com.skidsense.mobile.api.BackendClient
import com.skidsense.mobile.model.SessionRow
import com.skidsense.mobile.model.TurnRecord
import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.CryptoError
import com.skidsense.mobile.rc.HistoryCrypto
import com.skidsense.mobile.rc.KeyPair
import com.skidsense.mobile.transport.RcJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** One decrypted session from the backend's encrypted store (spec §11). */
@Serializable
data class HistorySession(
    val v: Int = 1,
    val row: SessionRow = SessionRow(key = ""),
    val turns: List<TurnRecord> = emptyList()
)

data class HistoryEntry(
    val sessionKey: String,
    val epoch: Long,
    val updatedAt: Long,
    val size: Long,
    val row: SessionRow?,
    val turns: List<TurnRecord>,
    val error: String? = null
)

/**
 * The backend's encrypted history: fetch the key wraps for this device, unwrap
 * the host's history key with the device's static X25519 key, list blobs,
 * download and open them. The backend never sees plaintext; every blob is
 * bound to its host, session key and epoch by its AAD, so one session's
 * ciphertext cannot be passed off as another's.
 *
 * Read-only, and searching happens here, over decrypted text — the backend
 * cannot search what it cannot read.
 */
class HistoryRepository(
    private val backend: BackendClient,
    private val identity: KeyPair,
    private val hostId: String
) {
    private val lock = Mutex()
    /** epoch → the host's history key for that epoch. */
    private val keys = HashMap<Long, ByteArray>()

    suspend fun keysKnown(): Boolean = lock.withLock { keys.isNotEmpty() }

    /** Fetch (or refresh) this device's copies of the host's history keys. */
    suspend fun refreshKeys(deviceId: String) {
        val rows = backend.historyKeys(hostId, deviceId)
        val opened = HashMap<Long, ByteArray>()
        for (row in rows) {
            val wrapped = try {
                B64u.decode(row.wrapped, 80)
            } catch (_: CryptoError) {
                continue
            }
            val key = try {
                HistoryCrypto.unwrapKey(wrapped, identity, HistoryCrypto.wrapContext(hostId, row.epoch))
            } catch (_: CryptoError) {
                continue // wrapped to another device, or a different epoch's context
            }
            opened[row.epoch] = key
        }
        lock.withLock { keys.putAll(opened) }
    }

    /** The newest epoch this device holds a key for. */
    suspend fun currentEpoch(): Long? = lock.withLock { keys.keys.maxOrNull() }

    /**
     * List and decrypt everything this device can read, newest first. A row
     * whose epoch has no key is reported with [HistoryEntry.error] rather than
     * dropped, so the UI can say the host has not re-wrapped for this device.
     */
    suspend fun load(since: Long = 0): List<HistoryEntry> {
        val rows = backend.historySessions(hostId, since)
        return rows.map { row -> open(row.sessionKey, row.epoch, row.updatedAt, row.size) }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun open(sessionKey: String, epoch: Long, updatedAt: Long, size: Long): HistoryEntry {
        val key = lock.withLock { keys[epoch] }
            ?: return HistoryEntry(sessionKey, epoch, updatedAt, size, null, emptyList(), "这台手机没有第 $epoch 代历史密钥，等电脑端重新上传后再看")
        val row = try {
            backend.historyBlob(hostId, sessionKey)
        } catch (error: Throwable) {
            return HistoryEntry(sessionKey, epoch, updatedAt, size, null, emptyList(), error.message ?: "无法下载")
        }
        return open(row.blob, sessionKey, row.epoch, row.updatedAt)
    }

    /** Open one blob with the key for its epoch. */
    fun open(blobBase64: String, sessionKey: String, epoch: Long, updatedAt: Long): HistoryEntry {
        val key = keys[epoch] ?: return HistoryEntry(sessionKey, epoch, updatedAt, 0, null, emptyList(), "没有第 $epoch 代历史密钥")
        val blob = try {
            B64u.decode(blobBase64)
        } catch (error: CryptoError) {
            return HistoryEntry(sessionKey, epoch, updatedAt, 0, null, emptyList(), "密文编码无效")
        }
        val plaintext = try {
            HistoryCrypto.openBlob(key, HistoryCrypto.historyAad(hostId, sessionKey, epoch), blob)
        } catch (_: CryptoError) {
            return HistoryEntry(sessionKey, epoch, updatedAt, blob.size.toLong(), null, emptyList(), "解密失败：这段历史可能属于别的会话或纪元")
        }
        return try {
            val decoded = RcJson.decodeFromString(HistorySession.serializer(), plaintext.decodeToString())
            HistoryEntry(sessionKey, epoch, updatedAt, blob.size.toLong(), decoded.row, decoded.turns)
        } catch (error: Exception) {
            HistoryEntry(sessionKey, epoch, updatedAt, blob.size.toLong(), null, emptyList(), "内容无法解析：${error.message}")
        }
    }

    companion object {
        /**
         * Local search over decrypted content. Case-insensitive, and the match
         * tells the UI what it found so the row can show the context.
         */
        fun search(entries: List<HistoryEntry>, query: String): List<HistoryEntry> {
            val needle = query.trim()
            if (needle.isEmpty()) return entries
            return entries.filter { entry ->
                entry.row?.title?.contains(needle, ignoreCase = true) == true ||
                    entry.turns.any { turn ->
                        turn.prompt.contains(needle, ignoreCase = true) ||
                            turn.snapshot.text.contains(needle, ignoreCase = true) ||
                            turn.snapshot.reasoning.contains(needle, ignoreCase = true) ||
                            turn.snapshot.toolCalls.any { call ->
                                call.summary?.contains(needle, ignoreCase = true) == true ||
                                    call.name.contains(needle, ignoreCase = true)
                            }
                    } ||
                    entry.sessionKey.contains(needle, ignoreCase = true)
            }
        }

        /** A one-line excerpt showing where a hit was. */
        fun excerpt(entry: HistoryEntry, query: String): String {
            val needle = query.trim()
            if (needle.isEmpty()) return entry.row?.preview.orEmpty()
            val title = entry.row?.title
            if (title != null && title.contains(needle, ignoreCase = true)) return title
            for (turn in entry.turns) {
                val index = turn.snapshot.text.indexOf(needle, ignoreCase = true)
                if (index >= 0) return snippet(turn.snapshot.text, index, needle.length)
                val promptIndex = turn.prompt.indexOf(needle, ignoreCase = true)
                if (promptIndex >= 0) return snippet(turn.prompt, promptIndex, needle.length)
            }
            return entry.row?.preview.orEmpty()
        }

        private fun snippet(text: String, index: Int, length: Int): String {
            val from = (index - 24).coerceAtLeast(0)
            val to = (index + length + 40).coerceAtMost(text.length)
            return (if (from > 0) "…" else "") + text.substring(from, to).replace('\n', ' ') + (if (to < text.length) "…" else "")
        }

    }
}
