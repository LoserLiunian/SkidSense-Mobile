package com.skidsense.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The two labels the device and host lists put next to a name. */
class PresenceAndTimeTest {
    /** `last_seen_at` is Unix seconds; it used to print as days since 1970 (「20732天 09:06」). */
    @Test
    fun lastSeenReadsAsTimeAgo() {
        val now = 1_791_277_590_000L
        assertEquals("刚刚", formatAgo(now - 20_000, now))
        assertEquals("5 分钟前", formatAgo(now - 5 * 60_000, now))
        assertEquals("3 小时前", formatAgo(now - 3 * 3_600_000, now))
        assertEquals("2 天前", formatAgo(now - 2 * 86_400_000L, now))
        // A clock a little ahead of the phone's is not "in the future".
        assertEquals("刚刚", formatAgo(now + 30_000, now))
    }

    /**
     * The backend's `online` is relay presence. A computer with only 局域网直连
     * on is never online there, and 离线 sent people away from a host they
     * could reach.
     */
    @Test
    fun relayAbsenceIsNotCalledOfflineWhenThereIsALanRoute() {
        assertEquals("在线", presenceLabel(true, listOf("192.168.1.20")))
        assertEquals("中继离线", presenceLabel(false, listOf("192.168.1.20")))
        assertEquals("离线", presenceLabel(false, emptyList()))
        assertEquals(null, presenceLabel(null, emptyList()))
    }
}
