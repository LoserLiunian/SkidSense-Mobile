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
     * A `skidsense://pair/1?d=…` link as a *one-shot event*, not a value
     * (S32): an incrementing number per accepted link, because the string
     * itself is what made a second tap of the same link a no-op
     * (`remember(link)` never re-ran), and what let a recreated Activity
     * replay an old intent's data. The Compose side keeps the string for
     * prefill; the *decision* to act keys on this counter.
     */
    private var launchSeq by mutableStateOf(0)
    private var launchLink: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val env = AndroidEnvironment(this)
        environment = env
        // A link is taken only from a genuine delivery: a recreation
        // (savedInstanceState set) or a task restored from history replays the
        // last intent's data rather than a new tap.
        if (savedInstanceState == null && !fromHistory(intent)) {
            pairingLink(intent)?.let {
                launchLink = it
                launchSeq = 1
            }
        }
        setContent {
            RcTheme {
                SkidSenseApp(
                    backend = env.backend,
                    carriers = env.carriers,
                    platform = env.platform,
                    biometrics = BiometricGate { title, onResult -> promptBiometric(title, onResult) },
                    launchSeq = launchSeq,
                    launchLink = launchLink,
                    onLaunchHandled = ::consumeLaunchLink
                )
            }
        }
    }

    /** A second tap while the app is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (fromHistory(intent)) return
        pairingLink(intent)?.let {
            launchLink = it
            launchSeq += 1
        }
    }

    /**
     * The link the shell already consumed is erased at the source too: as long
     * as the intent still carried the data, the next recreation replayed it.
     */
    private fun consumeLaunchLink() {
        pairingLink(intent)?.let {
            intent.data = null
            setIntent(intent)
        }
    }

    private fun fromHistory(intent: Intent?): Boolean =
        intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

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
