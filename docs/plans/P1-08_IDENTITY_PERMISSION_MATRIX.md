# P1-08 方法 / 参数 / 物理调用 / Guest 回包对照

| 入口与方法 | Guest 输入/目标 | 物理系统边界 | Guest 可见结果（合同） |
|---|---|---|---|
| `Context.checkSelfPermission` / `checkPermission` | permission；pid/uid 仅为 Context 合同参数 | 不查询/不写 Host PMS；`GuestCapabilityGate` 读当前 session 的 effective permission | 当前 virtual user 的 `PERMISSION_GRANTED(0)` 或 `DENIED(-1)` |
| `IPackageManager.checkPermission(permission, package)` | permission 与 target package 分开解析 | `PackageManagerInvocationHandler` 在 Host PMS 前以 universe/policy 投影 | 当前 Guest/可见 peer 的 per-user 权限；Host package 始终 `DENIED` |
| `AppOpsManager.check/note/start/finish/noteProxy/checkPackage` | Guest package + virtual UID；API31+ 可含 AttributionSource | `SystemServiceInvocationHandler` 本地处理 Guest target，拒绝 Host package/UID identity-smuggling；不改 Host AppOps 表 | `ALLOWED=0`、`IGNORED=1`、`ERRORED=2`、`DEFAULT=3`；Host package 抛结构化 `SecurityException` |
| `Context.getAttributionSource` | 当前 Guest package、virtual UID | API31+ `GuestAttributionSourceBridge` 构造 Guest projection | `(guest package, virtual UID)`；非虚拟 Provider transport 仍使用 Host resolver，不能混称为 Guest identity |
| Guest `ContentResolver.call` 到 Guest Provider | guest authority、method、callback AttributionSource | `GuestComponentRuntime` 临时安装 caller AttributionSource 到 Provider，并 finally restore | callback `callingPackage`、`callingAttributionPackage`、`callingAttributionUid` 都为 Guest projection |
| `FrameworkIdentityInvocationHandler` 保护系统服务 | 精确 `MethodIdentityPolicy` 定义的 package/UID/Attribution 参数 | 改写后调用实际 Binder；Binder callback/return 再受边界包装 | 有 policy 的回包按 guest identity 投影；未知受保护签名是 `IdentityRewriteException`，不是 empty/success |
| `FrameworkHookReport` + runtime status | service 安装结果、失败原因 | session 启动前 mandatory hooks gate；status 附 `frameworkHooksInstalled/Failed` | 注册、注入、实际 fixture 调用三项分别留证；任一关键 failure 不可 PASS |

## 验收样本矩阵

| 虚拟用户 | CAMERA permission | `android:camera` AppOp | Probe 预期 |
|---:|---|---|---|
| 0 | policy 为 GRANTED，但 Host 未获 CAMERA | policy 为 ALLOWED，effective 为 IGNORED | CAMERA -1、四个 AppOps 操作均 1；INTERNET 0；Guest PMS/Attribution/Provider callback 成功。证明虚拟策略不能升级 Host capability |
| 1 | DENIED | IGNORED | CAMERA -1、INTERNET -1、四个 AppOps 操作均 1；Host 包被拒绝；Guest PMS/Attribution/Provider callback 仍按 user 1 identity 投影，virtual UID 必须不同于 user 0 |

## 执行证据（2026-09-08）

| Lane | 结果 | 证据 | 结论 |
|---|---|---|---|
| API36 / `emulator-5554` | PASS | `out/verification/p1-08-api36-final-contract-v2-20260908/run.json` | user 0/1 的 virtual UID 为 10000/110000；精确 `:provider` 预热、PMS、AppOps、Attribution 与 callback 全部满足合同。 |
| API35 / `emulator-5556` | PASS | `out/verification/p1-08-api35-final-contract-v8-20260908/run.json` | 重启 AVD 后预检、双用户策略、精确 `:provider`、PMS/AppOps/Attribution/callback 全部通过；最终 SHA 与 API33 一致。此前 v6 的冷启动失败保留为环境诊断证据。 |
| API33 / `emulator-5560` | PASS | `out/verification/p1-08-api33-final-contract-v1-20260908/run.json` | user 0/1 的 virtual UID 为 10000/110000，策略、精确 `:provider`、PMS/AppOps/Attribution/callback 全部通过。 |

当前状态：**已完成（API33/35/36 PASS）。** 大规模回归与 8 小时稳定性测试未启动。
