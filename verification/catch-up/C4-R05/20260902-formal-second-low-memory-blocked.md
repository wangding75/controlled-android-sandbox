# C4-R05：12 小时 formal lane 第二次 Host LOW_MEMORY 原始证据与策略改判（2026-09-02）

## 1. 原始现场与当前边界

- 任务：`C4-R05`
- 原始现场状态：`LOW_MEMORY` 环境事件，已保存首失败证据
- 当前任务状态：`IN_PROGRESS`；按宿主机性能决策继续矩阵
- C4 阶段：`IN_PROGRESS`；本事件不再阻断 C4
- 12 小时基线启动：`2026-09-02 02:29:06 +08:00`
- `continue-existing-output` 外层进程启动：`2026-09-02 06:43:59 +08:00`
- 当前 round-2 launch child 启动：`2026-09-02 07:38:28 +08:00`
- 原始失败 case：`2026-09-02 09:25:28.868 +08:00` 至 `09:27:07.963 +08:00`
- 首次失败快照完成：`2026-09-02 09:27:09.033 +08:00`
- 外层在保存证据后曾停止；这是旧的“单次 LOW_MEMORY 恢复预算”策略结果，不是当前任务
  的最终验收结论。按用户明确决策，现改为记录每次 Host `LOW_MEMORY` 并动态恢复续接，
  从本失败坐标继续矩阵；未改写本次原始失败。

这是本条 12 小时 formal lane 中继 round-1 Fanqie/user1/hot-005 的一次 Host
`LOW_MEMORY` 动态恢复之后，round-2 出现的第二次同类 Host 进程终止。它不是 12 小时
phase envelope 到期，也不是用户会话中断。原编排器当时按“只允许一次恢复”停止；该
策略现已按宿主机性能限制改为：Host `LOW_MEMORY` 只记录、不按次数阻断，动态恢复后
继续精确坐标。

## 2. 开始基线、环境与产物

- 开始基线：`0b78ea9fd4fbfafe2ff1608a9f56466b3b3d0b0d`
- 分支：`feature/t57-r03-va-pro-capability-campaign`
- 远端：`origin/feature/t57-r03-va-pro-capability-campaign`
- Git 身份：`OpenAI <openai@users.noreply.github.com>`
- 启动前工作区干净；本次 failure 后已终止所有 formal runner 进程。
- MuMu 实例：动态解析 `RD测试`，index `1`，API `32`，型号 `22041211A`，ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`，Android ID
  `398eea33120cd887`，本轮 boot ID
  `3e612f4b-d8e0-4333-b5e1-71697f5944b7`。resolved serial 只在设备快照中记录，
  未写入源码或 runner。
- 本轮构建产物：host APK `8a65e3670bd97f85aa86536b1c7ca7a376578efe085dabf35abbfca589ba4c14`；
  companion32 `c064e04f1e90810b7fdc9f71e8d8f9478ec37b9285a9d40f739610a7fbd0c7a7`；
  fixture `8d8f4d776b287ea947358690882a33763c3967578952cc88e57d5188ca8275a5`；
  fixture32 `9193694ee36848992e98d7e1ff7197a833182807784eb5a375f0d32bff5c96e1`。
- 被测夸克动态包：`com.quark.browser`，version `10.10.5.1080` / code `1080`，
  base revision/APK SHA-256 `2cb38172da5da4aee03826da0feccb77ff0391ee7356f1562052ddc1fae9ecb3`，
  primary ABI `arm64-v8a`；未对商业包写死分支。

## 3. 首次失败 case 与原始证据

原始失败坐标为夸克 `user0 / cold-009`；当前续接从该坐标开始：

- request ID：`e4f0057e1f6c400b9796eddfd76ee870`
- operation ID：`e4f0057e1f6c400b9796eddfd76ee870-launch`
- runner operation ID：`c4-r03-quark-u0-cold-9-e4f0057e1f`
- attempt：`1`；retry budget：`0`；`automaticRetryPerformed=false`；`retryable=false`
- case elapsed：`99,095 ms`
- command：`ERROR` / return code `1`
- error：`RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout; last=`
- readiness result：缺失（`readinessElapsedMs=null`），不能把动态现场观察改写为
  `LAUNCH_PASS`。

完整原始 bundle（已保留 request、日志、进程、Activity、Window、Surface、截图、
package、设备和 transaction 快照）位于：

`verification/catch-up/C4-R05/formal-two-round-20260902-timeout12h-v1/round-2-retained-hot-recovery/launch-matrix/attempt-001/attempts/quark/user-0/cold-009/`

关键文件包括 `case.json`、`first-failure-full/application-exit-info.txt`、
`first-failure-full/logcat.txt`、`activity-activities.txt`、`activity-processes.txt`、
`window-windows.txt`、`surface-list.txt`、`surface-dump.txt`、`processes.txt`、
`host-package.txt`、`target-package.txt`、`device-getprop.txt`、`adb-devices.txt`、
截图及 `tx/` 下的 catalog、lifecycle、checkpoint、virtual UID 和空的
`debug-command-result` 文件。

首次失败快照中的硬证据：

- Host `com.warden.controlledsandbox.debug` PID `24780` 在
  `09:26:06.458 +08:00` 的 `ApplicationExitInfo` 为 reason `3 (LOW_MEMORY)`，PSS
  `117MB`、RSS `184MB`。
- 同一时间 `logcat` 记录 `ActivityManager: Process com.warden.controlledsandbox.debug
  (pid 24780) has died: prcp TOP`、`WindowManager: WIN DEATH`，随后 Host bootstrap
  service 被调度重启；`com.mumu.acc` 亦在 `09:26:07.648` 退出并被调度重启。
- `debug-command-result.json` 采集返回 code `1` 且为 `0` bytes；这解释了内部结果
  采集超时，但不是 phase timeout。
- 现场 Guest/Window/Screenshot 仍有观察值：Guest stub `drawn=true`、windows 非空、
  Surface 非空，截图 `1080x1920`、SHA-256
  `d3b4d15b3741d6bbaee8a57fc8a802c1a938cfdd38f8c34afa4074b47ae5dc7b`、
  `nonBlackFraction=0.994174`、`uniform=false`。这些只能证明现场曾有可见观察，不能
  替代缺失的 request-scoped terminal result。
- 当前快照没有 FATAL marker；`logcat-critical` 未给出 FATAL/ANR 证据。package
  lifecycle 显示已安装包为 `ACTIVE`，catalog 与 lastgood catalog 均存在；没有证据
  表明本次失败形成新的 staging、半发布 revision 或孤儿实例。

## 4. 时间线、deadline 与重试记录

1. `09:25:27.332`：cold-stop 的预期 force-stop 完成；随后新的 Host/Guest 启动链路建立。
2. `09:25:28.868`：本 case 发出 request/operation，attempt=1。
3. `09:26:05.295`：Host 发起目标 Stub Activity；`09:26:06.456/458` Host 进程死亡并被
   Android 标为 `LOW_MEMORY`。
4. `09:27:07.963`：case 以内部 `debug-command-result` 采集超时失败；case 总耗时
   `99,095 ms`，不是 `43,200 s` phase envelope。
5. `09:27:09.033`：first-failure-full 快照完成；随后停止外层 runner。

当前 phase timeout 配置为 `43,200 s`（12 小时），child 也收到
`--child-timeout-seconds 43200`。child 从 `07:38:28` 到失败约运行 `1h47m`，失败时
仍有约 `10h13m` phase budget；扩大 phase timeout 不能使已死亡的 Host 产生结果文件。

本 lane 的重试记录：round-1 Fanqie/user1/hot-005 和本 round-2 Quark/user0/cold-009
均保留 Host-scoped `LOW_MEMORY` 首失败证据；每次恢复都通过动态 MuMu 重启和独立
continuation 处理。两次 case 均为 attempt=1、retry budget=0、无隐藏的 case 自动重试。
保存证据后的 Ctrl+C 是旧策略停止；策略改判后从 Quark/user0/cold-009 精确坐标继续。
没有 fixed sleep、吞异常、扩大 cold/hot SLO，亦没有用晚到首帧或 Guest 进程存在覆盖失败。

## 5. 根因分类与 VA/NBB 对照

- 当前最强根因分类：`ENVIRONMENT_BOUNDARY_EVENT`，具体为 MuMu/RD Host process-owner 的
  memory-pressure/LOW_MEMORY 终止，导致 CAS 的结果采集边界失去 Host owner；不是
  Quark SDK、SX adapter/UI 或已证实的黑屏根因。
- 证据确认了 Host owner death 和缺失 terminal result，但没有证据把唯一内存来源
  归因到某一个 CAS 方法或 Quark 业务线程；更细的内存占用因果关系保持“待验证”。
- VA 对照：`VActivityManagerService.startProcessIfNeedLocked/processDead`、
  `ActivityStack.startActivityProcess/processDied`、`VirtualRuntime.crash`，以真实
  process owner/death/rebind 为边界。
- NBB 对照：`BProcessManagerService.startProcessLocked`、
  `ActivityStack.startActivityProcess`、`BActivityThread.bindApplication`，同样不把
  Activity marker 或 Guest 进程存在当作启动完成。
- CAS 对照：`RuntimeGuestLifecycleCoordinator` 和
  `GuestRecoveryPrewarmCoordinator` 已有 generation/owner 约束；本次没有形成可安全
  采纳的新源码修复，也没有理由增加包名分支。后续恢复条件是稳定/校正 RD Host 内存
  环境后，以新 clean commit 重新完成完整 R05，不是再次盲目重启。

## 6. 验收结果、偏离与遗留风险

- build clean commit：`PASS`，build timeout 仍为独立 `3,600 s`。
- 两轮 R04 failure-injection/recovery：`PASS`，phase timeout `43,200 s`。
- round-1 R02 reduced add gate：`PASS`；launch matrix 聚合为 500/500 terminal
  coordinate，另保留 1 条原始 Host LOW_MEMORY 首失败观察和一次有界恢复。
- round-2 R02 reduced add gate：`PASS`，137/137 operations；round-2 launch 在
  `216` 条通过观察后于上述 Quark case fail-closed，期望 `500`，Hongguo/Fanqie
  尚未进入启动矩阵。
- round-2 商业样本到阻断点：fixture `100/100`，DingTalk `100/100`，Quark 已有
  `16` 条通过后 `cold-009` 失败；Hongguo、Fanqie 未执行。本结果不能宣称商业矩阵
  通过，亦不能用夸克替代红果/番茄小说。
- C1 Activity、C2 Window/Audio、C4 CAS-only、SX F1-F5 回归和 user0/user1 各 15
  分钟且至少 50 周期短测：在旧策略停止点尚未执行，待当前矩阵完成后继续。
- 策略切换边界：旧策略曾按单次恢复预算停止；按当前宿主机性能决策已改为记录
  `LOW_MEMORY` 并继续。没有把 round-2 部分结果或 round-1 的环境恢复写成
  C4-R05/C4 `DONE`。
- 遗留风险：Host memory-pressure/process-owner 边界、MuMu `com.mumu.acc` 联动退出、
  结果采集在 Host death 后的终态回收，以及新 boot 后完整两轮的稳定性，均未关闭。

## 7. Known Issues、提交与恢复条件

- 新增 `KI-R03-069`：状态 `RECORDED`、`acceptance: NOT_FIXED`、
  `blocks_current_campaign: false`；它记录宿主机性能导致的 Host `LOW_MEMORY`，不再按
  事件次数阻断本次矩阵。既有 `KI-R03-053/054/057/058/059/061/062/063/064/065/066/067/068`
  和 `KI-R03-060` 不擅自关闭。
- 本次策略实现提交为
  `cd7cdf3dc0fafc8a4fafbe67db1aacaf77d465fe`；被测 timeout 实现提交为
  `14a6f38bf6fa1132998227f2bb34cf813071cf35`，本轮原始运行/预检基线为
  `0b78ea9fd4fbfafe2ff1608a9f56466b3b3d0b0d`。
- 证据、Known Issue 和进度回执分别固化在本文件、`docs/review/KNOWN_ISSUES.yaml`、
  `docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md`；提交 SHA 与远端核验结果
  在最终进度提交后回填。
- 当前恢复条件：保留本次原始 case，以新的非阻断策略从 Quark/user0/cold-009 精确
  坐标继续；每次 Host `LOW_MEMORY` 只新增事件记录和动态恢复证据。普通非
  `LOW_MEMORY` 失败、恢复失败、坐标缺失或 phase deadline 到期仍然停止。
- 下一任务为当前 `C4-R05` 矩阵续接（`IN_PROGRESS`）；`C5-T01..T04` 继续
  `NOT_APPLICABLE`，在完整验收前不前移 `C6-T01`。

## 8. 策略切换后的续接预检

- `2026-09-02 09:49:30 +08:00` 的续接预检第一次失败，错误为动态 ADB connection
  `error: closed`；原始记录见
  `verification/catch-up/C4-R05/continuation-preflight-failure-20260902-094930.md`。
- 保存证据后通过动态 `RD测试` 实例执行 MuMu restart，manager returncode=0；boot 从
  `3e612f4b-d8e0-4333-b5e1-71697f5944b7` 变为
  `754f6e00-da46-426d-857e-4bce363cad10`，restart 证据见
  `verification/catch-up/C4-R05/continuation-preflight-recovery-20260902-094930/restart.json`。
- 恢复后续接预检 PASS，机器可继续执行；当前矩阵从 Quark `user0/cold-009` 精确坐标
  续接。预检输出见
  `verification/catch-up/C4-R05/continuation-preflight-nonblocking-20260902.json`。

本次策略/证据/进度提交均已推送：策略实现提交为
`cd7cdf3dc0fafc8a4fafbe67db1aacaf77d465fe`，证据与进度提交为
`f342503d928e95abba2defd259e80695c4c11618`；当前报告的补充更新将在续接启动前单独
提交并完成远端核验。
