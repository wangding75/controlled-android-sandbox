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

2026-08-25 的 2 小时续接进一步完成了红果 user0/user1 各 50/50（100/100 PASS），并完成番茄
user0 的 4 个 PASS；番茄 user1 尚未开始，番茄 user0 尚缺 46 个 case。红果的成功只证明当前
CAS 通用 owner/readiness 修复在该动态样本上的已执行范围，不改变夸克正向对照规则，也不替代番茄
剩余矩阵。完整非重复矩阵为 404/500，故 owner/阶段结论继续保持待关闭。

### 7.3 2026-08-25 重启后断点复核

用户要求重启 `RD测试` 后从断点继续。运行器先停止正在执行的番茄 user1 lane，保留其已经生成的
`cold-001`、`hot-001` 和 `cold-002` 首次失败证据；随后通过 MuMu 实例名动态解析 index 并执行
`MuMuManager control --vmindex <resolved-index> restart`。重启前 boot ID 为
`60d44ff7-2d1b-44a3-8cec-7b1f0608b633`，重启后重新解析为
`70f2ef8b-daf7-4492-b011-4a1da57a5c49`；ADB serial 只保存在本次动态解析快照中，未写入 runner 常量。

为避免从头重跑已完成 case，C4-R03 collector 增加了显式的
`MANUAL_RESUME_AFTER_RESTART` 起点元数据。它不改变生产 deadline、不执行自动重试，且把续接观察记录为
`attempt=2/retryBudget=0/automaticRetryPerformed=false`，并通过 `resume.previousLane` 链回首次失败。
续接从番茄 user1 `cold-002` 开始；新 boot 下 readiness 为 `42106 ms`，仍超过 cold `30000 ms` SLO。
Activity created/resumed、`FIRST_FRAME_DRAWN`、Window、Surface 和非黑截图均存在，因此错误不是黑屏或
SX/UI Surface owner；collector 在保存完整 first-failure snapshot 后按 fail-fast 停止，未进入 `hot-002`。

两次 user1 `cold-002` 以及 user0 `cold-001` 的共同调用链都出现
`com.dragon.read` 的 Mira plugin provider 访问进入
`GuestContentProviderFrameworkInterceptor -> GuestRuntimeBrokerBridge`，并出现
`GUEST_MAIN_THREAD_TIMEOUT`，随后才绘制首帧。已确认 owner 保持 CAS 通用 Guest ContentProvider/launch
readiness 边界；具体 app-side plugin/provider 触发点和最小 CAS provider/broker 协议仍待验证，不能猜测为
番茄专属兼容性，也不能转交 SX/UI。该结果使 C4-R03 继续 `BLOCKED`，不得更新为 `DONE` 或推进任务。

机器可读证据见 `verification/catch-up/C4-R03/rd-acceptance/summary.json` 的
`continuationAfterRestart`，raw 目录为：

- `artifacts/capability-audit/catch-up-c4-r03/continuation-final-fanqie-u0-u1-25-20260825`；
- `artifacts/capability-audit/catch-up-c4-r03/continuation-final-fanqie-u1-25-20260825`；
- `artifacts/capability-audit/catch-up-c4-r03/continuation-after-reboot-fanqie-u1-from-cold2-a2-20260825`。

## 8. 修复设计与定向验证（2026-08-25）

### 8.1 根因与参考实现映射

在实施修复前重新对照 NBB/VA 实现：NBB 的进程记录、Binder owner/death 状态和生命周期归属，
以及 VA 的 ActivityStack/进程启动、Provider 生命周期边界，均把“生命周期状态发布”和“执行
回调”分开处理。这里仅借鉴 owner、生命周期和死亡回收的边界原则，不复制旧的全局 singleton 或
私有 API 假设。结合番茄首次失败的完整栈，已确认 CAS 的具体锁环为：

`GuestContentProviderFrameworkInterceptor` 的全局 `synchronized` → `attachInfo()/prepare()` →
Guest 主线程 Broker 调用 → Fanqie Mira plugin provider 再次通过 ContentResolver 进入
ContentProvider 拦截器。全局锁使回入无法取得拦截器，15 秒后才出现 `GUEST_MAIN_THREAD_TIMEOUT`，
随后首帧才被绘制。该 owner 是 CAS 通用 Guest ContentProvider/launch readiness 边界；Mira 是触发
路径证据，不足以把问题猜测为番茄专属，也没有 SX/UI owner 证据。

### 8.2 最小修复边界

`GuestContentProviderFrameworkInterceptor` 现在按 authority 维护短生命周期 single-flight 状态，
只在读取/发布/关闭状态时持有短 `stateLock`。`attachInfo()`、`prepare()`、反射构造和 shutdown
回调都在锁外执行；同一 authority 在 Guest 主线程回入时 fail-closed，避免把主线程变成等待者，
其他线程只等待该 authority 的一次创建结果。关闭先发布 terminal state，再在锁外回调 Provider。
该修复不延长 Guest 主线程 15000 ms timeout、不放宽冷/热 readiness SLO、不增加 retry，也不通过
固定 sleep 或重复运行掩盖首次失败；因此不提前实施 C4-R04/C4-R05 的验收编排。

### 8.3 回归与 RD 测试

静态 Android 编译、自检和 Gradle Debug 构建均通过。以实例名 `RD测试` 动态解析到本次 boot
`70f2ef8b-daf7-4492-b011-4a1da57a5c49`；ADB endpoint 只写入 evidence，不进入 runner 常量。当前
APK `89DCBEB082F9F6452813CF363BB5E5AE17632ACE2031EAE4490D17C2FB6B75A1` 下，番茄
`com.dragon.read` 7.1.9.32/71932（base 1、split 0、arm64-v8a）用户 0/1 冷/热各 1 轮共
4/4 PASS：readiness 分别为 15978、463、16744、626 ms，均有
`REQUEST_ACCEPTED → GUEST_READY → ACTIVITY_RESUMED → FIRST_FRAME_DRAWN`、非空 Window、
Surface 和非黑截图。每行 `attempt=1/retryBudget=0/automaticRetryPerformed=false`，当前 case-scoped
fatal markers 为空；完整 request/operation ID 和快照见：

`verification/catch-up/C4-R03/rd-acceptance/targeted-fix-20260825.json` 及
`artifacts/capability-audit/catch-up-c4-r03/after-provider-interceptor-single-flight-20260825`。

这只是修复后的定向验证，不是 500 行正式矩阵。番茄完整双用户矩阵、C4-R04/C4-R05 仍未完成，
因此 C4-R03 保持 `BLOCKED`，不得更新为 `DONE` 或推进下一任务；历史首次失败证据继续保留并可
用于对比，夸克仍只作正向对照。

## 9. 证据索引

- R03 start preflight：`verification/catch-up/C4-R03/start-state.json`。
- 机器汇总：`verification/catch-up/C4-R03/rd-acceptance/summary.json`。
- fixture 最小正向矩阵：`artifacts/capability-audit/catch-up-c4-r03/fixture-after-event-fix-20260824T1645`。
- 番茄单次 cold/hot：`artifacts/capability-audit/catch-up-c4-r03/fanqie-after-event-fix-20260824T1730`。
- DingTalk/夸克/红果首次失败：见第 4 节 raw paths；每个目录含 logcat、dumpsys、Surface、进程、
  事务文件、截图和设备快照。

## 10. 2026-08-25 DingTalk 优先续接与资源压力首次失败

用户指定本轮先执行 DingTalk，fixture 放到最后。fixture 因此继续保持暂停，没有用 fixture 的正向
结果替代 DingTalk，也没有进入 C4-R04、C4-R05、C6 或 OEM 适配。

### 10.1 动态环境与样本事实

本轮仍通过 MuMu 实例名 `RD测试` 动态解析设备；重启后的 boot ID 为
`7fec8065-1d25-4e25-8c53-f7cb7eb3b26a`，设备为 `22041211A` / API 32，ABI 为
`x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`。ADB serial 只保存在 raw environment evidence，
未进入 runner 选择器。DingTalk 动态记录为 `com.alibaba.android.rimet`、7.8.10/1178、base 1、
split 0、primary ABI `arm64-v8a`，启动 Activity 为
`com.alibaba.android.rimet.biz.LaunchHomeActivity`；完整记录见
`verification/catch-up/C4-R03/dingtalk-priority-20260825.json`。

### 10.2 首次失败与有限续接

第一条 DingTalk lane `fix-dingtalk-u0-u1-a1-20260825` 产生 84 行，其中 83 行满足 readiness
门禁，user1/hot-017 为首次非通过：readiness `11720 ms`，超过 hot `10000 ms`，request ID 为
`33720dd87741423c9042654f7f5a3d99`，operation ID 为
`33720dd87741423c9042654f7f5a3d99-launch`。该行已经保存 logcat、Activity/Window、Surface、
进程、设备属性、事务文件和截图，且 `attempt=1`、`retryBudget=0`、
`automaticRetryPerformed=false`、`retryable=false`。

按重启后的独立观察策略，仅以 `MANUAL_RESUME_AFTER_RESTART`、`attempt=2`、retry budget 0 续接，
没有把旧 hot-017 改写为 PASS。续接 lane 的 cold/hot-017 和 cold/hot-018、cold-019 通过，
但 hot-019 再次首次失败：readiness `10179 ms`（超出 `179 ms`），request ID 为
`417dbc1ae7534d39b82429871b779de6`，operation ID 为
`417dbc1ae7534d39b82429871b779de6-launch`。它同样具有完整首次失败快照；runner 在该行 fail-fast
停止，没有继续剩余 DingTalk 行，也没有启动 fixture。

两次失败都最终观察到 `ACTIVITY_RESUMED`、`FIRST_FRAME_DRAWN`、非空 Window、非空 Surface 和
非黑截图；因此这些证据不是黑屏或 Surface/UI 缺失。hot-019 快照还记录了此前三次 MuMu
`lowmemorykiller` 事件，分别杀死 WebView、Contacts 和 ExternalStorage，均带有
`reason: device is not responding`；没有发现 DingTalk target 或 CAS host 被同类 LMK 直接杀死，
也没有 `GUEST_MAIN_THREAD_TIMEOUT`、`ANR in` 或 `FATAL EXCEPTION`。这确认了设备资源压力信号，
但不能单凭时间相关性证明完整根因。

### 10.3 owner 结论与恢复条件

已确认：导入/catalog 成功后，失败位于 CAS 通用 launch/readiness 观察边界；DingTalk 的
`LaunchHomeActivity -> PrivacyPolicyActivity` 子 Activity 仍通过统一 request/operation 时间线
关联，并最终绘制首帧。已确认的设备 LMK 是环境贡献信号。没有证据把 owner 归给 SX adapter/UI，
也不能把夸克正向对照外推为 DingTalk、红果或番茄小说兼容。

待验证：CAS 进程 owner/launch preparation 延迟与 MuMu responsiveness 哪一个占主导，以及该
Activity transition 是否需要 CAS 通用的有界 readiness 设计。C4-R03 只记录证据、复现、分类和
NBB/VA 参考映射；不通过延长 deadline、增加 sleep、扩大 retry 或猜测 package 特判实施生产修复。

当前结论为 `BLOCKED`：DingTalk 仍有 readiness 首次失败，fixture 按用户顺序留待最后，500 行
正式矩阵未闭合。机器可读回执和 raw evidence：
`verification/catch-up/C4-R03/dingtalk-priority-20260825.json`、
`artifacts/capability-audit/catch-up-c4-r03/fix-dingtalk-u0-u1-a1-20260825`、
`artifacts/capability-audit/catch-up-c4-r03/fix-dingtalk-u1-r17-a2-20260825`。

## 11. 2026-08-25 fixture 最后续接更正

在 DingTalk lane 按首次失败规则停止后，fixture 仍按用户指定的“最后一个 target”执行；此前将
fixture 留在暂停状态是流程判断错误，已通过本节和独立机器证据更正。fixture user0 原有 50 行，
user1 原有 15 行；DingTalk 运行期间使旧 hot 前置失效，因此从 user1 `cold-008/hot-008` 重新
建立有效对照，再继续 iteration 9–25。续接 lane 共 36 行，其中 `cold-008` 是替换观察，新增
唯一行 35 行。

fixture 续接 lane
`artifacts/capability-audit/catch-up-c4-r03/fix-fixture-u1-after-dingtalk-last-a2-20260825`
的 36/36 行通过，`errorClassification=NONE`、`failureDetected=false`；每行有统一
request/operation ID、Activity created/resumed、`FIRST_FRAME_DRAWN`、非空 Window、非空 Surface
和非黑截图，case-scoped fatal/ANR markers 为空。续接外层字段为 `attempt=2`、`retryBudget=0`、
`automaticRetryPerformed=false`、`retryable=false`，没有自动重试。

按唯一 `(user, mode, iteration)` 去重后，fixture 观察为 user0 50/50、user1 50/50，cold 50/50、
hot 50/50，唯一 100 行均通过。由于 user1 的后 35 个唯一行是切换 target 后的人工续接观察，
这证明 fixture 的当前观察矩阵完整，不把它表述为一次未经中断的 100 行 attempt=1 门禁；DingTalk
的历史首次失败仍是 C4-R03 的阻断条件。runner 原始 `resume.json` 使用了通用
`MANUAL_RESUME_AFTER_RESTART` 标签，但本 lane 的 boot ID 与 DingTalk 相同、实际没有设备重启，
因此高层回执明确记录为 `MANUAL_CONTINUATION_AFTER_DINGTALK_TARGET_SWITCH`，不把该标签误解为重启。

独立机器回执为 `verification/catch-up/C4-R03/fixture-priority-last-20260825.json`。C4-R03
仍为 `BLOCKED`，不推进 C4-R04；fixture PASS 不能覆盖 DingTalk readiness 首失败，也不能关闭
商业矩阵。

## 12. 2026-08-25 DingTalk 有界复现结果

按用户要求，在 fixture 最后续接完成后，对 DingTalk 建立一条有界人工复现 lane；它不是自动重试，
也不把未复现结果直接改成 PASS。当前 boot ID 未改变，`attempt=3`、`retryBudget=0`、
`automaticRetryPerformed=false`、`retryable=false`。

该 lane 覆盖了两个历史失败点：`hot-017` readiness `8546 ms`、`hot-019` readiness `8515 ms`，
均通过；同时 `cold/hot-017`、`cold/hot-018`、`cold/hot-019`、`cold/hot-020` 共 8 行均有首帧、
Window、Surface、非黑截图且无 FATAL/ANR。runner 在下一个无关诊断行开始前停止，原始 lane 和
request/operation ID 保留在：
`artifacts/capability-audit/catch-up-c4-r03/dingtalk-repro-after-fixture-a3-20260825`。

通过复现并不能清除历史首次失败。更重要的是，本次通过的 `hot-017` 和 `hot-019` logcat 仍各自
出现了与目标无关的 WebView LMK `reason: device is not responding`，说明“有 LMK 就一定失败”也
不成立。当时的证据结论只能是：两个历史失败在同一 boot 的有界复现中未复现，故问题具有非确定性，
但 CAS launch/readiness 与 MuMu responsiveness 的主导因果仍未分离；不能把问题标记为已修复或关闭
`KI-R03-060`。机器回执为 `verification/catch-up/C4-R03/dingtalk-repro-after-fixture-20260825.json`。
该段的 `BLOCKED` 是复现完成时的历史状态；后续用户批准的行政关账和风险豁免见第 13 节。

## 13. 2026-08-25 用户批准的条件性关账与回归安排

用户明确要求：保留 DingTalk readiness 问题为 Issue，接受当前残余风险，将 C4-R03 记录为 `DONE` 并
推进下一任务，后续把该 Issue 纳入回归。账本据此采用“行政 DONE、风险豁免、正式矩阵门禁延期”的记录方式：
历史 `hot-017`/`hot-019` 首次失败、8 条有界复现通过和 10 条未执行坐标均保持原样，未被改写为 500/500 PASS。

因此 `KI-R03-060` 仍为开放 Issue，owner 仍待 CAS process/prepare 与 MuMu responsiveness 的因果分离；
本节只记录用户批准的推进决策，不构成生产修复或 C4 阶段关闭。C4-R04/R05 必须将该 Issue 作为回归门禁，
并在最终关门前重新验证失败坐标及完整矩阵。
