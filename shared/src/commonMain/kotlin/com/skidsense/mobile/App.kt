package com.skidsense.mobile

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.skidsense.mobile.app.SkidSenseApp
import com.skidsense.mobile.transport.CarrierFactory
import com.skidsense.mobile.ui.RcTheme

/**
 * The shared root. Android's activity and iOS's `MainViewController` both call
 * this with the same three things, so every screen above it is common code.
 *
 * TODO(iOS): `IosEnvironment` still needs the Keychain (secrets), a file store
 * under Application Support, a biometric gate and a QR scanner before this
 * entry point is usable; see `platform/DevicePlatform.ios.kt`.
 */
@Composable
fun App(
    backend: com.skidsense.mobile.api.BackendClient,
    carriers: CarrierFactory,
    platform: com.skidsense.mobile.platform.DevicePlatform,
    biometrics: com.skidsense.mobile.platform.BiometricGate,
    launchLink: String? = null
) {
    RcTheme {
        SkidSenseApp(
            backend = backend,
            carriers = carriers,
            platform = platform,
            biometrics = biometrics,
            launchLink = launchLink
        )
    }
}

/** Placeholder shown by the iOS previews until the environment above exists. */
@Composable
fun UnavailableApp(message: String) {
    Text(message)
}
