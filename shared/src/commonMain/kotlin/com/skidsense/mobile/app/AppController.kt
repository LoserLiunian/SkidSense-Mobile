package com.skidsense.mobile.app

import com.skidsense.mobile.api.BackendClient
import com.skidsense.mobile.api.BackendException
import com.skidsense.mobile.api.DeviceRow
import com.skidsense.mobile.api.HostRow
import com.skidsense.mobile.api.LoginChallenge
import com.skidsense.mobile.api.ServerStatus
import com.skidsense.mobile.model.DirEntry
import com.skidsense.mobile.model.GitSnapshot
import com.skidsense.mobile.model.ListDirResult
import com.skidsense.mobile.model.ModelCatalog
import com.skidsense.mobile.model.OpenSessionResponse
import com.skidsense.mobile.model.PromptResponse
import com.skidsense.mobile.model.ReadFileResult
import com.skidsense.mobile.model.SessionPatchPush
import com.skidsense.mobile.model.SessionRow
import com.skidsense.mobile.model.TurnSnapshot
import com.skidsense.mobile.model.Workspace
import com.skidsense.mobile.model.WriteFileResult
import com.skidsense.mobile.rc.B64u
import com.skidsense.mobile.rc.CryptoError
import com.skidsense.mobile.rc.KeyPair
import com.skidsense.mobile.rc.PairingPayload
import com.skidsense.mobile.rc.Primitives
import com.skidsense.mobile.rc.normalizeBackendUrl
import com.skidsense.mobile.store.FileStore
import com.skidsense.mobile.store.SecretStore
import com.skidsense.mobile.store.getString
import com.skidsense.mobile.store.putString
import com.skidsense.mobile.transport.AppInfo
import com.skidsense.mobile.transport.CarrierFactory
import com.skidsense.mobile.transport.ClientState
import com.skidsense.mobile.transport.Credentials
import com.skidsense.mobile.transport.Enrollment
import com.skidsense.mobile.transport.HostEndpoint
import com.skidsense.mobile.transport.RcClient
import com.skidsense.mobile.transport.RcJson
import com.skidsense.mobile.transport.RemoteCallError
import com.skidsense.mobile.transport.Welcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A paired host as this phone stores it. */
@kotlinx.serialization.Serializable
data class PairedHost(
    val hostId: String,
    /** The host's static public key, pinned from the QR code. */
    val hostKey: String,
    val deviceId: String,
    val name: String = "",
    val machine: String = "",
    val lanAddrs: List<String> = emptyList(),
    val lanPort: Int = 0,
    /** The backend both ends were signed in to at pairing time. */
    val server: String = "",
    /** Whose pairing this is (S33) — 0 for a record written before pairings carried it. */
    @kotlinx.serialization.SerialName("userId") val userId: Long = 0,
    val pairedAt: Long = 0
) {
    fun endpoint(): HostEndpoint = HostEndpoint(hostId, B64u.decode(hostKey, 32), deviceId, lanAddrs, lanPort)

    /**
     * Visible to [userId] on [server]: an exact account match, or a legacy
     * record with no owner on the same backend, which is adopted for lack of a
     * better claimant.
     */
    fun visibleTo(userId: Long, server: String): Boolean =
        this.userId == userId || (this.userId == 0L && sameBackend(this.server, server))
}

/** Host+port equality, scheme-insensitive, like `pair`'s own check. */
private fun sameBackend(a: String, b: String): Boolean =
    com.skidsense.mobile.rc.normalizeBackendUrl(a) == com.skidsense.mobile.rc.normalizeBackendUrl(b)

/** Everything the UI reads. One object, replaced wholesale on change. */
data class AppState(
    val ready: Boolean = false,
    val baseUrl: String = "",
    val status: ServerStatus? = null,
    val user: String? = null,
    val userId: Long = 0,
    val hosts: List<HostRow> = emptyList(),
    val hostsError: String? = null,
    val hostsLoading: Boolean = false,
    val paired: List<PairedHost> = emptyList(),
    val activeHostId: String? = null,
    val connection: ClientState = ClientState.Idle,
    val welcome: Welcome? = null,
    val workspaces: List<Workspace> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val search: String = "",
    val devicesForHost: List<DeviceRow> = emptyList(),
    val biometricLock: Boolean = false,
    val locked: Boolean = false,
    val lastError: String? = null
) {
    val connected: Boolean get() = connection is ClientState.Connected

    /** Scopes come from the host's `welcome` — the effective set, already narrowed (spec §8.3). */
    fun scopes(): Set<String> = welcome?.device?.scopes?.toSet() ?: emptySet()
    fun can(method: String): Boolean = welcome?.can(method) ?: false
    fun canScope(scope: String): Boolean = scope in scopes()

    /** Sessions for one workspace, newest first. */
    fun sessionsIn(workdir: String): List<SessionRow> =
        sessions.filter { it.workdir == workdir }.sortedByDescending { it.updatedAt }

    val activeHost: PairedHost? get() = paired.firstOrNull { it.hostId == activeHostId }
}

/**
 * The application's one state machine: login, hosts, the connection to the
 * active host, the session list, and the live turn. Screens read [state] and
 * call the methods here; nothing else touches the transport or the backend.
 */
class AppController(
    private val backend: BackendClient,
    private val carriers: CarrierFactory,
    private val secrets: SecretStore,
    private val files: FileStore,
    private val platformName: String,
    private val deviceModel: String,
    private val scope: CoroutineScope,
    private val appInfo: AppInfo = AppInfo("skidsense-mobile", "0.1.0", "android")
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * Where `tui.data` / `tui.exit` go while a terminal screen is mounted.
     * Set by the terminal screen; null when none is open.
     */
    var terminalSink: com.skidsense.mobile.ui.TerminalSink? = null

    private val _live = MutableStateFlow(0L)
    /** Bumped whenever [liveTurn] changed, so Compose recomposes. */
    val liveRevision: StateFlow<Long> = _live.asStateFlow()

    val liveTurn = LiveTurn()

    private var client: RcClient? = null
    private var eventJob: Job? = null

    /**
     * Attachments for the composer. One manager per active host, tagged with
     * the *live connection*: the desktop's stash is per-connection and dies
     * with it, so a draft from a dead connection is dropped, never replayed.
     */
    var uploads: UploadManager = UploadManager(::callOrNull, ::connectionMarker)
        private set

    /**
     * What an upload's connection is compared with, by object identity: nil
     * until a connection is up, and a draft staged offline is dropped at the
     * first connection — it never had a live id.
     */
    private fun connectionMarker(): Any? = client?.connectionMarker

    /**
     * One in-flight files search (spec §6.4), exposed through a flow that is
     * rebuilt on every accepted frame: the fold mutates as `search.progress`
     * arrives, and assigning *the same instance* back to a StateFlow emits
     * nothing (identity compare) — which is exactly why results never showed
     * (N02). A versioned wrapper keeps the live fold and forces an emission.
     */
    /**
     * The wrapper whose identity changes on every publish: a StateFlow's
     * identity compare is what N02's bug filtered every update through.
     */
    class SearchView(val revision: Long, val fold: SearchFold)

    private val _searchView = MutableStateFlow<SearchView?>(null)
    private var searchRevision = 0L

    val search: StateFlow<SearchView?> = _searchView.asStateFlow()

    /** Set the live fold; the exposed flow sees a *new* object every time. */
    private fun publishSearch(fold: SearchFold?) {
        _searchView.value = fold?.let { SearchView(++searchRevision, it) }
    }

    /**
     * The device's static X25519 key pair, from the Keystore.
     *
     * A read that *throws* is not a missing key: it is the Keystore failing
     * transiently (or the store having been wiped underneath us), and
     * generating a fresh pair then would overwrite the real one when the next
     * read succeeds — every paired host would go `unknown-device`. So a throw
     * propagates, and a new key is made only for a clean `null` (S27).
     */
    private var identityCached: KeyPair? = null

    val identity: KeyPair
        get() = identityCached ?: run {
            val stored = secrets.get(IDENTITY_KEY)
            val pair = if (stored != null && stored.size == 32) {
                Primitives.keyPairFromPrivate(stored)
            } else {
                val fresh = Primitives.generateKeyPair()
                try {
                    secrets.put(IDENTITY_KEY, fresh.priv)
                } catch (error: Throwable) {
                    // The key must not be used without being stored: the next
                    // launch would generate another and the hosts would see a
                    // stranger where they enrolled this one.
                    throw CryptoError("no-keystore", "系统密钥库不可用，无法生成设备密钥", error)
                }
                fresh
            }
            identityCached = pair
            pair
        }

    val devicePublicKey: String get() = B64u.encode(identity.pub)

    private val grants = GrantCache()

    /**
     * Grants for the active host, fetched from the backend and cached until
     * they expire. `rc-access` lasts an hour, which is also how long LAN mode
     * survives with the backend unreachable (spec §8).
     */
    private inner class GrantCache : Credentials {
        private val lock = Mutex()
        private var cached: String? = null
        private var expiresAt = 0L

        override suspend fun grant(fresh: Boolean): String = lock.withLock {
            val host = _state.value.activeHost ?: throw BackendException("还没有选择电脑")
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!fresh && cached != null && now < expiresAt - 60_000) return cached!!
            val response = try {
                backend.grant(host.hostId, host.deviceId)
            } catch (error: BackendException) {
                throw com.skidsense.mobile.transport.RcException("grant", error.message ?: "无法获取授权凭证")
            }
            cached = response.grant
            expiresAt = if (response.expiresAt > 1_000_000_000_000L) response.expiresAt else response.expiresAt * 1000
            if (expiresAt <= now) expiresAt = now + 3_600_000
            response.grant
        }

        override suspend fun onUnauthorized() {
            backend.invalidateAccessToken()
            lock.withLock { cached = null; expiresAt = 0 }
        }

        fun clear() {
            cached = null
            expiresAt = 0
        }
    }

    // --- startup ------------------------------------------------------------------

    private var restoredBrokenSecrets = false

    suspend fun start() {
        if (!restoredBrokenSecrets) {
            restoredBrokenSecrets = true
            restoreSecretsIfBroken()
        }
        val session = backend.session.value
        val paired = loadPaired(session?.userId ?: 0, session?.baseUrl ?: "")
        update {
            it.copy(
                ready = true,
                baseUrl = session?.baseUrl ?: backend.defaultBaseUrl,
                user = session?.username,
                userId = session?.userId ?: 0,
                paired = paired,
                biometricLock = files.read(LOCK_KEY) == "1",
                locked = files.read(LOCK_KEY) == "1"
            )
        }
        if (session != null) refreshHosts()
        // The landing screen keys on `user`: a session the backend has since
        // revoked is cleared by the client's next refresh, and the UI must
        // follow it back to login — not sit on a hosts list that cannot work
        // (the banner used to be the only, dead, clue).
        scope.launch {
            backend.session.collect { current ->
                if (current == null && _state.value.user != null) {
                    onSessionExpired()
                }
            }
        }
    }

    /**
     * The store told us its master would not decrypt this install (S27): the
     * session it held cannot be recovered, so the phone is signed out and the
     * pairings (they belonged to a device key that is already gone) forgotten.
     */
    private fun restoreSecretsIfBroken() {
        val restorer = (secrets as? com.skidsense.mobile.store.BrokenStoreRestorer) ?: return
        if (!restorer.restoreIfBroken()) return
        update { it.copy(lastError = "这台设备上的登录信息已损坏，已为你登出；请重新登录并重新配对") }
    }

    /** Back to login, keeping a tapped pairing link for after the sign-in. */
    private fun onSessionExpired() {
        disconnect()
        update {
            it.copy(
                user = null, userId = 0, hosts = emptyList(), hostsError = null,
                activeHostId = null, welcome = null, sessions = emptyList(),
                lastError = "登录已过期，请重新登录"
            )
        }
    }

    // --- login --------------------------------------------------------------------

    suspend fun probe(base: String): ServerStatus? = try {
        backend.status(base).also { update { s -> s.copy(baseUrl = base, status = it) } }
    } catch (error: BackendException) {
        update { s -> s.copy(lastError = error.message) }
        null
    }

    /** Signs in; returns the second-factor challenge when one is required. */
    suspend fun login(base: String, username: String, password: String, geetest: String?, turnstile: String?): LoginChallenge? {
        val challenge = backend.login(base, username, password, geetest, turnstile)
        update { it.copy(baseUrl = base) }
        if (challenge == null) onSignedIn()
        return challenge
    }

    suspend fun verifyTwoFactor(base: String, flowToken: String, code: String) {
        backend.verifyLogin(base, flowToken, code)
        onSignedIn()
    }

    private suspend fun onSignedIn() {
        val session = backend.session.value
        // Pairings belong to an account (`server` + `userId`): another account
        // must neither see the previous one's computers here nor overwrite its
        // records with its own (S33).
        val paired = loadPaired(session?.userId ?: 0, session?.baseUrl ?: "")
        update { it.copy(user = session?.username, userId = session?.userId ?: 0, paired = paired, lastError = null) }
        refreshHosts()
    }

    suspend fun logout() {
        disconnect()
        backend.logout()
        // Disk is deliberately untouched: the pairings still on it belong to
        // the account that just left, and are read back if it signs in again
        // (S33). Clearing them here used to risk the opposite bug — a same-
        // process re-login writing its short list over the full one.
        update { it.copy(user = null, userId = 0, hosts = emptyList(), paired = emptyList(), activeHostId = null, welcome = null, sessions = emptyList()) }
    }

    // --- hosts --------------------------------------------------------------------

    suspend fun refreshHosts() {
        update { it.copy(hostsLoading = true) }
        try {
            val hosts = backend.hosts()
            update { it.copy(hosts = hosts, hostsLoading = false, hostsError = null) }
            learnAddresses(hosts)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            update { it.copy(hostsLoading = false, hostsError = error.message ?: "无法读取电脑列表") }
        }
    }

    /**
     * Fold the backend's `lan_addrs`/`lan_port` into the paired hosts.
     *
     * The QR code's address is a snapshot; a router gives out a different one
     * after a reboot. When an address really changed the active connection is
     * given a nudge, so a host that moved does not stay on the relay forever.
     */
    private fun learnAddresses(hosts: List<com.skidsense.mobile.api.HostRow>) {
        val paired = _state.value.paired
        if (paired.isEmpty()) return
        var changed = false
        val updated = paired.map { host ->
            val row = hosts.firstOrNull { it.hostId == host.hostId } ?: return@map host
            val endpoint = liveEndpoints[host.hostId]
            val fresh = endpoint?.learn(row.lanAddrs, row.lanPort.takeIf { it in 1..65535 })
                ?: (row.lanAddrs != host.lanAddrs)
            if (fresh) changed = true
            if (row.lanAddrs.isEmpty() && row.lanPort == host.lanPort) host
            else host.copy(
                lanAddrs = row.lanAddrs.ifEmpty { host.lanAddrs },
                lanPort = row.lanPort.takeIf { it in 1..65535 } ?: host.lanPort,
                name = row.name.ifBlank { host.name }
            )
        }
        if (updated != paired) {
            persistPaired(updated)
            update { it.copy(paired = updated) }
        }
        if (changed && _state.value.activeHostId != null) client?.retry()
    }

    /** The live endpoint per host, so the address list is shared with the client. */
    private val liveEndpoints = HashMap<String, HostEndpoint>()

    suspend fun devicesFor(hostId: String) {
        try {
            val devices = backend.devices(hostId)
            update { it.copy(devicesForHost = devices) }
        } catch (error: Throwable) {
            update { it.copy(lastError = error.message) }
        }
    }

    suspend fun renameDevice(deviceId: String, name: String) {
        val host = _state.value.activeHost ?: return
        backend.updateDevice(deviceId, name = name)
        devicesFor(host.hostId)
    }

    suspend fun setDeviceScopes(deviceId: String, scopes: List<String>) {
        val host = _state.value.activeHost ?: return
        backend.updateDevice(deviceId, scopes = scopes)
        devicesFor(host.hostId)
    }

    /** Revoke a device. Revoking *this* phone also forgets it here. */
    suspend fun revokeDevice(deviceId: String) {
        val host = _state.value.activeHost ?: return
        backend.revokeDevice(deviceId)
        devicesFor(host.hostId)
        if (deviceId == host.deviceId) {
            disconnect()
            val remaining = _state.value.paired.filterNot { it.hostId == host.hostId }
            persistPaired(remaining)
            update { it.copy(paired = remaining, activeHostId = null, welcome = null, sessions = emptyList()) }
        }
    }

    // --- pairing ------------------------------------------------------------------

    /**
     * The whole first visit: decode the QR payload, check it points at the
     * backend we are signed in to, register this device's key, run the enroll
     * handshake, and only then treat the host as paired — the `welcome` is the
     * host's acknowledgement that it recorded this key (spec §4.3 step 5, §8.4).
     */
    suspend fun pair(payload: PairingPayload, onProgress: (String) -> Unit = {}): PairingPayload {
        val session = backend.session.value ?: throw IllegalStateException("请先登录")
        if (normalizeBackendUrl(payload.server) != normalizeBackendUrl(session.baseUrl)) {
            throw IllegalStateException(
                "这个二维码属于 ${payload.server}，而你登录的是 ${session.baseUrl}。两端必须登录同一个后端。"
            )
        }
        onProgress("正在登记这台手机…")
        val deviceName = deviceModel.ifBlank { "我的手机" }
        val registration = try {
            backend.registerDevice(payload.hostId, deviceName, devicePublicKey, platformName)
        } catch (error: BackendException) {
            // 409 means this key is already active on that host, so the row is
            // reused — and per spec §9 that response carries the device id but
            // **no ticket**, because there is nothing left to enrol. Recovery
            // is two steps, not one: fetch an rc-access grant for that id, then
            // run an ordinary `connect` handshake. Jumping straight to the
            // handshake would have nothing to put in `hello`, and retrying the
            // registration would only earn the same 409.
            val existing = (error.data as? JsonObject)?.let { data ->
                (data["device_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            } ?: throw error
            onProgress("这台手机已经登记过，正在重新连接…")
            return reconnectExisting(payload, existing, onProgress)
        }
        return finishPairing(payload, registration.device.deviceId, onProgress, registration.ticket)
    }

    /**
     * The 409 path: this phone is already registered on that host with a key
     * the backend still lists as active, so no ticket was issued.
     *
     * Fetch a grant first, then `connect`. The grant is fetched directly rather
     * than through `GrantCache` because that cache is keyed on the *active*
     * host, and at this moment this host is not active yet — using it would
     * throw "还没有选择电脑" from the wrong place.
     */
    private suspend fun reconnectExisting(
        payload: PairingPayload,
        deviceId: String,
        onProgress: (String) -> Unit
    ): PairingPayload {
        val grant = try {
            backend.grant(payload.hostId, deviceId).grant
        } catch (error: BackendException) {
            throw IllegalStateException(
                "这台手机在这个电脑上已登记，但拿不到接入凭证：${error.message ?: "未知原因"}。" +
                    "在电脑上检查它是否已被撤销；已撤销的话请重新生成二维码。",
                error
            )
        }
        onProgress("正在与电脑握手…")
        val welcome = try {
            Enrollment.connectWithGrant(
                payload = payload,
                deviceId = deviceId,
                identity = identity,
                grant = grant,
                carriers = carriers,
                scope = scope,
                onProgress = onProgress
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is com.skidsense.mobile.transport.HandshakeRejected && error.code == "unknown-device") {
                // `hsr` is plaintext: this refusal only earns the destructive
                // repair once the relay — TLS to the backend — agrees (C6).
                return repairStaleConfirmed(payload, deviceId, grant, onProgress)
            }
            throw IllegalStateException(error.message ?: "重新连接失败", error)
        }
        return rememberPairing(payload, deviceId, welcome, onProgress)
    }

    /**
     * The LAN walk said `unknown-device`; before believing it, the relay route
     * is asked on its own (anything answering on a LAN address can forge the
     * plaintext refusal). Only when the backend-mediated route says the same
     * does the stale row get revoked. A relay that is itself unreachable says
     * nothing — the phone keeps its pairing and the user retries.
     */
    private suspend fun repairStaleConfirmed(
        payload: PairingPayload,
        deviceId: String,
        grant: String,
        onProgress: (String) -> Unit
    ): PairingPayload {
        try {
            val welcome = connectWithGrantRelayFirst(payload, deviceId, grant, onProgress)
            // The relay took the plain `connect` after all: the LAN answer was
            // the forged one. Nothing stale to repair; keep the pairing.
            return rememberPairing(payload, deviceId, welcome, onProgress)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is com.skidsense.mobile.transport.HandshakeRejected && error.code == "unknown-device") {
                return repairStale(payload, deviceId, onProgress)
            }
            throw IllegalStateException(error.message ?: "重新连接失败", error)
        }
    }

    /**
     * The backend lists this phone as active on that host, and the host has
     * no record of it — an `activate` that succeeded on the backend while its
     * answer was lost, or a host whose identity was reset. Retrying `connect`
     * can never work (spec §4.3 step 5: the host admits only keys it saw pair),
     * and every new registration earns the same 409.
     *
     * Only called once the *relay* route has said `unknown-device` too
     * ([connectWithGrantRelay]): `hsr` is pre-handshake plaintext, so on a LAN
     * address it is forgeable by anything answering there, and a forged one
     * must not be able to trigger what follows (spec §6.5, C6). Through the
     * relay the refusal passes the backend's TLS and device check, which is
     * as trustworthy a word as this device can get.
     *
     * The user is holding a fresh QR code, so this does what they would do by
     * hand: revoke the stale row, register again — a revoked key gets a new
     * pending row and a ticket (§9) — and enrol with the code in front of them.
     */
    private suspend fun repairStale(
        payload: PairingPayload,
        staleDeviceId: String,
        onProgress: (String) -> Unit
    ): PairingPayload {
        onProgress("电脑上没有这台手机的记录，正在重新配对…")
        try {
            backend.revokeDevice(staleDeviceId)
        } catch (error: BackendException) {
            throw IllegalStateException("无法清理这台手机在服务器上的旧记录：${error.message ?: "未知原因"}", error)
        }
        val registration = backend.registerDevice(payload.hostId, deviceModel.ifBlank { "我的手机" }, devicePublicKey, platformName)
        return finishPairing(payload, registration.device.deviceId, onProgress, registration.ticket)
    }

    /**
     * `unknown-device`, believed only once the relay route agrees (C6). The
     * walk re-orders the endpoint so the relay comes first: a forged answer
     * on a LAN address is skipped over, and only the route the backend itself
     * vouches for can earn a revocation.
     */
    private suspend fun connectWithGrantRelayFirst(
        payload: PairingPayload,
        deviceId: String,
        grant: String,
        onProgress: (String) -> Unit
    ): com.skidsense.mobile.transport.Welcome {
        val relayOnly = payload.copy(lanAddrs = emptyList(), lanPort = 0)
        return Enrollment.connectWithGrant(relayOnly, deviceId, identity, grant, carriers, scope, onProgress = onProgress)
    }

    private suspend fun finishPairing(
        payload: PairingPayload,
        deviceId: String,
        onProgress: (String) -> Unit,
        ticket: String? = null
    ): PairingPayload {
        val usableTicket = ticket ?: throw IllegalStateException("服务器没有给出配对凭证，请重新生成二维码")
        onProgress("正在与电脑握手…")
        val welcome = try {
            Enrollment.enroll(
                payload = payload,
                deviceId = deviceId,
                identity = identity,
                ticket = usableTicket,
                carriers = carriers,
                scope = scope,
                onProgress = onProgress
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw IllegalStateException(error.message ?: "配对失败", error)
        }
        return rememberPairing(payload, deviceId, welcome, onProgress)
    }

    /**
     * Record the host after any successful handshake, `enroll` or `connect`.
     *
     * One place so the two paths cannot record different fields: the address
     * list and port come from the QR either way, and a recovery that stored a
     * host without them would silently fall back to the relay forever.
     */
    private suspend fun rememberPairing(
        payload: PairingPayload,
        deviceId: String,
        welcome: com.skidsense.mobile.transport.Welcome,
        onProgress: (String) -> Unit
    ): PairingPayload {
        val host = PairedHost(
            hostId = payload.hostId,
            hostKey = B64u.encode(payload.hostKey),
            deviceId = deviceId,
            name = welcome.host.name,
            machine = payload.machine,
            lanAddrs = payload.lanAddrs,
            lanPort = payload.lanPort,
            server = payload.server,
            userId = _state.value.userId,
            pairedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )
        val remaining = _state.value.paired.filterNot { it.hostId == host.hostId } + host
        persistPaired(remaining)
        update { it.copy(paired = remaining, lastError = null) }
        onProgress("配对完成，正在读取这个设备被授予的权限…")
        return payload
    }

    /** Forget a host on this phone only (the desktop keeps its record; revoke it there). */
    suspend fun forgetHost(hostId: String) {
        if (_state.value.activeHostId == hostId) disconnect()
        val remaining = _state.value.paired.filterNot { it.hostId == hostId }
        persistPaired(remaining)
        update { it.copy(paired = remaining, activeHostId = null) }
    }

    // --- connection -----------------------------------------------------------------

    fun connect(hostId: String) {
        val host = _state.value.paired.firstOrNull { it.hostId == hostId } ?: return
        disconnect()
        grants.clear()
        // One endpoint object per host, kept across reconnects: it is where a
        // fresher address list from `GET /hosts` lands.
        val endpoint = liveEndpoints.getOrPut(hostId) { host.endpoint() }
        val rc = RcClient(endpoint, identity, grants, carriers, scope)
        rc.onLanUnreachable = { onLanUnreachable() }
        client = rc
        uploads = UploadManager(::callOrNull, ::connectionMarker)
        publishSearch(null)
        update { it.copy(activeHostId = hostId, connection = ClientState.Idle, welcome = null, sessions = emptyList()) }
        eventJob = scope.launch { rc.state.collect { state -> onConnectionState(state) } }
        scope.launch { rc.events.collect { event -> onEvent(event.kind, event.payload) } }
        rc.start()
    }

    fun disconnect() {
        eventJob?.cancel()
        eventJob = null
        resyncJob?.cancel()
        resyncJob = null
        reloadJob?.cancel()
        reloadJob = null
        terminalKey = null
        terminalBuffer.clear()
        val rc = client ?: return
        client = null
        scope.launch { rc.stop() }
        liveTurn.reset()
        _live.value += 1
        update { it.copy(connection = ClientState.Idle, welcome = null, sessions = emptyList()) }
    }

    fun retry() {
        client?.retry()
    }

    /**
     * A LAN attempt failed and the client is falling back to the relay: ask the
     * backend for the host's current addresses, so the next round has a better
     * chance than the one that just timed out. Rate-limited, since a whole round
     * can fail several times in a row.
     */
    private var lastAddressRefresh = 0L

    private suspend fun onLanUnreachable() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (now - lastAddressRefresh < 60_000) return
        lastAddressRefresh = now
        refreshHosts()
    }

    private suspend fun onConnectionState(state: ClientState) {
        update { it.copy(connection = state, welcome = (state as? ClientState.Connected)?.welcome) }
        if (state is ClientState.Connected) {
            // Whatever was uploaded on the connection that just died is gone
            // from the desktop's stash too; only drafts from *this* connection
            // may live on.
            uploads.dropStaleConnections()
            if (_state.value.workspaces.isEmpty()) loadWorkspaces()
            loadSessions()
        }
    }

    /**
     * Events are folded in arrival order, but the work they trigger is *not*:
     * a request launched from inside this collector (a gap's `turn.snapshot`,
     * a `sessions.changed`'s `sessions.list`) used to run here, and while it
     * waited, events filled every buffer between it and the read loop — past
     * about a thousand of them the read loop blocked on `emit`, the response
     * sat unread behind them, and the request died at the 30 s timeout. So the
     * collector stays a fold: it updates state and hands work to the
     * single-flight jobs below, which make the round trips in their own
     * coroutines.
     */
    private suspend fun onEvent(kind: String, payload: JsonElement) {
        when (kind) {
            "session.patch" -> onSessionPatch(payload)
            "sessions.changed" -> onSessionsChanged()
            "tui.data" -> com.skidsense.mobile.ui.TerminalChannel.decodeData(payload)?.let { (key, data) ->
                // The terminal's first bytes are on the wire before the screen
                // has registered its sink (the CLI draws at once); they wait
                // in [terminalBuffer] instead of being dropped.
                val sink = terminalKey?.takeIf { terminalSink != null }
                if (sink == key) terminalSink?.onData(key, data)
                else if (terminalKey == key || terminalKey == null) terminalBuffer.getOrPut(key) { StringBuilder() }.append(data)
            }
            "tui.exit" -> com.skidsense.mobile.ui.TerminalChannel.decodeExit(payload)?.let { exit ->
                if (terminalKey == exit.key) {
                    terminalBuffer.remove(exit.key)
                    terminalSink?.onExit(exit.key, exit.code, exit.reason, exit.tail)
                }
            }
            "search.progress" -> onSearchProgress(payload)
            // Received but not yet shown anywhere; the UI reads what it needs on demand.
            "agents.changed", "fs.changed", "git.changed", "background.jobs" -> Unit
        }
    }

    /**
     * The terminal the controller currently streams for, and the bytes that
     * arrived before its sink registered (N04). The exit payload is decoded
     * whole: `code`, `signal`, the human sentence and the tail (spec §6.4, C2).
     */
    private var terminalKey: String? = null
    private val terminalBuffer = HashMap<String, StringBuilder>()

    /** A terminal screen appeared for [key]: hand over everything held for it. */
    fun attachTerminal(key: String, sink: com.skidsense.mobile.ui.TerminalSink) {
        terminalKey = key
        terminalSink = sink
        terminalBuffer.remove(key)?.takeIf { it.isNotEmpty() }?.let { sink.onData(key, it.toString()) }
    }

    /** The bytes [attachTerminal] is still holding for [key] (test-visible). */
    internal fun bufferedTerminalData(key: String): String = terminalBuffer[key]?.toString() ?: ""

    fun detachTerminal(sink: com.skidsense.mobile.ui.TerminalSink) {
        if (terminalSink === sink) {
            terminalSink = null
            terminalKey = null
        }
    }

    /** The subscription this screen cares about. */
    var openSessionKey: String? = null
        private set

    private fun onSessionPatch(payload: JsonElement) {
        val push = try {
            RcJson.decodeFromJsonElement(SessionPatchPush.serializer(), payload)
        } catch (_: Exception) {
            return
        }
        if (push.sessionKey != openSessionKey) return
        // A re-read is bringing back a snapshot that already contains the
        // patches still in flight: applying any of them under the old baseline
        // would duplicate their deltas (the renderer's `useLiveTurn` treats
        // everything until the answer as suspect for the same reason, C1).
        if (resyncJob?.isActive == true) {
            resyncPendingSeq = maxOf(resyncPendingSeq ?: push.seq, push.seq)
            return
        }
        when (liveTurn.apply(push)) {
            LiveTurn.Applied.Ok -> _live.value += 1
            LiveTurn.Applied.Gap -> resyncSnapshot(push)
        }
    }

    /**
     * The one re-read in flight for the open session. A gap found while one is
     * already running only moves its target seq forward; everything waits on
     * the same `turn.snapshot` round trip (spec §6.4, C1).
     */
    private var resyncJob: Job? = null
    private var resyncGeneration = 0
    private var resyncPendingSeq: Long? = null

    private fun resyncSnapshot(push: SessionPatchPush) {
        val existing = resyncJob
        if (existing?.isActive == true) {
            resyncPendingSeq = maxOf(resyncPendingSeq ?: push.seq, push.seq)
            return
        }
        scheduleResync(push.seq)
    }

    private fun scheduleResync(triggerSeq: Long) {
        val generation = ++resyncGeneration
        val key = openSessionKey ?: return
        resyncJob = scope.launch {
            try {
                var target = triggerSeq
                while (true) {
                    val fresh = readSnapshot(key)
                    // A stale job must not write over what a newer `sessions.open`
                    // or re-read already decided.
                    if (generation != resyncGeneration) return@launch
                    if (fresh == null) {
                        // The guard in `readSnapshot`: null means the request
                        // failed, so the baseline does not move and the next
                        // frame — or `sessions.changed` — tries again.
                        return@launch
                    }
                    if (fresh.snapshot != null) {
                        liveTurn.setSnapshot(fresh.snapshot)
                        liveTurn.acceptSeq(fresh.seq ?: target)
                    } else {
                        // The turn ended before the re-read arrived: the end it
                        // never published lives in the session record, not in
                        // `turn.snapshot`. Finishing from `sessions.open` is
                        // what keeps the phone from showing it as running
                        // forever (C1).
                        val opened = runCatching {
                            requestDecoded("sessions.open", buildJsonObject { put("key", key) }, OpenSessionResponse.serializer())
                        }.getOrNull()
                        if (opened == null) {
                            // Both reads failed: nothing is known, the baseline
                            // does not move, and the next frame — or a
                            // `sessions.changed` — asks again (C1).
                            return@launch
                        }
                        liveTurn.setHistory(opened.turns)
                        liveTurn.setSnapshot(opened.live ?: opened.turns.lastOrNull()?.snapshot)
                        liveTurn.acceptSeq(target)
                    }
                    _live.value += 1
                    loadSessions()
                    val again = resyncPendingSeq
                    if (again == null || again == target) return@launch
                    resyncPendingSeq = null
                    target = again
                }
            } finally {
                resyncJob = null
                // A gap that arrived while this one ran still needs an answer:
                // its patches were dropped, not applied.
                val pending = resyncPendingSeq
                if (pending != null && generation == resyncGeneration) {
                    resyncPendingSeq = null
                    resyncSnapshot(SessionPatchPush(sessionKey = key, seq = pending))
                }
            }
        }
    }

    /** One decoded `turn.snapshot` response, or null when the request failed. */
    private class SnapshotRead(val snapshot: TurnSnapshot?, val seq: Long?)

    private suspend fun readSnapshot(key: String): SnapshotRead? {
        val element = try {
            request("turn.snapshot", buildJsonObject { put("key", key) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return null
        } ?: return SnapshotRead(null, null)
        if (element !is JsonObject || element.isEmpty()) return SnapshotRead(null, null)
        // A current desktop sends back the seq of the last patch this snapshot
        // already contains (C1); an older one omits it and the caller falls
        // back to the seq whose gap triggered the read.
        return SnapshotRead(
            RcJson.decodeFromJsonElement(TurnSnapshot.serializer(), element),
            ((element["seq"] as? JsonPrimitive)?.takeIf { !it.isString })?.content?.toLongOrNull()
        )
    }

    /**
     * The session list is refreshed here — coalesced, never from inside the
     * collector — and doubles as the gap rule's backstop: a `sessions.changed`
     * whose row says the open turn is over while the live snapshot still calls
     * it running is the one place guaranteed to arrive after a re-read that
     * failed (C1).
     */
    private var reloadJob: Job? = null

    private fun onSessionsChanged() {
        if (reloadJob?.isActive == true) return
        reloadJob = scope.launch {
            reloadSessionsOnce()
            val key = openSessionKey ?: return@launch
            if (liveTurn.snapshot?.running != true) return@launch
            val row = _state.value.sessions.firstOrNull { it.key == key } ?: return@launch
            if (row.runState == "running") return@launch
            // The row says the open turn is over while the live snapshot still
            // calls it running (a gap whose re-read failed is the common way
            // in). The heal is the session record, not another snapshot read:
            // reopen, and the record's final state replaces the stale one (C1).
            resyncJob?.cancel()
            resyncJob = null
            resyncPendingSeq = null
            resyncGeneration += 1
            val opened = runCatching {
                requestDecoded("sessions.open", buildJsonObject { put("key", key) }, OpenSessionResponse.serializer())
            }.getOrNull() ?: return@launch
            liveTurn.setHistory(opened.turns)
            liveTurn.setSnapshot(opened.live ?: opened.turns.lastOrNull()?.snapshot)
            _live.value += 1
        }
    }

    private suspend fun reloadSessionsOnce() {
        val rows = requestDecoded("sessions.list", JsonObject(emptyMap()), ListSerializer(SessionRow.serializer())) ?: return
        update { it.copy(sessions = rows, sessionsLoading = false, sessionsError = null) }
    }

    // --- host calls ----------------------------------------------------------------

    /** A `req` on the live connection. Throws when offline or when the host refuses. */
    private suspend fun request(method: String, params: JsonElement? = null): JsonElement? {
        val rc = client ?: throw com.skidsense.mobile.transport.RcException("offline", "未连接到电脑")
        return try {
            rc.call(method, params)
        } catch (error: RemoteCallError) {
            update { it.copy(lastError = error.message) }
            throw error
        }
    }

    /** A call that returns null instead of throwing, for paths that report their own failures. */
    private suspend fun callOrNull(method: String, params: JsonObject?): JsonObject? = try {
        request(method, params) as? JsonObject
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        null
    }

    private suspend fun <T> requestDecoded(
        method: String,
        params: JsonElement?,
        serializer: kotlinx.serialization.DeserializationStrategy<T>
    ): T? = request(method, params)?.let {
        try {
            RcJson.decodeFromJsonElement(serializer, it)
        } catch (error: Exception) {
            update { s -> s.copy(lastError = "无法解析 $method 的结果：${error.message}") }
            null
        }
    }

    suspend fun loadWorkspaces() {
        val workspaces = requestDecoded("workspaces.list", null, ListSerializer(Workspace.serializer())) ?: return
        update { it.copy(workspaces = workspaces.sortedBy { w -> w.order }) }
    }

    suspend fun loadSessions() {
        update { it.copy(sessionsLoading = true) }
        try {
            val rows = requestDecoded("sessions.list", JsonObject(emptyMap()), ListSerializer(SessionRow.serializer()))
            update { it.copy(sessions = rows ?: emptyList(), sessionsLoading = false, sessionsError = null) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            update { it.copy(sessionsLoading = false, sessionsError = error.message ?: "无法读取会话") }
        }
    }

    suspend fun searchSessions(query: String) {
        update { it.copy(search = query) }
        if (query.isBlank()) {
            loadSessions()
            return
        }
        try {
            // `sessions.search` answers `SessionRow[]` — the same shape as
            // `sessions.list`, not an envelope (spec §7; the desktop's
            // `Host.searchSessions` returns `SessionRow[]`).
            val rows = requestDecoded(
                "sessions.search",
                buildJsonObject { put("query", query) },
                ListSerializer(SessionRow.serializer())
            )
            update { it.copy(sessions = rows ?: emptyList()) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            update { it.copy(sessionsError = error.message) }
        }
    }

    suspend fun openSession(key: String): OpenSessionResponse? {
        val previous = openSessionKey
        if (previous != null && previous != key) {
            runCatching { request("unsubscribe", buildJsonObject { put("key", previous) }) }
        }
        // The in-flight re-read belongs to whichever session it was following:
        // its answer must not land on this one.
        resyncJob?.cancel()
        resyncJob = null
        resyncPendingSeq = null
        resyncGeneration += 1
        openSessionKey = key
        liveTurn.reset()
        _live.value += 1
        val response = requestDecoded("sessions.open", buildJsonObject { put("key", key) }, OpenSessionResponse.serializer())
        if (response == null) return null
        client?.subscribe(key)
        liveTurn.setHistory(response.turns)
        liveTurn.setSnapshot(response.live)
        _live.value += 1
        return response
    }

    suspend fun closeSession() {
        val key = openSessionKey ?: return
        resyncJob?.cancel()
        resyncJob = null
        resyncPendingSeq = null
        resyncGeneration += 1
        runCatching { request("unsubscribe", buildJsonObject { put("key", key) }) }
        openSessionKey = null
        liveTurn.reset()
        _live.value += 1
    }

    /**
     * Send a turn, with whatever attachments are staged *for this session*.
     *
     * `text` keeps the parameter name the wire uses (`sessionKey`) — the one
     * exception to the `key` convention of spec §7, which is easy to get wrong
     * and shows up as "没有这个会话" at runtime.
     *
     * The upload ids are only handed over once every one of them is complete:
     * an unfinished id fails the whole turn on the desktop, so it is refused
     * here with a message naming the file.
     *
     * When a prompt fails the uploads go back only if the host *said*, on the
     * same connection, that it did not take the turn (a definite `ok:false` or
     * a call error the host produced). Anything else — a drop, a timeout, an
     * answer that never arrived — leaves it genuinely uncertain whether the
     * turn started, and the ids it may already have consumed are dead with the
     * connection regardless (the desktop's stash is per-connection), so the
     * drafts are dropped and the user is told to check the transcript (S28).
     */
    suspend fun prompt(
        key: String,
        text: String,
        model: String?,
        effort: String?,
        approvalMode: String?
    ): PromptResponse {
        val taken = try {
            uploads.take(key)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return PromptResponse(ok = false, error = error.message ?: "附件还没传完")
        }
        val connection: Any? = client?.connectionMarker ?: this@AppController
        val ids = taken.map { it.id }
        val body = buildJsonObject {
            put("sessionKey", key)
            put("prompt", text)
            if (!model.isNullOrBlank()) put("model", model)
            if (!effort.isNullOrBlank()) put("effort", effort)
            if (!approvalMode.isNullOrBlank()) put("approvalMode", approvalMode)
            if (ids.isNotEmpty()) putJsonArray("uploads") { ids.forEach { add(JsonPrimitive(it)) } }
        }
        try {
            val response = requestDecoded("turn.prompt", body, PromptResponse.serializer())
            if (response == null) return uncertainPromptError(taken)
            if (!response.ok) {
                // The host answered on this connection and refused: the stash
                // is still holding the ids, and a retry may carry them as-is.
                uploads.restore(taken)
                update { it.copy(lastError = response.error) }
            }
            return response
        } catch (error: RemoteCallError) {
            uploads.restore(taken)
            return PromptResponse(ok = false, error = error.message ?: "发送失败")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return uncertainPromptError(taken, error)
        }
    }

    /**
     * No trustworthy answer — a drop, a timeout, an undecodable reply: the
     * turn may already be running on the desktop, and its upload stash is
     * per-connection, so the ids either were consumed or died with the wire.
     * Either way they are NOT put back: replaying them on the next connection
     * would earn a certain "附件不存在或已过期" (S28). The user is told to check
     * the transcript instead.
     */
    private suspend fun uncertainPromptError(
        taken: List<UploadManager.Upload>,
        error: Throwable? = null
    ): PromptResponse {
        val message = buildString {
            append(error?.message ?: "发送状态不明")
            append("：不确定回合是否已开始，请查看会话记录确认后再决定是否重发")
            if (taken.isNotEmpty()) append("；附件已移除，需要重新添加")
        }
        update { it.copy(lastError = message) }
        return PromptResponse(ok = false, error = message)
    }

    /** Stage a file for the next turn's prompt. Throws with a message for the UI. */
    suspend fun attach(name: String, mimeType: String?, bytes: ByteArray, sessionKey: String? = null): Unit {
        try {
            uploads.begin(name, mimeType, bytes, sessionKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException(UploadManager.describe(error), error)
        }
    }

    suspend fun detach(id: String) {
        uploads.abort(id)
    }

    /** Everything staged for [sessionKey], dropped — called when leaving its composer. */
    suspend fun detachAll(sessionKey: String) {
        uploads.abortAll(sessionKey)
    }

    /** Leaving a session's composer, from a scope that outlives it (S29). */
    fun detachAllBackground(sessionKey: String) {
        scope.launch { uploads.abortAll(sessionKey) }
    }

    /** Leaving the terminal, from a scope that outlives it (S29). */
    fun closeTerminalBackground(sessionKey: String) {
        scope.launch { runCatching { closeTerminal(sessionKey) } }
    }

    suspend fun stopTurn(key: String) {
        runCatching { request("turn.stop", buildJsonObject { put("key", key) }) }
    }

    suspend fun steer(key: String, text: String) {
        runCatching { request("turn.steer", buildJsonObject { put("key", key); put("text", text) }) }
    }

    /** Answer a permission question. `answer` is the wire form of `AskUserAnswer`. */
    suspend fun interact(key: String, interactionId: String, answer: JsonElement) {
        request(
            "turn.interact",
            buildJsonObject {
                put("key", key)
                put("interactionId", interactionId)
                put("answer", answer)
            }
        )
    }

    suspend fun selectChoice(key: String, interactionId: String, choiceId: String) =
        interact(key, interactionId, buildJsonObject { put("action", "select"); put("choiceId", choiceId) })

    suspend fun answerText(key: String, interactionId: String, text: String) =
        interact(key, interactionId, buildJsonObject { put("action", "text"); put("text", text) })

    suspend fun skipQuestion(key: String, interactionId: String) =
        interact(key, interactionId, buildJsonObject { put("action", "skip") })

    suspend fun cancelQuestion(key: String, interactionId: String) =
        interact(key, interactionId, buildJsonObject { put("action", "cancel") })

    suspend fun agents(): List<com.skidsense.mobile.model.AgentStatus> =
        requestDecoded("agents.list", null, ListSerializer(com.skidsense.mobile.model.AgentStatus.serializer())) ?: emptyList()

    suspend fun models(agent: String): ModelCatalog? =
        requestDecoded("models.list", buildJsonObject { put("agent", agent) }, ModelCatalog.serializer())

    suspend fun newSession(agent: String, workdir: String, title: String?): SessionRow? {
        val row = requestDecoded(
            "sessions.new",
            buildJsonObject {
                put("agent", agent)
                put("workdir", workdir)
                if (!title.isNullOrBlank()) put("title", title)
            },
            SessionRow.serializer()
        )
        loadSessions()
        return row
    }

    suspend fun renameSession(key: String, title: String) {
        request("sessions.rename", buildJsonObject { put("key", key); put("title", title) })
        loadSessions()
    }

    suspend fun deleteSession(key: String) {
        request("sessions.delete", buildJsonObject { put("key", key) })
        if (openSessionKey == key) openSessionKey = null
        loadSessions()
    }

    // --- files search (spec §6.4) ---------------------------------------------------

    /**
     * Start a content search. The progress comes back as `search.progress`
     * events on this connection, under the id chosen here — the desktop
     * namespaces it internally (`<connection>:<id>`) and echoes ours back, so
     * cancellation uses the same string.
     */
    suspend fun startSearch(root: String, query: String, isRegex: Boolean = false): String {
        cancelSearch()
        val id = "s" + Primitives.randomBytes(8).toHexString()
        val fold = SearchFold(id = id, query = query)
        publishSearch(fold)
        val params = buildJsonObject {
            put("id", id)
            put("root", root)
            put("query", query)
            put("isRegex", isRegex)
        }
        try {
            request("search.start", params)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // A brand-new fold carrying the failure: mutating the old one and
            // putting the same instance back emits nothing (StateFlow compares
            // by identity), which is exactly how results used to never appear.
            publishSearch(SearchFold(id, query).also { it.failed(error.message ?: "搜索失败") })
        }
        return id
    }

    suspend fun cancelSearch() {
        val fold = _searchView.value?.fold ?: return
        if (fold.state.done) return
        runCatching { request("search.cancel", buildJsonObject { put("id", fold.id) }) }
        publishSearch(SearchFold(fold.id, fold.query).also { it.cancelled() })
    }

    fun clearSearch() {
        publishSearch(null)
    }

    /** Leaving the files screen, from a scope that outlives it. */
    fun cancelSearchBackground() {
        val fold = _searchView.value?.fold ?: return
        scope.launch {
            if (!fold.state.done) runCatching { request("search.cancel", buildJsonObject { put("id", fold.id) }) }
            publishSearch(null)
        }
    }

    private fun onSearchProgress(payload: JsonElement) {
        val fold = _searchView.value?.fold ?: return
        val push = try {
            RcJson.decodeFromJsonElement(com.skidsense.mobile.model.SearchProgressPush.serializer(), payload)
        } catch (_: Exception) {
            return
        }
        // The same mutation StateFlow-filtered before; now the publish forces it.
        if (fold.apply(push)) publishSearch(fold)
    }

    // --- artifacts ------------------------------------------------------------------

    suspend fun artifacts(root: String?, limit: Int = 200): List<com.skidsense.mobile.model.ArtifactRow> =
        requestDecoded(
            "artifacts.list",
            buildJsonObject {
                if (root != null) put("root", root)
                put("limit", limit)
            },
            ListSerializer(com.skidsense.mobile.model.ArtifactRow.serializer())
        ) ?: emptyList()

    // --- files ----------------------------------------------------------------------

    suspend fun listDir(root: String, path: String, showIgnored: Boolean = false): ListDirResult? =
        requestDecoded(
            "fs.list",
            buildJsonObject {
                put("root", root)
                put("path", path)
                put("showIgnored", showIgnored)
            },
            ListDirResult.serializer()
        )

    suspend fun readFile(root: String, path: String): ReadFileResult? =
        requestDecoded("fs.read", buildJsonObject { put("root", root); put("path", path) }, ReadFileResult.serializer())

    suspend fun writeFile(root: String, path: String, text: String, etag: String?): WriteFileResult? =
        requestDecoded(
            "fs.write",
            buildJsonObject {
                put("root", root)
                put("path", path)
                put("text", text)
                if (etag != null) put("etag", etag)
            },
            WriteFileResult.serializer()
        )

    suspend fun fileOp(root: String, kind: String, path: String?, to: String?): com.skidsense.mobile.model.FileOpResult? =
        requestDecoded(
            "fs.op",
            buildJsonObject {
                put("root", root)
                put("kind", kind)
                if (path != null) put("path", path)
                if (to != null) put("to", to)
            },
            com.skidsense.mobile.model.FileOpResult.serializer()
        )

    // --- git ------------------------------------------------------------------------

    suspend fun gitSnapshot(root: String): GitSnapshot? =
        requestDecoded("git.snapshot", buildJsonObject { put("root", root) }, GitSnapshot.serializer())

    suspend fun gitDiff(root: String, path: String, against: String = "head"): com.skidsense.mobile.model.GitDiffResult? =
        requestDecoded(
            "git.diff",
            buildJsonObject { put("root", root); put("path", path); put("against", against) },
            com.skidsense.mobile.model.GitDiffResult.serializer()
        )

    suspend fun gitBranches(root: String): List<com.skidsense.mobile.model.GitBranchInfo> =
        requestDecoded("git.branches", buildJsonObject { put("root", root) }, ListSerializer(com.skidsense.mobile.model.GitBranchInfo.serializer()))
            ?: emptyList()

    suspend fun gitMutate(root: String, op: String, paths: List<String>? = null, message: String? = null, branch: String? = null): com.skidsense.mobile.model.GitMutateResult? =
        requestDecoded(
            "git.mutate",
            buildJsonObject {
                put("root", root)
                put("op", op)
                paths?.let { list -> putJsonArray("paths") { list.forEach { add(JsonPrimitive(it)) } } }
                if (message != null) put("message", message)
                if (branch != null) put("branch", branch)
            },
            com.skidsense.mobile.model.GitMutateResult.serializer()
        )

    // --- terminal -------------------------------------------------------------------

    suspend fun openTerminal(key: String, cols: Int, rows: Int): JsonElement? =
        request("tui.open", buildJsonObject { put("key", key); put("cols", cols); put("rows", rows) })

    suspend fun terminalInput(key: String, data: String) {
        request("tui.input", buildJsonObject { put("key", key); put("data", data) })
    }

    suspend fun terminalResize(key: String, cols: Int, rows: Int) {
        request("tui.resize", buildJsonObject { put("key", key); put("cols", cols); put("rows", rows) })
    }

    suspend fun closeTerminal(key: String) {
        request("tui.close", buildJsonObject { put("key", key) })
    }

    // --- settings -------------------------------------------------------------------

    fun setBiometricLock(enabled: Boolean) {
        files.write(LOCK_KEY, if (enabled) "1" else "0")
        update { it.copy(biometricLock = enabled, locked = if (enabled) it.locked else false) }
    }

    fun unlock() {
        update { it.copy(locked = false) }
    }

    fun lock() {
        if (_state.value.biometricLock) update { it.copy(locked = true) }
    }

    fun clearError() {
        update { it.copy(lastError = null) }
    }

    // --- persistence -----------------------------------------------------------------

    /** The pairings one account may see, as stored on disk. */
    private fun loadPaired(userId: Long, server: String): List<PairedHost> {
        val raw = files.read(PAIRED_KEY) ?: return emptyList()
        val all = runCatching {
            RcJson.decodeFromString(ListSerializer(PairedHost.serializer()), raw)
        }.getOrElse { emptyList() }
        return all.filter { it.visibleTo(userId, server) }
    }

    /**
     * Write [visible] — what the *current* account holds — back, preserving on
     * disk any pairing the current account cannot see (another account lives on
     * the same phone). A naive rewrite used to destroy those rows the first
     * time the short in-memory list was saved after a re-login (S33).
     */
    private fun persistPaired(visible: List<PairedHost>) {
        val userId = _state.value.userId
        val server = _state.value.baseUrl
        val raw = files.read(PAIRED_KEY)
        val others = raw?.let {
            runCatching { RcJson.decodeFromString(ListSerializer(PairedHost.serializer()), it) }.getOrNull()
        }.orEmpty().filterNot { it.visibleTo(userId, server) }
        files.write(PAIRED_KEY, RcJson.encodeToString(ListSerializer(PairedHost.serializer()), visible + others))
    }

    private fun update(block: (AppState) -> AppState) {
        _state.value = block(_state.value)
    }

    private companion object {
        const val IDENTITY_KEY = "device-static-key"
        const val PAIRED_KEY = "paired-hosts.json"
        const val LOCK_KEY = "biometric-lock"
    }
}
