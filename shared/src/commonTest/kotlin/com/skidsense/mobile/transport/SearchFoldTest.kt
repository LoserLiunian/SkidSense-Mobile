package com.skidsense.mobile.transport

import com.skidsense.mobile.app.SearchFold
import com.skidsense.mobile.model.SearchFileResult
import com.skidsense.mobile.model.SearchMatch
import com.skidsense.mobile.model.SearchProgressPush
import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.Primitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The files search's progress fold (spec §6.4): progress arrives as events on
 * the connection that asked, under the id the device chose — so a device must
 * ignore another device's search, and a `done` or `error` ends it.
 */
class SearchFoldTest {
    private fun files(path: String, lines: List<Int>) = SearchFileResult(
        path = path,
        matches = lines.map { SearchMatch(line = it, column = 0, text = "line $it") }
    )

    @Test
    fun progressAccumulatesAndDoneCarriesTheTotal() {
        val fold = SearchFold(id = "s1", query = "needle")
        fold.apply(SearchProgressPush(id = "s1", kind = "files", files = listOf(files("a.kt", listOf(1, 2)))))
        fold.apply(SearchProgressPush(id = "s1", kind = "files", files = listOf(files("b.kt", listOf(9)))))
        assertEquals(2, fold.state.files.size)
        assertFalse(fold.state.done)

        fold.apply(SearchProgressPush(id = "s1", kind = "done", totalMatches = 3, fileCount = 2))
        assertTrue(fold.state.done)
        assertEquals(3, fold.state.totalMatches)
    }

    @Test
    fun anotherDevicesSearchIsIgnored() {
        val fold = SearchFold(id = "s1", query = "needle")
        fold.apply(SearchProgressPush(id = "someone-else", kind = "files", files = listOf(files("x.kt", listOf(1)))))
        assertEquals(emptyList(), fold.state.files)
        assertFalse(fold.state.done)
    }

    @Test
    fun anErrorEndsTheSearchWithItsMessage() {
        val fold = SearchFold(id = "s1", query = "needle")
        fold.apply(SearchProgressPush(id = "s1", kind = "error", error = "工作区不可读"))
        assertTrue(fold.state.done)
        assertEquals("工作区不可读", fold.state.error)
    }

    @Test
    fun progressAfterDoneIsIgnored() {
        val fold = SearchFold(id = "s1", query = "needle")
        fold.apply(SearchProgressPush(id = "s1", kind = "done", totalMatches = 1))
        fold.apply(SearchProgressPush(id = "s1", kind = "files", files = listOf(files("late.kt", listOf(2)))))
        assertEquals(emptyList(), fold.state.files, "a late frame does not reopen a finished search")
    }

    @Test
    fun cancellingMarksItDoneSoNoMoreFramesAreExpected() {
        val fold = SearchFold(id = "s1", query = "needle")
        fold.apply(SearchProgressPush(id = "s1", kind = "files", files = listOf(files("a.kt", listOf(1)))))
        fold.cancelled()
        assertTrue(fold.state.done)
        fold.apply(SearchProgressPush(id = "s1", kind = "files", files = listOf(files("b.kt", listOf(2)))))
        assertEquals(1, fold.state.files.size)
    }

    @Test
    fun theSearchIdIsOursAndNamespacingIsTheHostsBusiness() {
        // The device mints the id; the desktop prefixes it with the connection
        // id internally and echoes ours back on `search.progress`.
        val fold = SearchFold(id = "s1", query = "q")
        assertEquals("s1", fold.id)
    }
}

/** Host addresses: what the QR carried, and what `GET /hosts` says now. */
class HostEndpointTest {
    private val key = Primitives.generateKeyPair().pub

    private fun endpoint(addrs: List<String>, port: Int = 47290) = HostEndpoint("h", key, "d", addrs, port)

    @Test
    fun routesFollowTheOrderTheyWereLearned() {
        val endpoint = endpoint(listOf("192.168.1.20"))
        assertEquals(
            listOf<Route>(Route.Lan("192.168.1.20", 47290), Route.Relay),
            endpoint.routes()
        )
    }

    @Test
    fun aLearnedAddressIsTriedFirstAndTheOldOneIsKeptAsAFallback() {
        val endpoint = endpoint(listOf("192.168.1.20"))
        assertTrue(endpoint.learn(listOf("10.0.0.7")))
        assertEquals(
            listOf<Route>(Route.Lan("10.0.0.7", 47290), Route.Lan("192.168.1.20", 47290), Route.Relay),
            endpoint.routes()
        )
        assertEquals(listOf("10.0.0.7"), endpoint.addresses, "the current address is what the UI shows")
    }

    @Test
    fun theSameAddressTwiceIsNotAChange() {
        val endpoint = endpoint(listOf("192.168.1.20"))
        assertFalse(endpoint.learn(listOf("192.168.1.20")))
        assertFalse(endpoint.learn(listOf("192.168.1.20", "192.168.1.20")))
    }

    @Test
    fun anEmptyBackendListChangesNothing() {
        val endpoint = endpoint(listOf("192.168.1.20"))
        assertFalse(endpoint.learn(emptyList()))
        assertEquals(listOf("192.168.1.20"), endpoint.addresses)
    }

    @Test
    fun aNewPortIsAlsoLearned() {
        val endpoint = endpoint(listOf("192.168.1.20"), port = 47290)
        assertTrue(endpoint.learn(listOf("192.168.1.20"), port = 50000))
    }

    @Test
    fun theFallbackListIsBounded() {
        val endpoint = endpoint(listOf("192.168.1.20"))
        repeat(40) { index -> endpoint.learn(listOf("10.0.0.$index")) }
        // The relay is always last; the LAN candidates never grow without bound.
        assertTrue(endpoint.routes().size <= 17, "kept ${endpoint.routes().size} routes")
        assertEquals(Route.Lan("10.0.0.39", 47290), endpoint.routes().first())
    }

    @Test
    fun relayCanBeTurnedOff() {
        val endpoint = HostEndpoint("h", key, "d", listOf("192.168.1.20"), 47290, relayEnabled = false)
        assertEquals(listOf<Route>(Route.Lan("192.168.1.20", 47290)), endpoint.routes())
    }

    @Test
    fun aZeroPortMeansNoLanRoute() {
        val endpoint = endpoint(emptyList(), port = 0)
        assertEquals(listOf<Route>(Route.Relay), endpoint.routes())
    }

    @Test
    fun addressesAreDeduplicated() {
        val endpoint = endpoint(listOf("192.168.1.20", "192.168.1.20"))
        assertEquals(2, endpoint.routes().size)
        assertNotNull(endpoint.routes().firstOrNull { it is Route.Lan })
        assertNull(endpoint.routes().firstOrNull { route -> route is Route.Lan && route.address == "10.0.0.1" })
    }
}
