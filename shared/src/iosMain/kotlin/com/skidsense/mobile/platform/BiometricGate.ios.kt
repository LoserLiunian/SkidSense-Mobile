package com.skidsense.mobile.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The app lock on iOS: the system's own prompt (Face ID, Touch ID, or the
 * passcode), as `BiometricPrompt` is on Android.
 *
 * It used to answer `true` to every request, so turning the lock on did
 * nothing on this platform while the setting said it was on. When the device
 * has no way to authenticate at all the gate still lets the user in — that is
 * `BiometricGate`'s contract, since the lock guards the screen, not a key.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosBiometricGate(): BiometricGate = BiometricGate { title, onResult ->
    val context = LAContext()
    if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) {
        onResult(true)
        return@BiometricGate
    }
    context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = title) { success, _ ->
        dispatch_async(dispatch_get_main_queue()) { onResult(success) }
    }
}
