package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val LocalSettingsNavigation = staticCompositionLocalOf<(String) -> Unit> { {} }

internal enum class RemoteTarget(val entry: String, val title: String, val description: String) {
    DSH("remoteDsh", "Remote DSH", "连接桌面端 DeepSeek Harness"),
    SSH("remoteSsh", "Remote SSH", "使用 SSH 密钥连接服务器"),
    COMPUTER("remoteComputer", "Remote Computer", "配对并连接远程电脑");

    val icon
        get() =
            when (this) {
                DSH -> AppIcons.RemoteRelay
                SSH -> AppIcons.Terminal
                COMPUTER -> AppIcons.RemoteComputer
            }
}

@Composable
internal fun RemoteOverview(navigate: (String) -> Unit) {
    SettingsGroup {
        RemoteTarget.entries.forEach { target ->
            SettingsLink(
                target.icon,
                target.title,
                target.description,
            ) {
                navigate(target.entry)
            }
        }
    }
}

@Composable
internal fun RemotePairingSettings(target: RemoteTarget) {
    DshaCard {
        Text(target.title, style = PrismTypography.headline)
        Text(target.description, style = PrismTypography.body)
        Text("配对功能正在规划中，本版先保留入口。", color = WhaleMuted, style = PrismTypography.footnote)
        Text("配对完成后，每个会话需要在右侧工作区单独开启访问。", color = WhaleMuted, style = PrismTypography.footnote)
    }
}

@Composable
internal fun RemoteAccessPane(target: RemoteTarget) {
    val navigate = LocalSettingsNavigation.current
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = LocalWorkspaceTopInset.current + 16.dp,
                bottom = 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${target.title} 访问", style = PrismTypography.headline)
        Text("当前会话尚无可授权的设备。配对功能开放后，可在这里逐项允许访问。", style = PrismTypography.body, color = WhaleMuted)
        SettingsGroup {
            SettingsLink(target.icon, "管理配对", target.description) { navigate(target.entry) }
        }
    }
}
