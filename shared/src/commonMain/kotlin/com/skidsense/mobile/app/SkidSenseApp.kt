package com.skidsense.mobile.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.skidsense.mobile.api.BackendClient
import com.skidsense.mobile.platform.BiometricGate
import com.skidsense.mobile.platform.DevicePlatform
import com.skidsense.mobile.transport.CarrierFactory
import com.skidsense.mobile.ui.AppLockedScreen
import com.skidsense.mobile.ui.FilesScreen
import com.skidsense.mobile.ui.GitScreen
import com.skidsense.mobile.ui.HistoryScreen
import com.skidsense.mobile.ui.HostListScreen
import com.skidsense.mobile.ui.LoginScreen
import com.skidsense.mobile.ui.PairingScreen
import com.skidsense.mobile.ui.SessionListScreen
import com.skidsense.mobile.ui.SessionScreen
import com.skidsense.mobile.ui.SettingsScreen
import com.skidsense.mobile.ui.TerminalScreen
import kotlinx.coroutines.launch

/** Which screen is showing. Screen state is simple; the app data lives in [AppController]. */
sealed interface Screen {
    data object Login : Screen
    data object Hosts : Screen
    data object Pairing : Screen
    data object Sessions : Screen
    data object Settings : Screen
    data object History : Screen
    data class Session(val key: String) : Screen
    data class Files(val root: String) : Screen
    data class Git(val root: String) : Screen
    data class Terminal(val key: String) : Screen
}

/**
 * Where the app opens once the controller has loaded, when no link asked for
 * somewhere else. A stored session survives a restart (the secret store keeps
 * it), so a signed-in user lands on their computers — not on a login form
 * that has forgotten even which server they use.
 */
internal fun landingScreen(state: AppState): Screen = if (state.user != null) Screen.Hosts else Screen.Login

/**
 * The shell: creates the one [AppController], holds the back stack, and routes
 * between screens. Everything else is a screen reading the controller's state.
 */
@Composable
fun SkidSenseApp(
    backend: BackendClient,
    carriers: CarrierFactory,
    platform: DevicePlatform,
    biometrics: BiometricGate,
    /** A `skidsense://` link the app was opened with, if any (a tapped QR code). */
    launchLink: String? = null
) {
    val scope = rememberCoroutineScope()
    val controller = remember(backend, carriers, platform) {
        AppController(
            backend = backend,
            carriers = carriers,
            secrets = platform.secrets,
            files = platform.files,
            platformName = platform.name,
            deviceModel = platform.model,
            scope = scope
        )
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Login) }
    var stack by remember { mutableStateOf<List<Screen>>(emptyList()) }
    var deepLink by remember(launchLink) { mutableStateOf(DeepLink.parse(launchLink)) }
    // Collected, not read: `.value` subscribes Compose to nothing, so the
    // effects below keyed on `state.ready` and `state.user` ran once with the
    // first value and never again — a tapped pairing link waited forever for
    // a `ready` it could not see, and so did the landing screen.
    val state by controller.state.collectAsState()

    fun go(next: Screen) {
        stack = stack + screen
        screen = next
    }

    fun back() {
        screen = stack.lastOrNull() ?: Screen.Hosts
        stack = stack.dropLast(1)
    }

    LaunchedEffect(Unit) { controller.start() }

    // The first screen is chosen once, when the stored session has been read.
    // It used to stay on Login whatever was stored: every cold start asked for
    // the password again, with the server field back at the default.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(state.ready) {
        if (!state.ready || landed) return@LaunchedEffect
        landed = true
        if (deepLink == null && screen == Screen.Login) screen = landingScreen(state)
    }

    // A tapped pairing link goes straight to the pairing screen, once the
    // controller has loaded (it needs to know which backend we are signed in to).
    //
    // Signed out, the link is kept rather than dropped: signing in changes
    // `state.user`, this runs again, and the pairing screen opens on the link
    // the user tapped instead of asking for it a second time.
    LaunchedEffect(deepLink, state.ready, state.user) {
        if (deepLink == null || !state.ready) return@LaunchedEffect
        if (state.user == null) {
            screen = Screen.Login
            return@LaunchedEffect
        }
        stack = emptyList()
        screen = Screen.Pairing
        deepLink = null
    }

    // A lock only ever shows after the app has been idle-locked; the first
    // unlock is asked for once, on launch, when the user turned it on.
    LaunchedEffect(state.biometricLock, state.ready) {
        if (state.ready && state.biometricLock && state.locked) {
            biometrics.authenticate("解锁 SkidSense") { ok -> if (ok) controller.unlock() }
        }
    }

    // Coming back to a paired host after being backgrounded is the common case:
    // reconnect when the app returns to the foreground.
    DisposableEffect(Unit) { onDispose { controller.disconnect() } }

    if (state.locked) {
        AppLockedScreen(onUnlock = { biometrics.authenticate("解锁 SkidSense") { ok -> if (ok) controller.unlock() } })
        return
    }

    when (val current = screen) {
        Screen.Login -> LoginScreen(controller) { if (deepLink == null) screen = Screen.Hosts }
        Screen.Hosts -> HostListScreen(
            app = controller,
            onOpenHost = { hostId ->
                controller.connect(hostId)
                screen = Screen.Sessions
            },
            onPair = { go(Screen.Pairing) },
            onSettings = { go(Screen.Settings) }
        )
        Screen.Pairing -> PairingScreen(
            app = controller,
            initialLink = launchLink,
            onPaired = { hostId ->
                controller.connect(hostId)
                stack = emptyList()
                screen = Screen.Sessions
            },
            onBack = { screen = Screen.Hosts }
        )
        Screen.Sessions -> SessionListScreen(
            app = controller,
            onOpen = { key -> go(Screen.Session(key)) },
            onOpenFiles = { root -> go(Screen.Files(root)) },
            onOpenGit = { root -> go(Screen.Git(root)) },
            onBack = {
                controller.disconnect()
                screen = Screen.Hosts
            }
        )
        Screen.Settings -> SettingsScreen(
            app = controller,
            onBack = { back() },
            onLogout = {
                scope.launch {
                    controller.logout()
                    stack = emptyList()
                    screen = Screen.Login
                }
            },
            onHistory = { go(Screen.History) },
            onBiometric = { controller.setBiometricLock(!state.biometricLock) }
        )
        is Screen.History -> HistoryScreen(
            app = controller,
            repository = state.activeHost?.let { host ->
                HistoryRepository(
                    backend = backend,
                    identity = controller.identity,
                    hostId = host.hostId
                )
            },
            onBack = { back() }
        )
        is Screen.Session -> SessionScreen(
            app = controller,
            sessionKey = current.key,
            onBack = {
                scope.launch { controller.closeSession() }
                back()
            },
            onOpenFiles = { root -> go(Screen.Files(root)) },
            onOpenGit = { root -> go(Screen.Git(root)) },
            onOpenTerminal = { key -> go(Screen.Terminal(key)) }
        )
        is Screen.Files -> FilesScreen(app = controller, root = current.root, onBack = { back() })
        is Screen.Git -> GitScreen(app = controller, root = current.root, onBack = { back() })
        is Screen.Terminal -> TerminalScreen(app = controller, sessionKey = current.key, onBack = { back() })
    }
}
