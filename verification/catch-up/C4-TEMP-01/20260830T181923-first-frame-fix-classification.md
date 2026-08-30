# C4-TEMP-01 2026-08-30 首帧修复分类

## 结论

本次首失败的 CAS 共性根因已定位并修复：Guest 后台线程登记动态 Receiver 时，旧实现把 Host
`registerReceiver` 同步切回 Guest 主线程；Quark `BrowserActivity.onCreate` 同时等待该后台
初始化，形成约 15 秒 `GuestMainThreadDispatcher` 超时和后续首帧门禁失败。修复后不再执行该
主线程 hop，回调仍由 Android `Handler` 投递到 Guest scheduler。

## 责任分类

| 边界 | 结论 | 证据 |
|---|---|---|
| CAS 通用 | 已确认并修复动态 Receiver 的线程/owner 锁反转 | `GuestDynamicReceiverTransport.java`；前序 raw lane 的 `GUEST_RECEIVER_REGISTER`→`UNREGISTER` 约 15,032 ms |
| SX adapter/UI | 未发现证据 | package-neutral fixture 与 CAS Window/Surface 合同均通过 |
| App/SDK | Quark 自身剩余启动成本仍存在，但不再阻断本 TEMP 3+3 硬门槛 | 直启/CAS 对照与 child `BrowserActivity` timeline |
| RD 环境 | 本轮未把设备低内存信号当作本修复根因 | environment snapshot/boot ID 保留在 raw lane |
| 验收编排 | fail-closed、零自动重试和真实首帧门槛保持不变 | 每个 case 的 `attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false` |

## 参考边界

采纳 Android `Context.registerReceiver(..., Handler, ...)` 的调用线程与回调 Handler 分离合同，
以及 VA/NBB 的 Broker receiver registry 与 framework dispatcher 分层；不采纳扩大 timeout、静默
retry、包名分支、绕过 Broker registry 或把 Guest callback 直接交给 Host 主线程。

## 结果

机器可读矩阵见 [20260830T181923-benchmark-summary.json](20260830T181923-benchmark-summary.json)。
原始 request/operation、case、截图和动态环境见
`quark-receiver-thread-20260830/20260830T181923/`；package-neutral 对照见
`fixture-receiver-thread-20260830/`。Quark 直启 3/3、CAS 3/3 均真实
`FIRST_FRAME_DRAWN`，最大 `sandbox/direct=8.3638`，通过 10x 硬门槛；3x 目标仍未达成，
作为性能残余风险交给 C4-R05 全量验收。

历史 `KI-R03-062` 首失败不覆盖；该问题的具体锁反转签名在本次 lane 不再复现，正式 KI 状态
留待 R05 完整回归决定。
