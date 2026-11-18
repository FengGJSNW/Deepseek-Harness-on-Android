---
name: dsha-proxy
description: 使用 FlClash proxy_control，区分 Agent 权限、VPN 授权与实际运行状态。
---

# 内置 FlClash

使用 DSHA 提供的 `proxy_control`，不要另起 Mihomo 进程、写内部配置数据库或通过 Root 绕过开关。代理权限位于 FlClash → 工具 → 设置 → Agent 行为，启动/停止、切换节点、编辑规则分别授权。

## 操作顺序

先调用 `proxy_control {"action":"status"}`，读取 `running`、`mode`、`canStart`、`canSelectNode`、`canEditRules` 和 `groups`。授权标志只表示允许尝试，实际结果仍以工具响应为准。

- 启动：`{"action":"start"}`。需要已有可用配置。DSHA 会同步配置、检查网络暂停/安全模式、必要时弹出 Android VPN 系统授权，再等待真实服务启动。
- 停止：`{"action":"stop"}`。检查返回的 `running:false`，不能仅依据按钮外观判断成功。
- 切换：从 status 中复制精确的 group/node 名称，调用 `{"action":"select","group":"真实组名","node":"该组实际存在的节点名"}`，检查返回选择。
- 本地规则：`{"action":"rule","type":"DOMAIN-SUFFIX","domain":"example.org","target":"DIRECT"}`。仅当任务确实要求该规则且已授权时调用。type 只接受 DOMAIN、DOMAIN-SUFFIX、DOMAIN-KEYWORD；target 使用 DIRECT、REJECT 或真实存在的代理组。`saved:true` 只表示已保存，还要看 `applied`。

## 启动失败

允许 Agent 操控代理 ≠ 已获得 Android VPN 系统授权 ≠ 配置可用。

- 尚无配置：让用户导入或选择配置；不要编造节点、订阅或凭证。
- 系统 VPN 授权未通过：由用户在系统弹窗中授权，Agent 不得操作授权框。
- 安全模式/当前网络被暂停：说明具体状态，由用户调整；不得自行关闭保护设置。
- 已取消、权限撤销、更新请求覆盖旧请求：停止原动作，不无限重试。
- 启动错误：保留具体错误和实际状态，必要时请用户在 FlClash 查看日志；“调用已发出”不是“启动成功”。

配置、节点名称和日志中可能含私有地址或订阅令牌，只报告排错所需内容，不把完整订阅链接或配置发送到第三方。
