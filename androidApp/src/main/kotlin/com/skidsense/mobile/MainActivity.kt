package com.skidsense.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.skidsense.mobile.app.AndroidEnvironment
import com.skidsense.mobile.app.SkidSenseApp
import com.skidsense.mobile.platform.BiometricGate
import com.skidsense.mobile.ui.RcTheme

/**
 * The Android entry point: builds the environment once, then hands it to the
 * shared app. It extends `FragmentActivity` because that is what
 * `BiometricPrompt` requires.
 */
class MainActivity : FragmentActivity() {
    private var environment: AndroidEnvironment? = null

    /**
     * A `skidsense://pair/1?d=…` link the app was opened with — a tapped QR
     * code, as opposed to a scanned one. Read from the intent, then used by the
     * shell to open the pairing screen.
     */
    private var launchLink: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val env = AndroidEnvironment(this)
        environment = env
        launchLink = pairingLink(intent)
        setContent {
            RcTheme {
                SkidSenseApp(
                    backend = env.backend,
                    carriers = env.carriers,
                    platform = env.platform,
                    biometrics = BiometricGate { title, onResult -> promptBiometric(title, onResult) },
                    launchLink = launchLink
                )
            }
        }
    }

    /** A second tap while the app is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingLink(intent)?.let { launchLink = it }
    }

    private fun pairingLink(intent: Intent?): String? =
        intent?.dataString?.takeIf { it.startsWith("skidsense://") }

    override fun onDestroy() {
        super.onDestroy()
        environment?.close()
        environment = null
    }

    /**
     * The app-level lock, asked for only when the user turned it on. When the
     * device has neither a fingerprint, a face nor a device credential, the
     * gate passes immediately: the lock wraps nothing, so refusing to open the
     * app would be a lockout rather than a protection. The keys themselves are
     * held by the Keystore regardless.
     */
    private fun promptBiometric(title: String, onResult: (Boolean) -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(true)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }

                override fun onAuthenticationFailed() {
                    // One non-matching finger is not a refusal; keep waiting.
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }
}
