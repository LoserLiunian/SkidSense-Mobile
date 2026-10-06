package com.skidsense.mobile

import com.skidsense.mobile.ui.TerminalChannel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fixtures produced by the *desktop's* own code, fed to the phone's decoders —
 * the round-2 review's evidence captured the desktop's real wire shapes, and
 * the shape is what must keep parsing.
 */
class DesktopFixtureTest {
    /**
     * A real desktop terminal exit (`src/main/index.ts:360`): node-pty answers
     * a SIGKILL with `exitCode 0, signal 9`, and RemoteManager publishes
     * `{key, code, signal, tail}` with **no `reason`**. The phone used to look
     * for `reason` only and render this as a clean "退出码 0" (C2/S36).
     */
    @Test
    fun tuiExitFromTheDesktop() {
        val exit = TerminalChannel.decodeExit(
            com.skidsense.mobile.transport.RcJson.parseToJsonElement(
                """{"key":"claude:review2-tui","code":0,"signal":9,"tail":"started-before-kill"}"""
            )
        )!!
        assertEquals("claude:review2-tui", exit.key)
        assertEquals(0, exit.code)
        assertEquals("被信号 9 终止", exit.reason, "a signal death must not read as a clean exit")
        assertEquals("started-before-kill", exit.tail)
    }

    /** A current desktop sends `reason` itself; it wins over the rebuilt one. */
    @Test
    fun tuiExitWithAReasonKeepsTheHostsWords() {
        val exit = TerminalChannel.decodeExit(
            com.skidsense.mobile.transport.RcJson.parseToJsonElement(
                """{"key":"claude:1","code":1,"signal":null,"reason":"spawn /bin/sh ENOENT","tail":"err"}"""
            )
        )!!
        assertEquals("spawn /bin/sh ENOENT", exit.reason)
    }
}
