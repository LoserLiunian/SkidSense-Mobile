package com.skidsense.mobile.platform

import androidx.compose.runtime.Composable
import com.skidsense.mobile.store.FileStore
import com.skidsense.mobile.store.SecretStore

/**
 * What only the platform can answer. Everything else in the app is common
 * code so the iOS client can be built from the same sources.
 */
interface DevicePlatform {
    val secrets: SecretStore
    val files: FileStore

    /** `android` / `ios`, for `hello.app.platform` and `POST /devices`. */
    val name: String

    /** An opaque device label for `POST /devices`, e.g. "Pixel 8". */
    val model: String

    /** A random id for this install, kept so "this device" can be highlighted. */
    fun installId(): String

    /**
     * Call [onChange] whenever the phone's network changes — Wi-Fi joined or
     * left, cellular back — so the connection can retry at once instead of
     * waiting out its backoff, and a relay connection can look for the LAN.
     * Returns the unsubscribe. A platform that cannot tell does nothing; the
     * return to the foreground is the fallback.
     */
    fun watchNetwork(onChange: () -> Unit): () -> Unit = {}
}

fun interface BiometricGate {
    /**
     * Ask for the system unlock (fingerprint / face / device credential).
     * [onResult] says whether it succeeded. When unavailable, succeed
     * immediately: the lock is a convenience only, never a key holder.
     */
    fun authenticate(title: String, onResult: (Boolean) -> Unit)
}

/** A QR scanner, presented as a full-screen flow. Null when the platform has none. */
fun interface PairScanner {
    /**
     * Launch the scanner; [onResult] gets the scanned `skidsense://` text, or
     * null when the user backed out.
     */
    fun scan(onResult: (String?) -> Unit)
}

@Composable
expect fun rememberPairScanner(): PairScanner?
