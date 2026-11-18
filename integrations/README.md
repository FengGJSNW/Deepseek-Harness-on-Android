# 内置 FlClash

DSHA 的“内置 FlClash”打开原项目的 Flutter 页面，保留仪表盘、代理、配置、工具与原生 VPN 服务。只在工具页的设置分组添加“Agent 行为”；DSHA 当前源码版本为 0.8.6 / 36；本轮 AAR 已重新编译并校验实际源码及产物摘要。

`flclash.lock.json` 固定 FlClash、prism_ui 和 Mihomo 的提交。`flclash.patch` 保存相对于该提交的全部适配，包括新增 Dart 文件和生成的语言资源。`flclash/` 是可重建的完整源码检出，不是独立维护的 UI 仿制品。`android/` 为 DSHA Android 宿主的库模块适配；其中 core 的构建任务来自固定的原项目。

## 构建

准备锁文件中列出的 Flutter、Go、Rust、Gradle，以及 JDK 21、Android SDK 37.0、NDK 28.2.13676358、CMake 3.22.1。Rust 需要 `aarch64-linux-android` 和 `x86_64-linux-android` 目标。Windows 额外需要可加载的 libclang DLL，用 `LIBCLANG_PATH` 指向它所在的目录。

设置 `FLUTTER_ROOT`、`JAVA_HOME`、`ANDROID_HOME`、`PUB_CACHE` 和可选的 `GRADLE_USER_HOME`；Go、Cargo、Rustup 须在 PATH 中。跨盘的 Windows 项目应将 PUB_CACHE 和 TEMP 放在源码所在盘。网络代理通过进程级 HTTP_PROXY/HTTPS_PROXY 与 Gradle 的标准代理参数设置，不写入仓库。

```powershell
./scripts/setup-flclash.ps1
./scripts/build-flclash.ps1
npm run verify:release
```

构建脚本生成 `.tmp/flclash-module`，复用原源码目录并生成 Flutter release AAR Maven 仓库。原 `android/` 项目不被 Flutter 模块模板覆盖。生成模板中的 SDK/Gradle 版本在脚本内统一到 FlClash 的版本；Gradle 9.3 在 Windows 目录链接下的 Kotlin DSL 缺陷由 9.8 避开。AAR 的旧 Dokka 文档生成任务停用，运行时代码和资产完整保留。

Android 宿主使用同一份 release AAR 来构建 debug/release，避免启动两个 Dart 引擎。Flutter 引擎按需启动，Activity 关闭后继续由共享运行时管理。JVM 堆和编译并发有界，首次构建仍需下载并编译 Go、Rust 依赖。

每次 AAR 构建记录固定提交、实际源码摘要和产物 SHA256；`verify` 在编译宿主前检查记录，源码或 AAR 变化后必须重新构建。Windows 的 bindgen 使用带引号的 NDK 路径和 NDK C 标准头文件，支持 SDK 安装路径包含空格。

## 接口与隔离

- 只保留 DSHA 的应用内 `proxy_control`，无外部 MCP 端口。工具仍经过 DSHA 工作模式与能力权限检查。
- 启停、选节点、改规则分别授权，默认关闭；旧授权只迁移一次。每次调用和原生服务派发前复核权限及请求是否仍有效，取消不会被报告为成功。
- 旧配置、订阅地址和节点选择逐项导入，旧文件保留。配置写入成功后才记录迁移完成，重试采用稳定编号避免重复导入。
- 运行状态与混合端口同步给 DSHA 的网页和终端；停止后清除网页覆盖代理。VPN 授权等待期间再次检查 Agent 请求，撤销或取消不会在稍后启动服务。
- FlClash 使用独立的 `files/flclash`、缓存目录和通知编号。其文档提供器限制在 FlClash 子目录，不能浏览 DSHA 的聊天和凭据。
- 聊天玻璃使用独立的内容显示列表，避开顶栏自身，聊天与工作区切换时底座、点击区域和图标位置保持连续。
- 关于页显示固定的 FlClash 组件版本。原检查更新保留，使用上游发布页入口；独立 FlClash APK 不能替换 DSHA 宿主。返回键从 FlClash 返回原聊天 Activity，代理服务保留原生命周期。

## 源码与许可

FlClash 与其固定子模块的 LICENSE 保留在源码检出中。应用另包含 `assets/licenses/FlClash-GPL-3.0.txt`。本次已包含实际 FlClash 代码，不能继续将整份 APK 描述成只参考其风格、未包含 GPL 代码。打包对应源码时需包含宿主源码、锁文件、补丁、完整 FlClash 和子模块源码及其许可证，不包含签名密钥、账户资料、缓存和设备数据。

验证记录位于 `qa/v085-ui/`；编译、JVM/Flutter 测试与真实设备验证分别记录，不能将编译成功等同于手机验证通过。

0.8.6 慢下载加速通过明确用户同意的 native 请求发起，用户请求与 Agent 请求分别登记；没有给 Agent 新增绕过 `FlClashAgentPolicy` 的工具。状态只额外暴露是否有已选配置，不返回订阅凭据。旧独立 Mihomo 内核不再编入宿主。

## 首次创建 Flutter 模块与路径脱敏

在全新检出中，先用 Flutter 3.47.6 创建临时模块，复制其生成的 `.android` 到项目的 `.tmp/flclash-module/.android`，模块名称使用 `flclash`，Android 包名使用 `dev.deepseek.mobile.flclash`，再运行上面的构建脚本。不要把生成目录或个人 SDK 配置提交到仓库。

DSHA 的 Go 内核绑定任务启用 `-trimpath`。Windows 上 Rust 构建钩子会将当前用户目录映射为 `/dsha/user`；公开发行前还应扫描最终 APK 中的二进制、检查签名和对应源码，而不是仅检查 Kotlin 源码。
