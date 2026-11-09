package dev.deepseek.mobile

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** Login is entirely Compose. Only a provider security challenge may attach the hidden DOM view. */
@Composable
internal fun OfficialLoginIsland() {
    val chat = OfficialChatNavigation
    if (!chat.loginOpen) return
    val auth = chat.auth
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var account by remember { mutableStateOf(TextFieldValue()) }
    var credential by remember(auth.mode) { mutableStateOf(TextFieldValue()) }
    var visible by remember(auth.mode) { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf(false) }
    LaunchedEffect(auth.mode) {
        waiting = false
        delay(6000)
        waiting = true
    }
    LaunchedEffect(auth.challenge) {
        if (auth.challenge && chat.showVerification()) verification = true
        if (!auth.challenge) verification = false
    }
    WhaleSheet("登录 DeepSeek Chat", close = { chat.cancelLogin() }) {
        Column(
            Modifier.fillMaxWidth().testTag("official-login-native"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("登录成功后进入 Chat，Work 草稿会保留。", fontSize = 12.sp, color = WhaleMuted)
            if (auth.mode !in listOf("sms", "password")) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "正在连接官方账户…",
                        Modifier.padding(start = 12.dp),
                        color = WhaleMuted,
                        fontSize = 13.sp,
                    )
                }
                if (waiting || chat.failure.isNotBlank())
                    TextButton(onClick = { chat.retry(context) }) { Text("重新连接") }
            } else {
                val password = auth.mode == "password"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(
                        onClick = {
                            credential = TextFieldValue()
                            chat.setLoginMode("sms")
                        },
                        enabled = !chat.authBusy,
                    ) {
                        Text("验证码登录", color = if (!password) WhaleBlue else WhaleMuted)
                    }
                    TextButton(
                        onClick = {
                            credential = TextFieldValue()
                            chat.setLoginMode("password")
                        },
                        enabled = !chat.authBusy,
                    ) {
                        Text("密码登录", color = if (password) WhaleBlue else WhaleMuted)
                    }
                }
                OutlinedTextField(
                    account,
                    { account = it },
                    label = { Text(if (password) "手机号 / 邮箱" else "手机号（+86）") },
                    leadingIcon = {
                        Icon(AppIcons.Chat, null, Modifier.size(19.dp), tint = WhaleMuted)
                    },
                    singleLine = true,
                    enabled = !chat.authBusy,
                    shape = ContinuousShape(14.dp),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = if (password) KeyboardType.Email else KeyboardType.Phone,
                        ),
                    modifier = Modifier.fillMaxWidth().testTag("official-login-account"),
                    visualTransformation = UprightSymbols.transformation,
                )
                OutlinedTextField(
                    credential,
                    { credential = if (password) it else it.copy(text = it.text.take(6)) },
                    label = { Text(if (password) "密码" else "验证码") },
                    leadingIcon = {
                        Icon(AppIcons.Key, null, Modifier.size(19.dp), tint = WhaleMuted)
                    },
                    trailingIcon = {
                        if (password)
                            TextButton(onClick = { visible = !visible }) {
                                Text(if (visible) "隐藏" else "显示", fontSize = 12.sp)
                            }
                        else
                            TextButton(
                                onClick = {
                                    chat.submitLogin(
                                        "sms",
                                        account.text.trim(),
                                        "",
                                        codeOnly = true,
                                    )
                                },
                                enabled =
                                    !chat.authBusy &&
                                        auth.codeEnabled &&
                                        Regex("1[0-9]{10}").matches(account.text.trim()) &&
                                        agreed,
                            ) {
                                Text(auth.codeLabel, fontSize = 12.sp)
                            }
                    },
                    visualTransformation =
                        if (password && !visible) PasswordVisualTransformation()
                        else VisualTransformation.None,
                    singleLine = true,
                    enabled = !chat.authBusy,
                    shape = ContinuousShape(14.dp),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                if (password) KeyboardType.Password
                                else KeyboardType.NumberPassword,
                        ),
                    modifier = Modifier.fillMaxWidth().testTag("official-login-secret"),
                )
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        agreed,
                        { agreed = it },
                        Modifier.size(30.dp).testTag("official-login-agreement"),
                    )
                    Column(Modifier.weight(1f).padding(start = 5.dp)) {
                        Text(
                            "我已阅读并同意 DeepSeek 的用户协议与隐私政策。",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = WhaleMuted,
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    uri.openUri(
                                        "https://cdn.deepseek.com/policies/zh-CN/deepseek-terms-of-use.html",
                                    )
                                },
                                contentPadding = PaddingValues(end = 10.dp),
                            ) {
                                Text("用户协议", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = {
                                    uri.openUri(
                                        "https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html",
                                    )
                                },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text("隐私政策", fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (!password)
                    Text("未注册的手机号将由 DeepSeek 自动注册。", fontSize = 11.sp, color = WhaleMuted)
                Button(
                    onClick = { chat.submitLogin(auth.mode, account.text.trim(), credential.text) },
                    enabled =
                        agreed &&
                            !chat.authBusy &&
                            account.text.isNotBlank() &&
                            if (password) credential.text.isNotEmpty()
                            else Regex("[0-9]{6}").matches(credential.text),
                    shape = ContinuousShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("official-login-submit"),
                ) {
                    Text(if (chat.authBusy) "正在提交…" else "登录并进入 Chat")
                }
                if (auth.challenge && !verification)
                    TextButton(onClick = { verification = true }) { Text("完成官方安全验证") }
            }
            val message = chat.failure.ifBlank { auth.error }
            if (message.isNotBlank())
                Text(
                    message,
                    fontSize = 12.sp,
                    color = WhaleMuted,
                    modifier = Modifier.testTag("official-login-error"),
                )
        }
    }
    if (verification) {
        DisposableEffect(Unit) { onDispose { chat.hideVerification() } }
        CenterIsland({ verification = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DeepSeek 安全验证", Modifier.weight(1f), fontSize = 16.sp)
                    SmallIcon(AppIcons.Close, "关闭安全验证") { verification = false }
                }
                Text("请完成官方提供的验证。", fontSize = 12.sp, color = WhaleMuted)
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    factory = { ctx ->
                        android.widget.FrameLayout(ctx).apply {
                            chat.view?.let {
                                (it.parent as? ViewGroup)?.removeView(it)
                                addView(it, ViewGroup.LayoutParams(-1, -1))
                            }
                        }
                    },
                    onRelease = { host ->
                        host.removeAllViews()
                        chat.measure()
                    },
                )
            }
        }
    }
}
