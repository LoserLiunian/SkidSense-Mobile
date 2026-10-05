package com.skidsense.mobile.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the app opens. A stored session survives a restart, so a cold start
 * with one lands on the computer list; the app used to open on the login form
 * every time, with the server field back at its default.
 */
class RoutingTest {
    @Test
    fun aSignedInStartLandsOnTheComputers() {
        assertEquals(Screen.Hosts, landingScreen(AppState(ready = true, user = "rcadmin")))
    }

    @Test
    fun aSignedOutStartLandsOnLogin() {
        assertEquals(Screen.Login, landingScreen(AppState(ready = true, user = null)))
    }
}
