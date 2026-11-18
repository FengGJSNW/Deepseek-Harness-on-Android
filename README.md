<div align="center">
  <img src="app/src/main/res/drawable-nodpi/whale_chibi_expanded.png" width="150" alt="Deepseek Harness on Android 应用图标">

  # Deepseek Harness on Android

  **在 Android 上使用 DeepSeek Harness 的助手与工作空间**

  Android 11 及以上（API 30+） · ARM64 · [下载最新版本](https://github.com/FengGJSNW/Deepseek-Harness-on-Android/releases/latest)
</div>

## 界面预览

以下截图来自 Android 模拟器；会话内容为专门构造的示例，展示 Anthropic 风格字体预设与 Markdown 排版。

| 会话与 Markdown | 会话侧栏 | Sandbox 管理 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/conversation.png" width="250" alt="会话与 Markdown 排版"> | <img src="docs/screenshots/drawer.png" width="250" alt="会话侧栏"> | <img src="docs/screenshots/sandbox.png" width="250" alt="Sandbox 管理"> |

## 主要功能

- **会话与任务**：Work 会话支持 DeepSeek API、自定义任务模式、任务进度、Markdown、代码和数学内容渲染；Chat 可连接 DeepSeek 官方网页。模型服务需要用户自行配置或登录。
- **DSHA Sandbox**：在应用中管理独立的 Linux 虚拟环境、磁盘和会话访问授权。Sandbox 使用 v86 和 Alpine x86；虚拟机里的 root 不等同于 Android 系统 root。安装包不预装镜像，可按下文导入 ZIP。
- **内置浏览器**：支持本机网页浏览，以及 DeepSeek 官方搜索和网页读取接口；可在设置中选择提供给 Agent 的浏览器能力。
- **Shell / Root**：终端支持普通应用权限、Shizuku Shell 和 Root 等连接方式。高级权限需要设备环境及用户授权；Sandbox 与手机终端的权限分开管理。
- **扩展与工具**：支持按需安装和管理 Agent Skills、MCP 服务与 JavaScript 插件，也提供工作区文件、项目记忆及任务规划等工具；实际可用范围由用户授权与任务模式决定。
- **集成 FlClash**：内置 FlClash 的代理、配置及 VPN 界面，并扩展 Agent 行为授权与 DSHA 的调用入口。应用还参考和移植了其 `prism_ui` 组件与交互动效。

## 安装与镜像

1. 从 [Releases](https://github.com/FengGJSNW/Deepseek-Harness-on-Android/releases) 下载 Release APK 并安装。应用支持 Android 11 及以上；本次 Release APK 为 ARM64 架构。
2. 如需使用 Sandbox，下载同一版本的 `DSHA-Linux-Lite-0.8.6.zip` 和按需使用的 `DSHA-Tool-0.8.6.zip`，在应用的 **Sandbox → 导入镜像 ZIP** 中选择文件。工具包用于按需提供开发工具，不是独立安装包。
3. 在应用中配置模型服务；需要手机操作时，再按提示开启无障碍服务。Shizuku、Root 和 VPN 功能分别需要对应环境或系统授权。

下载内容的 SHA-256 及源码包见 Release。Sandbox 使用 32 位 x86 软件模拟，功能和性能与原生 Linux 主机不同；它不提供 Docker、GPU 加速或 Android 系统 root。

## 构建

需要 JDK 21、Node.js 24、Android SDK 37、NDK 28.2.13676358、CMake 3.22.1，以及固定版本的 Flutter、Go 和 Rust。FlClash 的源码锁定版本、补丁与构建步骤见 [集成说明](integrations/README.md)。

```powershell
npm ci
node scripts/setup-upstream.mjs
pwsh scripts/setup-flclash.ps1
pwsh scripts/build-flclash.ps1
npm run verify
```

项目不包含发布签名密钥；没有本地发布密钥时，可构建 Debug APK。Sandbox 镜像可通过 `node scripts/prepare-sandbox-bundle.mjs` 和 `python scripts/package-sandbox-images.py --output <目录>` 单独制作。

## 致谢与许可

感谢 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)、[FlClash](https://github.com/chen08209/FlClash)、[prism_ui](https://github.com/chen08209/prism_ui) 和 [Mihomo](https://github.com/MetaCubeX/mihomo) 的作者与贡献者。DSHA 内置并扩展了 FlClash，也使用和移植了其 UI 组件；相关源码版本与修改见 [集成说明](integrations/README.md)。

项目自有代码按 [GPL-3.0-only](LICENSE) 发布；第三方代码、字体和美术素材分别遵守原有许可。FlClash、prism_ui 与 Mihomo 使用 GPL-3.0；DeepSeek Harness 上游代码使用 MIT。鲸娘图标与部分陪伴素材的授权范围不同于代码许可，来源及使用限制见 [第三方说明](NOTICE.md) 和对应素材的来源文件。
