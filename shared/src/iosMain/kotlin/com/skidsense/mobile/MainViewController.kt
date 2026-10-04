package com.skidsense.mobile

import androidx.compose.ui.window.ComposeUIViewController

/**
 * The iOS entry point, called from `iosApp/iosApp/ContentView.swift`.
 *
 * TODO(iOS): `IosEnvironment` still needs the Keychain, a real file store, a
 * biometric prompt and a QR scanner before this is a usable app — see the
 * TODOs in `platform/DevicePlatform.ios.kt` and `IosEnvironment`.
 */
fun MainViewController() = ComposeUIViewController {
    val environment = IosEnvironment()
    environment()
}
