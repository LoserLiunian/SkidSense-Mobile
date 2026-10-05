package com.skidsense.mobile.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skidsense.mobile.platform.platformHttpClient
import com.skidsense.mobile.transport.CarrierFactory
import com.skidsense.mobile.transport.ClientConfig
import com.skidsense.mobile.transport.Credentials
import com.skidsense.mobile.transport.Enrollment
import com.skidsense.mobile.transport.KtorCarrierFactory
import com.skidsense.mobile.transport.RcClient
import com.skidsense.mobile.transport.Route
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real phone talking to a **real desktop**.
 *
 * Everywhere else, one side is a stand-in: the desktop's `verify:remote-lan`
 * pairs a hand-written TypeScript "phone" against the real listener, and this
 * module's own tests drive a `FakeHost` over an in-memory pipe. Neither can
 * catch a disagreement *between the two implementations* — a field name, a
 * base64 variant, an HKDF info string, an ordering of the four DH terms, or a
 * method name that one side spells differently.
 *
 * This test closes that gap. It is driven by `scripts/review-host-harness.ts`
 * in the desktop repo, which brings up a real `Host` + `RemoteManager` + LAN
 * listener, points them at a stand-in backend that signs grants the way new-api
 * does, and prints a real pairing link. The link, the backend address and the
 * account token are handed in as instrumentation arguments:
 *
 *   ./gradlew :shared:connectedAndroidDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.pairing='skidsense://pair/1?d=…' \
 *     -Pandroid.testInstrumentationRunnerArguments.backend='http://192.168.0.173:60695' \
 *     -Pandroid.testInstrumentationRunnerArguments.token='review-access-token'
 *
 * The test is skipped when the arguments are absent, so it never fails a
 * plain `connectedAndroidDeviceTest` run.
 */
@RunWith(AndroidJUnit4::class)
class RealHostPairingTest {
    private val args = InstrumentationRegistry.getArguments()
    private val link = args.getString("pairing")
    private val backend = args.getString("backend")
    private val token = args.getString("token") ?: "review-access-token"
    private val workdirName = args.getString("workdir")
    /**
     * `relay` runs the same test with the LAN carrier disallowed, so the only
     * way in is the backend's relay — the one path where new-api's own Go
     * forwarding sits between the two implementations.
     */
    private val viaRelay = args.getString("via") == "relay"

    private val client by lazy { platformHttpClient(forWebSockets = false) }
    // The WebSockets plugin is not installed by `platformHttpClient` itself — the
    // app installs it in `AndroidEnvironment`, so a test that opens a real
    // carrier has to as well. NOT the same settings as the app: the app also sets
    // `maxFrameSize`, which the OkHttp engine rejects on every session, so this
    // client leaves it out in order to get past the carrier and test the
    // protocol. `AppEnvironmentCarrierTest` is the one that uses the app's own
    // client, and it is the one that fails on that setting.
    private val socketClient by lazy {
        platformHttpClient(forWebSockets = true) {
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun raw (method: String, path: String, body: String? = null): JsonObject {
        val response = when (method) {
            "GET" -> client.get("$backend$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            else -> client.post("$backend$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body ?: "{}")
            }
        }
        val text = response.bodyAsText()
        val root = json.parseToJsonElement(text).jsonObject
        check(root["success"]?.jsonPrimitive?.content != "false") { "backend said: $text" }
        return root["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    @Test
    fun pairsWithARealDesktopOverTheLan () = runBlocking {
        if (link.isNullOrBlank() || backend.isNullOrBlank()) {
            println("SKIDSENSE_E2E=skip 没有提供 pairing/backend 参数")
            return@runBlocking
        }
        val payload = Pairing.decode(link)
        println("SKIDSENSE_E2E host=${payload.hostId} name=${payload.machine} port=${payload.lanPort} addrs=${payload.lanAddrs}")

        // 1. The QR the desktop drew decodes here, with the host key pinned from it.
        //    In relay mode the payload legitimately carries no LAN route — a host
        //    with the LAN carrier off advertises port 0 and an empty list (spec
        //    §3), and the relay is the only way in. Asserting a LAN address there
        //    would be asserting the opposite of the design.
        assertTrue("配对码里的主机密钥是 32 字节", payload.hostKey.size == 32)
        if (viaRelay) {
            assertTrue("纯中继的配对码不带局域网地址", payload.lanAddrs.isEmpty())
            assertEquals("纯中继的配对码端口为 0", 0, payload.lanPort)
            assertTrue("配对码仍指向后端", payload.server.startsWith("http"))
        } else {
            assertTrue("配对码带局域网地址", payload.lanAddrs.isNotEmpty())
            assertTrue("配对码带端口", payload.lanPort in 1..65535)
        }

        // 2. Register the device on the backend, as the app does before enrolling.
        val identity = Primitives.generateKeyPair()
        val registration = raw("POST", "/api/companion/devices", """
            {"host_id":"${payload.hostId}","name":"真机复查","platform":"android",
             "public_key":"${B64u.encode(identity.pub)}"}
        """.trimIndent())
        val device = registration["device"]!!.jsonObject
        val deviceId = device["device_id"]!!.jsonPrimitive.content
        val ticket = registration["ticket"]!!.jsonPrimitive.content
        println("SKIDSENSE_E2E device=$deviceId ticket=${ticket.take(24)}…")

        val carriers: CarrierFactory = KtorCarrierFactory(
            client = socketClient,
            backendBase = { backend },
            relayPath = { "/api/companion/ws" },
            bearer = { token }
        )

        // 3. The enroll handshake, over the LAN, with the pairing code as PSK.
        val welcome = Enrollment.enroll(
            payload = payload,
            deviceId = deviceId,
            identity = identity,
            ticket = ticket,
            carriers = carriers,
            scope = scope,
            config = ClientConfig(),
            onProgress = { println("SKIDSENSE_E2E $it") }
        )
        println("SKIDSENSE_E2E welcome host=${welcome.host.id} device=${welcome.device.id} scopes=${welcome.device.scopes} methods=${welcome.methods.size}")

        // 4. Two independently written implementations agreed on the whole handshake,
        //    or nothing above would have returned.
        assertEquals("welcome 里的 host 就是配对的那台", payload.hostId, welcome.host.id)
        assertEquals("welcome 里的设备就是刚注册的那台", deviceId, welcome.device.id)
        assertTrue("默认含文件权限", welcome.hasScope("files"))
        assertTrue("默认不含终端权限", !welcome.hasScope("terminal"))
        assertTrue("方法表里没有终端", welcome.methods.none { it.startsWith("tui.") })
        assertTrue("方法表里有会话列表", welcome.can("sessions.list"))

        // 5. Now an ordinary connect handshake with a grant, and a real call.
        val rc = RcClient(
            endpoint = com.skidsense.mobile.transport.HostEndpoint(
                hostId = payload.hostId,
                hostKey = payload.hostKey,
                deviceId = deviceId,
                lanAddrs = payload.lanAddrs,
                lanPort = payload.lanPort,
                relayEnabled = viaRelay
            ),
            identity = identity,
            credentials = object : Credentials {
                override suspend fun grant (fresh: Boolean): String {
                    val granted = raw("POST", "/api/companion/grant", """{"host_id":"${payload.hostId}","device_id":"$deviceId"}""")
                    return granted["grant"]!!.jsonPrimitive.content
                }
            },
            carriers = carriers,
            scope = scope,
            config = ClientConfig()
        )
        rc.start()
        var waited = 0
        while (rc.state.value !is com.skidsense.mobile.transport.ClientState.Connected && waited < 15_000) {
            delay(200); waited += 200
        }
        val state = rc.state.value
        println("SKIDSENSE_E2E state=$state after ${waited}ms")
        val connected = state as com.skidsense.mobile.transport.ClientState.Connected
        assertTrue(
            if (viaRelay) "经真实中继连上了真实桌面（$state）" else "经局域网连上了真实桌面（$state）",
            connected.route is Route.Relay || connected.route is Route.Lan
        )
        assertTrue(
            if (viaRelay) "走的是中继这条路，实际 ${connected.route}" else "走的是局域网这条路，实际 ${connected.route}",
            if (viaRelay) connected.route is Route.Relay else connected.route is Route.Lan
        )

        val sessions = rc.call("sessions.list")
        println("SKIDSENSE_E2E sessions.list → ${sessions.toString().take(200)}")

        // The workspace root the desktop registered, not a guess: `root` must be a
        // path this host has open, and only the host knows it.
        val workspaces = rc.call("workspaces.list")
        println("SKIDSENSE_E2E workspaces.list → ${workspaces.toString().take(300)}")
        val root = json.parseToJsonElement(workspaces.toString()).jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["path"]?.jsonPrimitive?.content?.endsWith("project") == true }
            ?.get("path")?.jsonPrimitive?.content
            ?: json.parseToJsonElement(workspaces.toString()).jsonArray.firstOrNull()?.jsonObject?.get("path")?.jsonPrimitive?.content
            ?: throw AssertionError("桌面没有注册任何工作区：$workspaces")
        println("SKIDSENSE_E2E 工作区根 = $root")

        val listed = rc.call("fs.list", buildJsonObject {
            put("root", JsonPrimitive(root))
            put("path", JsonPrimitive(""))
        })
        println("SKIDSENSE_E2E fs.list → ${listed.toString().take(300)}")
        assertTrue("真实桌面返回了目录内容", listed.toString().contains("hello.txt"))

        val file = rc.call("fs.read", buildJsonObject {
            put("root", JsonPrimitive(root))
            put("path", JsonPrimitive("hello.txt"))
        })
        val content = file.toString()
        println("SKIDSENSE_E2E fs.read → ${content.take(200)}")
        assertTrue("读到的正是桌面上那个文件的内容", content.contains("你好，手机"))

        // 5b. A whole turn driven from the phone: the push path (`ev` frames out
        //     of the real TurnEngine), an approval asked on the desktop and
        //     answered here, and the desktop recording which device answered.
        val events = java.util.concurrent.CopyOnWriteArrayList<com.skidsense.mobile.transport.RcEvent>()
        val collector = scope.launch { rc.events.collect { events.add(it) } }
        val row = rc.call("sessions.new", buildJsonObject {
            put("agent", JsonPrimitive("echo"))
            put("workdir", JsonPrimitive(root))
        }).jsonObject
        val key = row["key"]!!.jsonPrimitive.content
        println("SKIDSENSE_E2E sessions.new → $key")
        rc.subscribe(key)
        val prompt = "来自真机的问候 ${System.currentTimeMillis()}"
        val accepted = rc.call("turn.prompt", buildJsonObject {
            put("sessionKey", JsonPrimitive(key))
            put("prompt", JsonPrimitive(prompt))
        })
        println("SKIDSENSE_E2E turn.prompt → $accepted")

        suspend fun snapshot (): JsonObject? =
            rc.call("turn.snapshot", buildJsonObject { put("key", JsonPrimitive(key)) }) as? JsonObject
        var pending: String? = null
        for (i in 0 until 75) {
            val snap = snapshot()
            pending = (snap?.get("interactions") as? kotlinx.serialization.json.JsonArray)
                ?.map { it.jsonObject }
                ?.firstOrNull { it["answeredAt"] == null || it["answeredAt"] is kotlinx.serialization.json.JsonNull }
                ?.get("id")?.jsonPrimitive?.content
            if (pending != null) break
            delay(200)
        }
        println("SKIDSENSE_E2E 待审批 = $pending")
        assertTrue("桌面端的回合向手机提出了审批", pending != null)
        rc.call("turn.interact", buildJsonObject {
            put("key", JsonPrimitive(key))
            put("interactionId", JsonPrimitive(pending!!))
            put("answer", buildJsonObject { put("action", JsonPrimitive("select")); put("choiceId", JsonPrimitive("allow")) })
        })

        // `turn.snapshot` answers only while the turn is live (Host.snapshot reads
        // the active engine), so completion is read from the recorded turn that
        // `sessions.open` returns once it has settled.
        var final: JsonObject? = null
        for (i in 0 until 150) {
            if (snapshot() == null) {
                val opened = rc.call("sessions.open", buildJsonObject { put("key", JsonPrimitive(key)) }).jsonObject
                final = (opened["turns"] as? kotlinx.serialization.json.JsonArray)?.lastOrNull()
                    ?.jsonObject?.get("snapshot")?.jsonObject
                if (final != null) break
            }
            delay(200)
        }
        println("SKIDSENSE_E2E 记录里的回合 phase=${final?.get("phase")} 文本含提示词=${final?.get("text")?.jsonPrimitive?.content?.contains(prompt)}")
        assertTrue("回合文本回显了手机发的提示词", final?.get("text")?.jsonPrimitive?.content?.contains(prompt) == true)
        collector.cancel()
        val patches = events.filter { it.kind == "session.patch" }
        val answeredBy = (final?.get("interactions") as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.jsonObject?.get("answeredBy")?.jsonPrimitive?.content
        println("SKIDSENSE_E2E 回合结束 phase=${final?.get("phase")} 推送 session.patch=${patches.size} 条 answeredBy=$answeredBy")
        assertTrue("回合完成且回显了手机发的提示词", final != null)
        assertTrue("手机经推送路径收到了流式 session.patch", patches.isNotEmpty())
        assertEquals("桌面端记下了是哪台设备批的", "真机复查", answeredBy)

        // 5c. Optional: sit idle and watch whether the connection survives. A
        //     phone watching a long turn sends nothing for minutes, so a carrier
        //     that drops idle peers shows up here as a state change.
        val idleSeconds = args.getString("idle")?.toIntOrNull() ?: 0
        if (idleSeconds > 0) {
            val changes = java.util.concurrent.CopyOnWriteArrayList<String>()
            val start = System.currentTimeMillis()
            val watcher = scope.launch {
                rc.state.collect { s ->
                    val label = when (s) {
                        is com.skidsense.mobile.transport.ClientState.Connected -> "Connected(${s.route.label})"
                        else -> s.toString().take(120)
                    }
                    changes.add("+${(System.currentTimeMillis() - start) / 1000}s $label")
                }
            }
            delay(idleSeconds * 1000L)
            watcher.cancel()
            println("SKIDSENSE_E2E 静置 ${idleSeconds}s 期间的状态变化：$changes")
            assertEquals("静置期间连接一直在线（只应有初始那一条 Connected）", 1, changes.size)
        }

        // 6. A method this device was never granted must be refused by the host.
        var refused = false
        try { rc.call("tui.open", buildJsonObject { put("key", JsonPrimitive("claude:x")) }) }
        catch (error: Throwable) { refused = true; println("SKIDSENSE_E2E tui.open 被拒：${error.message}") }
        assertTrue("没有终端权限时 tui.open 被拒", refused)

        rc.stop()
        println("SKIDSENSE_E2E=pass")
    }
}
