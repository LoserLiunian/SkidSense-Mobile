package com.skidsense.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.skidsense.mobile.api.LoginChallenge
import com.skidsense.mobile.api.ServerStatus
import com.skidsense.mobile.app.AppController
import com.skidsense.mobile.platform.CaptchaWebView
import kotlinx.coroutines.launch

/**
 * 登录. new-api's own login (spec §12) with an optional GeeTest widget rendered
 * in a WebView, then a 2FA step when the account has one. The `geetest` query
 * parameter is the JSON of the four `getValidate()` fields, exactly as the
 * desktop sends it.
 */
@Composable
fun LoginScreen(app: AppController, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(app.state.value.baseUrl.ifEmpty { "https://ai.surise.cn" }) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var geetest by remember { mutableStateOf<String?>(null) }
    var turnstile by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<ServerStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var challenge by remember { mutableStateOf<LoginChallenge?>(null) }

    LaunchedEffect(Unit) {
        status = app.probe(base)
    }

    val needsCaptcha = status?.geetestCheck == true && !status?.geetestId.isNullOrBlank()
    val needsTurnstile = status?.turnstileCheck == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("SkidSense", style = MaterialTheme.typography.headlineSmall)
        Hint("用与电脑端相同的账号登录，才能连上你的电脑。")
        Spacer(Modifier.height(8.dp))

        if (challenge != null) {
            TwoFactorForm(
                challenge = challenge!!,
                busy = busy,
                error = error,
                onSubmit = { code ->
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            app.verifyTwoFactor(base, challenge!!.flowToken, code)
                            onSignedIn()
                        } catch (failure: Throwable) {
                            error = failure.message
                        } finally {
                            busy = false
                        }
                    }
                },
                onCancel = { challenge = null }
            )
            return@Column
        }

        OutlinedTextField(
            value = base,
            onValueChange = { base = it.trim() },
            label = { Text("服务器地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        )

        if (needsCaptcha) {
            Hint("人机验证")
            val captchaId = status?.geetestId
            if (geetest == null) {
                CaptchaWebView(
                    html = GeeTestPage.html(captchaId!!),
                    onResult = { result -> geetest = result },
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("已通过人机验证", MaterialTheme.colorScheme.primary, filled = true)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { geetest = null }) { Text("重新验证") }
                }
            }
        } else if (needsTurnstile) {
            Hint("人机验证（Turnstile）")
            if (turnstile == null) {
                CaptchaWebView(
                    html = TurnstilePage.html(status?.turnstileSiteKey.orEmpty()),
                    onResult = { result -> turnstile = result },
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            } else {
                Pill("已通过人机验证", MaterialTheme.colorScheme.primary, filled = true)
            }
        }

        ErrorBanner(error.orEmpty())

        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val result = app.login(base, username, password, geetest, turnstile)
                        if (result == null) onSignedIn() else challenge = result
                    } catch (failure: Throwable) {
                        error = failure.message
                        // A used GeeTest token is single-use: force a new solve.
                        geetest = null
                        turnstile = null
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && username.isNotBlank() && password.isNotEmpty() &&
                (!needsCaptcha || geetest != null) && (!needsTurnstile || turnstile != null),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp) else Text("登录")
        }
        TextButton(onClick = { scope.launch { status = app.probe(base) } }, enabled = !busy) {
            Text("测试连接")
        }
        if (status != null && status?.passwordLoginEnabled == false) {
            ErrorBanner("这个服务器关闭了密码登录")
        }
        Hint("扫码配对与登录是两件事：先在这里登录，再扫描电脑上的二维码。")
    }
}

@Composable
private fun TwoFactorForm(
    challenge: LoginChallenge,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    val methods = challenge.methods.filter { it.available }.map { it.method }
    Text("两步验证", style = MaterialTheme.typography.titleSmall)
    Hint(
        when {
            "2fa" in methods -> "输入验证器里的 6 位验证码，或一枚备用码。"
            methods.isEmpty() -> "账号要求二次验证。"
            else -> "可用方式：${methods.joinToString("、")}"
        }
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.trim() },
        label = { Text("验证码") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done)
    )
    ErrorBanner(error.orEmpty())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSubmit(code) }, enabled = !busy && code.isNotBlank(), modifier = Modifier.weight(1f)) {
            Text("验证")
        }
        OutlinedButton(onClick = onCancel, enabled = !busy) { Text("返回") }
    }
}

/**
 * GeeTest v4 in a WebView: the SDK draws its own captcha, and on success we
 * read `getValidate()`'s four fields back out and hand them over as JSON —
 * which is what goes in the `geetest` query parameter.
 */
object GeeTestPage {
    fun html(captchaId: String): String = """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <script src="https://static.geetest.com/v4/gt4.js"></script>
        <style>body{margin:0;font-family:sans-serif}</style>
        </head><body>
        <div id="box"></div>
        <script>
          function send(value) { window.SKIDSENSE_CAPTCHA.invoke(value); }
          initGeetest4({ captchaId: '$captchaId', product: 'float' }, function (captcha) {
            captcha.appendTo('#box');
            captcha.onSuccess(function () { send(JSON.stringify(captcha.getValidate())); });
            captcha.onError(function () { send(''); });
            captcha.onClose(function () { send(''); });
          });
        </script>
        </body></html>
    """.trimIndent()
}

/** Turnstile, same bridge, same contract: the token comes back as a string. */
object TurnstilePage {
    fun html(siteKey: String): String = """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
        <style>body{margin:0;font-family:sans-serif}</style>
        </head><body>
        <div class="cf-turnstile" data-sitekey="$siteKey" data-callback="onToken"></div>
        <script>
          function onToken(token) { window.SKIDSENSE_CAPTCHA.invoke(token); }
        </script>
        </body></html>
    """.trimIndent()
}
