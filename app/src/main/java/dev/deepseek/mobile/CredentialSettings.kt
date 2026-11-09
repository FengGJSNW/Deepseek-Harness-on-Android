package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Only masked metadata reaches this UI. Secret bytes remain in KeyStore / native HTTP. */
@Composable
internal fun CredentialSettings(back: () -> Unit) {
    val state = AssistantState
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val info = state.credentials.firstOrNull { it.id == selected }
    LaunchedEffect(state.ready) { if (state.ready) AgentService.current?.perform("list_keys") }
    androidx.activity.compose.BackHandler(adding || selected != null) {
        adding = false
        selected = null
        key = ""
        name = ""
        result = ""
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallIcon(AppIcons.ArrowBack, "返回") {
                if (adding || selected != null) {
                    adding = false
                    selected = null
                    key = ""
                    name = ""
                    result = ""
                } else back()
            }
            Text(
                when {
                    selected != null -> "密钥详情"
                    else -> "DeepSeek API Key"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(
            Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                info != null -> {
                    Text(
                        info.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(info.masked, fontSize = 14.sp, color = WhaleMuted)
                    if (!info.selected)
                        TextButton(
                            onClick = {
                                AgentService.current?.perform(
                                    "select_key",
                                    JSONObject().put("id", info.id),
                                )
                            },
                            enabled = !state.busy,
                        ) {
                            Text("使用此密钥")
                        }
                    else Text("正在使用", fontSize = 12.sp, color = WhaleBlue)
                    TextButton(
                        onClick = {
                            testing = true
                            result = ""
                            AgentService.current?.perform(
                                "test_connection",
                                JSONObject().put("id", info.id),
                            ) {
                                testing = false
                                result = if (it != null) "连接成功" else "连接失败，请查看提示"
                            }
                        },
                        enabled = state.ready && !state.busy && !testing,
                    ) {
                        Text(if (testing) "连接中…" else "测试连接")
                    }
                    if (result.isNotBlank()) Text(result, fontSize = 12.sp, color = WhaleMuted)
                    HorizontalDivider(color = WhaleLine)
                    TextButton(onClick = { deleting = true }, enabled = !state.busy) {
                        Text("删除", color = Color(0xFFBC656B))
                    }
                }
                else -> {
                    TextButton(onClick = { adding = true }) {
                        Icon(AppIcons.Add, null, Modifier.size(18.dp))
                        Text("添加密钥", Modifier.padding(start = 8.dp))
                    }
                    HorizontalDivider(color = WhaleLine)
                    state.credentials.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selected = entry.id
                                    result = ""
                                }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry.name, Modifier.weight(1f), fontSize = 14.sp)
                            if (entry.selected)
                                Icon(
                                    AppIcons.Check,
                                    "正在使用",
                                    Modifier.padding(end = 12.dp).size(17.dp),
                                    tint = WhaleBlue,
                                )
                            Text("详情", fontSize = 12.sp, color = WhaleMuted)
                            Icon(
                                AppIcons.ChevronRight,
                                null,
                                Modifier.padding(start = 6.dp).size(14.dp),
                                tint = WhaleMuted,
                            )
                        }
                        HorizontalDivider(color = WhaleLine.copy(alpha = .65f))
                    }
                    if (state.credentials.isEmpty())
                        Text("还没有密钥", fontSize = 12.sp, color = WhaleMuted)
                }
            }
        }
    }
    if (adding)
        WhaleSheet(
            "添加密钥",
            close = {
                if (!saving) {
                    adding = false
                    key = ""
                    name = ""
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text("密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = ContinuousShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = ContinuousShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    visualTransformation = UprightSymbols.transformation,
                )
                Button(
                    onClick = {
                        saving = true
                        AgentService.current?.perform(
                            "save_key",
                            JSONObject().put("key", key).put("name", name),
                        ) {
                            saving = false
                            if (it != null) {
                                key = ""
                                name = ""
                                adding = false
                            }
                        }
                    },
                    enabled =
                        state.ready &&
                            !state.busy &&
                            !saving &&
                            key.isNotBlank() &&
                            name.isNotBlank(),
                    shape = ContinuousShape(14.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (saving) "保存中" else "保存")
                }
            }
        }
    if (deleting && info != null)
        WhaleSheet("删除密钥？", "仅从此设备移除“${info.name}”。", { deleting = false }) {
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { deleting = false }) { Text("取消") }
                TextButton(
                    onClick = {
                        AgentService.current?.perform(
                            "delete_key",
                            JSONObject().put("id", info.id),
                        ) {
                            if (it != null) selected = null
                            deleting = false
                        }
                    },
                ) {
                    Text("删除", color = Color(0xFFBC656B))
                }
            }
        }
}
