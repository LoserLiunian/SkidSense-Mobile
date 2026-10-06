package com.skidsense.mobile.app

import com.skidsense.mobile.model.ListDirResult
import com.skidsense.mobile.model.ReadFileResult
import com.skidsense.mobile.model.WriteFileResult
import com.skidsense.mobile.transport.RcJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `mtime` on the wire is Node's `mtimeMs` — a *fractional* millisecond on
 * APFS/ext4/NTFS (spec §7, C7). Decoded into a `Long`, `fs.list` failed on the
 * first such file and the file browser read "这里是空的" against every real
 * desktop (N01); `fs.read` and `fs.write` had the same `Long`.
 */
class FsWireTest {
    /** The shape the desktop's `listDir` really answers (`src/main/files.ts:112`). */
    private val realListAnswer = """
        {"entries":[
            {"name":"hello.txt","path":"hello.txt","kind":"file","size":6,"mtime":1791221032611.1711,"git":null},
            {"name":"notes.md","path":"notes.md","kind":"file","size":12,"mtime":1791221032000,"git":"modified"}
        ],"truncated":false}
    """.trimIndent()

    @Test
    fun aFractionalMtimeDecodesInsteadOfKillingTheWholeList() {
        val result = RcJson.decodeFromString(ListDirResult.serializer(), realListAnswer)
        assertEquals(listOf("hello.txt", "notes.md"), result.entries.map { it.name })
        assertEquals(1791221032611L, result.entries[0].mtime, "the fraction may be dropped, not the entry")
        assertEquals(1791221032000L, result.entries[1].mtime)
    }

    @Test
    fun fsReadAndFsWriteDecodeTheSameFractionalMtime() {
        val read = RcJson.decodeFromString(
            ReadFileResult.serializer(),
            """{"path":"a.txt","encoding":"utf8","text":"hi","mime":"text/plain","size":2,"mtime":1791221032611.1711,"lines":1,"etag":"e1"}"""
        )
        assertEquals(1791221032611L, read.mtime)
        val write = RcJson.decodeFromString(
            WriteFileResult.serializer(),
            """{"ok":true,"etag":"e2","mtime":1791221032611.1711}"""
        )
        assertEquals(1791221032611L, write.mtime)
        val conflict = RcJson.decodeFromString(
            WriteFileResult.serializer(),
            """{"ok":false,"conflict":true}"""
        )
        assertEquals(null, conflict.mtime, "a refusal carries no mtime and must still parse")
    }
}
