package com.skidsense.mobile.app

import com.skidsense.mobile.rc.Pairing
import com.skidsense.mobile.rc.PairingPayload

/**
 * The pairing link the desktop shows can be tapped as well as scanned: the
 * manifest registers `skidsense://` so the phone opens the app instead of a
 * browser, and the intent's data is handed here.
 *
 * Only the pairing link is recognised; anything else on the scheme is ignored
 * rather than guessed at.
 */
object DeepLink {
    fun parse(url: String?): PairingPayload? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("skidsense://")) return null
        return try {
            Pairing.decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
