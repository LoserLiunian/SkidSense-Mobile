package com.skidsense.mobile.rc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skidsense.mobile.app.AndroidEnvironment
import com.skidsense.mobile.store.AndroidSecretStore
import com.skidsense.mobile.store.getString
import com.skidsense.mobile.store.putString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does a login survive the app being started again?
 *
 * Half of what "the app opens on the login form after every restart" needed:
 * the stored session must actually come back. It did — the bug was the
 * routing, which never left the login screen (`landingScreen`, `RoutingTest`)
 * and never noticed state changes at all. This keeps the storage half from
 * regressing on a real device, where the Keystore is.
 *
 * Two layers, so a failure says which: the secret store across two instances
 * (prefs + Keystore are what a cold start shares), then the real
 * `AndroidEnvironment` — a login through it (needs `loginBackend`, `loginUser`
 * and `loginPassword` arguments; the test says so and passes without them), then a second environment built the
 * way `MainActivity` builds one.
 */
@RunWith(AndroidJUnit4::class)
class SessionPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aSecretWrittenByOneStoreIsReadByTheNext () {
        AndroidSecretStore(context).putString("persistence-probe", "持久化探针")
        val again = AndroidSecretStore(context).getString("persistence-probe")
        println("SKIDSENSE_PERSIST secret store across instances → $again")
        assertEquals("第二个实例读得回第一个实例写的值", "持久化探针", again)
    }

    @Test
    fun aLoginSurvivesANewEnvironment () = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("loginBackend") ?: run {
            println("SKIDSENSE_PERSIST=skip 没有提供 loginBackend")
            return@runBlocking
        }
        val first = AndroidEnvironment(context)
        val challenge = first.backend.login(base, args.getString("loginUser") ?: "", args.getString("loginPassword") ?: "", null, null)
        println("SKIDSENSE_PERSIST login challenge=$challenge session=${first.backend.session.value?.baseUrl}")
        assertNotNull("第一次登录后有会话", first.backend.session.value)
        first.close()

        val second = AndroidEnvironment(context)
        val restored = second.backend.session.value
        println("SKIDSENSE_PERSIST 新环境恢复的会话 = ${restored?.baseUrl} user=${restored?.username}")
        second.close()
        assertNotNull("新环境（等同冷启动）恢复出了会话", restored)
        assertEquals("恢复的是同一个服务器", base.trimEnd('/'), restored!!.baseUrl)
    }
}
