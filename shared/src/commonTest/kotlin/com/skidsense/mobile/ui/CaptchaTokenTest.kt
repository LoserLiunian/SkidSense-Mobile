package com.skidsense.mobile.ui

import com.skidsense.mobile.platform.CAPTCHA_SEND_JS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The captcha pages take two values from the server's `/api/status` and put
 * them inside a script string and an HTML attribute. Only the plain tokens the
 * providers issue may get there.
 */
class CaptchaTokenTest {
    @Test
    fun providerTokensPassThrough() {
        assertEquals("54088bb07d2df3c46b79f80300b0abbe", captchaToken("54088bb07d2df3c46b79f80300b0abbe"))
        assertEquals("0x4AAAAAAA-b_c", captchaToken("0x4AAAAAAA-b_c"))
    }

    @Test
    fun anythingThatCouldEscapeTheStringIsDropped() {
        assertEquals("", captchaToken("x'});alert(1);//"))
        assertEquals("", captchaToken("\" onload=\"alert(1)"))
        assertEquals("", captchaToken(""))
        assertFalse(GeeTestPage.html("x'});alert(1);//").contains("alert(1)"))
        assertFalse(TurnstilePage.html("\" onload=\"x").contains("onload"))
    }

    @Test
    fun bothPagesHandTheResultOverThroughTheBridgeMethod() {
        assertTrue(CAPTCHA_SEND_JS.contains(".invoke("), "the bridge is an object; calling it as a function throws")
        assertTrue(GeeTestPage.html("abc").contains(CAPTCHA_SEND_JS))
        assertTrue(TurnstilePage.html("abc").contains(CAPTCHA_SEND_JS))
    }
}
