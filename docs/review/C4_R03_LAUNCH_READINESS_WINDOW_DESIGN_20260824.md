# C4-R03 启动 readiness、窗口合同与 NBB/VA 参考设计

日期：2026-08-24<br>
任务：`C4-R03`<br>
设备：MuMu `RD测试`（本轮由实例名动态解析；观测 endpoint 仅写入 evidence）<br>
基线：`3b042808eb0c1d63bfc5fea30e27e6e3725b3f16`

## 1. 结论与范围

C4-R03 已完成首帧阶段合同、request/operation ID、单次 fallback 约束和首次失败快照采集，
但规定的商业样本启动门禁未满足，因此本任务状态为 `BLOCKED`。本轮没有进入 C4-R04、C4-R05、
C6 或 OEM 适配。

失败不是通过重复启动、延长生产 deadline 或扩大 retry budget 得到的：每个启动 operation 均为
`attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`。runner 的等待预算只用于等待生产路径
返回已记录的失败；生产 gate 仍是 cold 30 秒、hot 10 秒。

## 2. R03 变更与已通过的最小证据

本轮实现了以下 R03 范围内的证据和合同变更：

- `REQUEST_ACCEPTED → GUEST_READY → ACTIVITY_RESUMED → FIRST_FRAME_DRAWN` 时间线及 request/operation ID；
- `GuestLaunchGate` 只有首帧、窗口、Surface 和非黑截图证据齐全时才允许通过；
- Activity framework callback 显式确认 `GUEST_READY`、`FIRST_FRAME_DRAWN`；
- activity event 按 activity token 过滤；
- `ensureWindowPublishedAfterResume` 只保留一次明确的 post-resume 观察/修复，不循环 post、sleep 或 addView；
- 首次失败立即保存 logcat、Activity/Window/Surface、进程、事务、设备属性和截图。

fixture 双用户各一轮 cold/hot 通过，且每条均有首帧、非空 Window/Surface 和非黑截图；番茄小说 user0
一轮 cold/hot 也通过（cold 29400 ms、hot 4829 ms）。这些是最小正向证据，不等价于任务书要求的
5 个 target × 2 users × 50 cold/hot 轮次。

## 3. 商业样本动态记录

样本由 `pm list packages`、`pm path`、`aapt2 dump badging` 和 `dumpsys package` 动态解析，执行器没有
硬编码 ADB 地址或商业 package。完整记录在各 raw evidence 的 `commercial-sample-discovery.json` 和
`targets.json`。

| 样本 | package | 版本 | base/split | ABI/native | 角色 |
|---|---|---|---|---|---|
| 夸克 | `com.quark.browser` | 10.10.5.1080 / 1080 | 1 / 0 | primary `arm64-v8a`；native `arm64-v8a` | 仅正向对照 |
| 红果免费短剧 | `com.phoenix.read` | 7.0.5.33 / 70533 | 1 / 0 | primary `arm64-v8a`；native `arm64-v8a` | 独立兼容性样本 |
| 番茄免费小说 | `com.dragon.read` | 7.1.9.32 / 71932 | 1 / 0 | primary `arm64-v8a`；native `arm64-v8a` | 独立兼容性样本 |
| 钉钉 | `com.alibaba.android.rimet` | 7.8.10 / 1178 | 1 / 0 | primary `arm64-v8a` | 任务书样本 |

夸克只作为正向对照，不用于推断红果或番茄小说的兼容性。红果的动态 badging 结果把 splash
label 映射到 `com.dragon.read.pages.splash.SplashActivity`，该映射本身已记录为 collector 待验证项；
不能在缺少事实时猜测其 owner 或改写成番茄 package。

## 4. 首次失败证据

| target | request ID | 首次错误 | 关键状态 | 原始 evidence |
|---|---|---|---|---|
| DingTalk | `ade67da601b74b9b81c7d6f46c5ce3e3` | `TransactionTooLargeException:data parcel size 270596 bytes` | 68.525 s；无首帧；Surface/截图仍有内容 | `artifacts/capability-audit/catch-up-c4-r03/dingtalk-after-event-fix-20260824T1635/attempts/dingtalk/user-0/cold-001` |
| 夸克 | `67a3de60be574c7bb97cc5480943297d` | `TransactionTooLargeException: data parcel size 283304 bytes` | 54.882 s；`windows_empty=true`、`reportedDrawn=false`、无首帧 | `artifacts/capability-audit/catch-up-c4-r03/quark-after-event-fix-20260824T1700/attempts/quark/user-0/cold-001` |
| 红果 | `b3b25112f8e24922b19bff1f590bc258` | `TransactionTooLargeException: data parcel size 308616 bytes` | 52.826 s；无 `GUEST_READY`、无首帧 | `artifacts/capability-audit/catch-up-c4-r03/hongguo-after-event-fix-20260824T1715/attempts/hongguo/user-0/cold-001` |

夸克的 logcat 首次失败栈进一步确认：`com.ucpro.MainActivity.onCreate` 调用
`Activity.startActivity`，随后进入 `GuestRuntimeBrokerBridge.execute` 的
`IRuntimeBroker.executeV2` Binder 事务；不是 SX adapter/UI 调用失败。红果和钉钉具有相同的
CAS 启动事务大小错误，但其最内层调用栈尚未分别完成同等精度的下钻，故只把共性 owner 写为已确认，
不同 app 的具体 extras/调用者仍标记待验证。

## 5. NBB/VA 对照与 CAS 差异

### 5.1 已查阅的参考实现

- NBB：`BActivityManagerService.startActivity` → `ActivityStack.startActivityLocked` →
  `startActivityProcess`；先建立 `ProxyActivityRecord`、准备 process，再生成 Host Stub shadow Intent
  交给真实 framework。`onActivityCreated` 回报并收敛 launching 状态。
- NBB：`BProcessManagerService`/`BActivityThread` 维护 process slot、attach 和 binder death；
  `ContextCompat`、`IWindowManagerProxy`、`IWindowSessionProxy` 保持 Host package/op-package 和
  WindowSession identity，最后仍由正常 ActivityThread addView/draw。
- VA：`VActivityManagerService.startActivity` → `ActivityStack.startActivityLocked`；
  `startActivityProcess` 保存原始 Intent 到 `StubActivityRecord`，只把小的 Host Stub targetIntent
  交给 framework；`StubActivity` 再恢复 Guest Intent。
- VA：`VActivityManagerService`/`ActivityStack` 以 process attach/death 和 ActivityRecord 为状态权威；
  `HCallbackStub`/`AppInstrumentation` 恢复 Guest Activity；`WindowManagerStub`/
  `WindowSessionPatch` 处理 Host Window identity，首帧由 framework 生命周期产生。

参考源码位置和 hash 已在 `docs/review/C4_R01_EVIDENCE_REPRO_CLASSIFICATION_AND_REFERENCE_MAPPING_20260824.md`
第 7 节固化；本节记录 R03 设计实际采用的事务边界，而不是复制参考实现。

### 5.2 CAS 当前已确认的差异

当前 CAS 路径为：

`Guest Context.startActivity` → `GuestContextComponentRouter.startActivityInternal` →
`GuestRuntimeBrokerBridge` → `RuntimeOperationTransport.request` →
`IRuntimeBroker.executeV2(RuntimeOperationRequest)` → `RuntimeActivityLaunchCoordinator` →
CAS task/route → Host Stub → framework。

`RuntimeIntentWireCodec` 对完整 Intent 设有 256 KiB wire 上限；当 marshal 超限时只丢弃 wire 字节，
但仍把 `intent.getExtras()` 复制到 `RuntimeKeys.INTENT_EXTRAS`。`RuntimeOperationRequest` 又把该 Bundle
作为顶层 Parcelable payload 送入 `IRuntimeBroker.executeV2`。这解释了为什么 Quark 在 CAS import 已成功后，
于 Guest Activity 的二次 `startActivity` 触发 283304 bytes 事务失败：原始 Intent 的大 extras 在 Guest→Broker
边界被重复/直接携带，尚未到 SX/UI 或正常 framework Stub 边界。

### 5.3 参考设计（尚未作为生产修复实施）

应沿用 NBB/VA 的“broker-owned ActivityRecord/route + 小 Host Stub Intent”边界：

1. Guest→Broker 只传有界的 route metadata 和不可猜测的 payload handle；原始 Intent/extras 保留在
   generation/session 绑定的 CAS broker record 中，不静默截断或丢弃。
2. Host framework 只接收小的 Stub projection；Guest Activity 恢复原始 Intent 时通过同一
   request/operation、session、generation 和 activity token 取回，单次消费并校验 owner。
3. oversized payload、handle 缺失、代际不匹配和恢复失败必须返回稳定 typed error；不得通过固定 sleep、
   无限 retry 或扩大 deadline 掩盖错误。
4. 该设计需要明确的跨 Guest/Broker payload store/handle 协议以及生命周期清理；在没有完成协议、
   Parcelable 边界测试和小样本对照前，不实施猜测式生产修复，也不再重复商业启动测试。

## 6. owner、已确认与待验证

### 已确认

- 导入/catalog 门槛已通过后，DingTalk、夸克、红果在启动 readiness 之前失败；番茄 user0 cold/hot
  通过，但不清除其余样本或双用户/50 轮门禁。
- Quark 的 `TransactionTooLargeException` 发生在 CAS `IRuntimeBroker.executeV2`；owner 是 CAS
  通用 Activity/Intent transport，不是 SX/UI。
- 运行器没有自动重试；每次首次失败均保存完整 snapshot。

### 待验证

- 三个失败样本各自的大 extras 来源、是否需要完整 Parcelable 语义，以及 payload handle 的最小协议。
- 红果动态 launchable Activity 的 package/label 映射异常是否为 collector 解析问题。
- CAS payload 边界修复后，5 个 target、2 个 user、cold/hot 各 50 轮的首帧、窗口、Surface、截图和
  重复 Stub/ViewRoot 结果。

因此 R03 当前 owner 保持 CAS 通用启动/Intent transport；没有证据时不转交 SX adapter/UI，也不把
番茄单次 PASS 外推为商业兼容。

## 7. 2026-08-25 续接：进程 owner lease 修复与 8 小时窗口结果

本节记录 2026-08-25 的续接执行结果；前文的 oversized Intent 首次失败仍是历史首次失败证据，
不得被后续部分矩阵 PASS 覆盖。

### 7.1 NBB/VA 进程 owner 合同与 CAS 修复

- NBB 的 `BProcessManagerService.startProcessLocked(packageName, processName, userId, ...)` 以
  `(virtual user, processName)` 维护 `ProcessRecord`，启动/attach 后由 Activity/Service owner 持有，
  death 时移除代际；`ActiveServices` 在 start/bind/stop 前通过同一 process manager 取得 owner。
- VA 的 `VActivityManagerService`/`ActivityStack` 以虚拟 `ProcessRecord`、task/activity history、
  attach/death 和 rebind 关系作为运行时权威；StubActivityRecord 只承载小的 Host route，窗口仍由
  正常 framework ActivityThread/WindowSession 绘制。
- CAS 原先在 `PACKAGE_LOOKUP_BEGIN` 之后才建立 RuntimeClient，长时间运行的 Guest 在 lookup/launch
  窗口内缺少前台 owner edge；MuMu `lowmemorykiller` 曾在设备仍有可用内存时杀死 Guest/Broker，形成
  `GUEST_PROCESS_DISCONNECTED`、DeadObject 和 `LAUNCH_GATE_FAILED`。这已确认为 CAS 通用进程生命周期
  owner，不是 SX/UI 或夸克专属问题。
- 本轮实现了 `BIND_AUTO_CREATE|BIND_IMPORTANT|BIND_ABOVE_CLIENT` 的 Broker/Guest/authority owner
  edges，并在 package lookup 前执行 RuntimeClient owner prime；该边界只改变 Android process importance，
  不重试、不 sleep、不延长生产 deadline。对应实现见
  `RebindableServiceConnector`、`BaseGuestProcessService`、`RuntimeGuestConnectionPool`、
  `RuntimeClient` 和 `DebugCommandActivity`。

### 7.2 续接矩阵与结论

按用户此前把每个 50 轮改为 25 轮的指示，目标矩阵为 5 targets × 2 users × (25 cold + 25 hot) =
500 rows。修复后的最终代码在 8 小时窗口内完成 260 rows，260/260 PASS：夸克双用户 100/100、
DingTalk 双用户 100/100、fixture user0 50/50 和 fixture user1 10/50；红果和番茄小说最终矩阵未开始。
每个已完成 row 均为 `attempt=1/retryBudget=0/automaticRetryPerformed=false/retryable=false`。

fixture user1 的 `cold-006` 在时间上限到达时处于截图质量采集阶段；其 logcat、activity/window、Surface、
截图和 cold-stop 文件已保留，但没有 `case.json`，不计入 PASS。机器可读统计见
`verification/catch-up/C4-R03/rd-acceptance/summary.json` 的 `continuation8h`。

夸克只继续作为正向对照，不能推导红果或番茄兼容；红果/番茄的 owner 与启动结论保持待验证。由于矩阵
未完成、C4-R04/R05 尚未执行，C4-R03 保持 `BLOCKED`，不得更新为 `DONE` 或推进下一任务。

## 8. 证据索引

- R03 start preflight：`verification/catch-up/C4-R03/start-state.json`。
- 机器汇总：`verification/catch-up/C4-R03/rd-acceptance/summary.json`。
- fixture 最小正向矩阵：`artifacts/capability-audit/catch-up-c4-r03/fixture-after-event-fix-20260824T1645`。
- 番茄单次 cold/hot：`artifacts/capability-audit/catch-up-c4-r03/fanqie-after-event-fix-20260824T1730`。
- DingTalk/夸克/红果首次失败：见第 4 节 raw paths；每个目录含 logcat、dumpsys、Surface、进程、
  事务文件、截图和设备快照。
