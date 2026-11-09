# Deepseek Harness

Deepseek Harness 是独立开发的 Android 助手。本仓库提供 0.8.3.1 的源码，应用要求 Android 11 或更新版本。

- Work 会话通过用户配置的 DeepSeek API Key 调用模型，支持任务队列、子 Agent、终端和项目上下文。
- 浏览器可选择 DeepSeek 官方网页搜索与抓取，也可使用本机 WebView 操作网页。
- 在「设置 → 扩展 → 插件 → 让 Agent 创建插件」打开 Creator 会话。Agent 可以编写并安装离线 JavaScript 工具，同一会话可立即试用。
- 手机操作使用 Android 无障碍服务，需由用户在系统设置里启用。API Key 在应用内设置，开发和构建无需填入密钥。

## 构建

准备 JDK 17 或 21、Node.js 22、Git、curl 和 Android SDK。SDK 需要安装 Platform 36、NDK 28.2.13676358 与 CMake 3.22.1。设置 `JAVA_HOME` 和 `ANDROID_HOME`，或通过 Android Studio 选择本机 SDK。首次构建会下载 Gradle 依赖和经过 SHA-256 校验的 Mihomo 构建输入。

在 Windows 运行：

~~~powershell
.\gradlew.bat :app:assembleDebug
~~~

在 macOS 或 Linux 运行：

~~~sh
./gradlew :app:assembleDebug
~~~

APK 生成在 `app/build/outputs/apk/debug/`，使用本机的调试签名。

## 修改 Web 代码

仓库包含预编译的 Web 资源。修改 `web/` 时，先在仓库根目录检出 `upstream.lock.json` 指定的 DeepSeek Harness 版本，再重建资源：

~~~sh
git init upstream
git -C upstream remote add origin https://github.com/deepseek-ai/deepseek-harness.git
git -C upstream fetch --depth=1 origin 22ddee3792402c3e70ec5f86a63c3e3e895a2f60
git -C upstream checkout --detach FETCH_HEAD
npm ci
npm run build
npm test
~~~

第三方代码和资源的许可见 [NOTICE](NOTICE.md)。公开版使用简化的鲸鱼图标、陪伴动画和触碰音效。
