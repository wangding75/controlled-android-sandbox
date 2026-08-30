# C4-R05 readiness terminal-publication race: first failure and bounded fix

日期：2026-08-31（Asia/Shanghai）  
任务：C4-R05  formal two-round acceptance  
责任域：CAS 通用 Activity launch/readiness coordinator

## 1. 结论边界

本文件记录 C4-R05 第二轮 retained-state/hot/recovery 续接 lane 的首个真实
失败、对应原始证据和最小源码修复。它不把该 lane 写成正式通过，也不清除历史
KI；完整两轮 R05 仍必须在包含修复的同一个 clean commit 上重新执行。

## 2. 首次失败签名

失败发生在 `dingtalk / com.alibaba.android.rimet / user0 / hot-001`：

- request ID：`b4a8ef989fd6459ba15db59aab8a4e5b`
- operation ID：`b4a8ef989fd6459ba15db59aab8a4e5b-launch`
- runner operation：`c4-r03-dingtalk-u0-hot-1-a2-b4a8ef989f`
- attempt：2（独立的 post-restart continuation observation），`retryBudget=0`，
  `automaticRetryPerformed=false`，`retryable=false`
- command error：`java.lang.IllegalStateException / LAUNCH_OBSERVATION_NOT_FOUND`
- 时间：`2026-08-31 07:37:15.093 +08:00` 至 `07:37:36.322 +08:00`
- 设备快照：MuMu `RD测试` 动态解析到 API 32 / `22041211A`，boot ID
  `bd6fc459-0d52-4689-868a-420364ea407c`
- 失败时独立设备证据仍为 `windows_empty=false`、Guest drawn、非空 Surface，
  1080x1920 截图 SHA-256
  `9d4d6412ac09b08702bddb099ab27d74f5f1e65cf9526ebe051430ed41417c5c`，
  `nonBlackFraction=0.963659`；这些事实不能替代 request-scoped terminal result，
  因此该轮按 fail-closed 处理。

原始 case contract、完整 probes、截图、事务/catalog/revision 和设备证据位于：

`verification/catch-up/C4-R05/formal-two-round-20260830-receiver-fix/round-2-retained-hot-recovery/launch-matrix/attempt-002/attempts/dingtalk/user-0/hot-001/`

## 3. 时间线和分类

原始 logcat 的相关顺序是：

1. 外层 request 完成 `OBSERVATION_SHAPE`，随后立即记录
   `OBSERVATION_LOOKUP ... found=false`；
2. nested `PrivacyPolicyActivity` 成功关联到父 request；
3. Guest 在 `07:36:35.704` 发出带 Window attached/registered 的
   `FIRST_FRAME_DRAWN`；
4. CAS 在 `07:36:35.706` 已评估外层 `GUEST_LAUNCH_READINESS status=LAUNCH_PASS`；
5. Debug command 在 `07:36:35.707` 读不到 terminal result，报
   `LAUNCH_OBSERVATION_NOT_FOUND`。

责任分类为 CAS 通用 readiness terminal-result publication race。现有窗口、Surface、
Guest Activity 和截图证据说明这不是本次失败的黑屏、SX adapter、商业包显示伪通过
或 RD 设备丢失；`operation` 字段为空是因为 debug command 在读取 terminal result
前已失败，不能据此猜测 Guest 没有完成首帧。

首次失败后未重试该 case。完整失败证据包括 `logcat.txt`、Host/Guest Activity 与
process dumps、Window/Surface/ViewRoot 相关 dump、screenshot、package/revision、
transaction/staging/catalog、APK/commit/boot 快照。

## 4. VA/NBB 对照与采用决定

参考实现和边界：

- NBB `Bcore/.../ActivityStack.java` 的 `startActivityProcess` 创建
  `ProxyActivityRecord`，启动进程并经过 Host stub；`onActivityCreated` 在同步边界
  提交 task/activity record，状态由生命周期回调收敛。
- NBB `BProcessManagerService.java` 以 `(buid/user, processName)` 持有进程 owner
  map，并在 attach/death 时维护 lease 和清理。
- NBB `fake/service/IWindowSessionProxy.java` 只在 WindowSession 边界投影 Host
  package 后委托 framework add/relayout。
- VA `VirtualApp/.../server/am/ActivityStack.java` 用 `StubActivityRecord`
  保存原始 Intent，并由 `onActivityCreated`/`onActivityResumed` 更新同步 task history；
  `HCallbackStub.java` 在 ActivityThread callback 处恢复 Guest intent/activityInfo。

CAS 当前对应边界是 `RuntimeActivityLaunchCoordinator` 的
`GuestLaunchObservation`、`GuestLaunchGate` 和 `publishLaunchReadiness`。采纳的最小
合同是：先构造 request/session/operation-scoped terminal bundle 并原子发布到
readiness result map，再移除 in-flight observation aliases；这样 poller 不会看到
“两者都不存在”的空窗。未采纳：任何 launch retry、固定 sleep、deadline 扩大、捕获
异常循环 addView、静态 Activity marker、Guest 进程存在替代 FIRST_FRAME_DRAWN，或
针对 DingTalk 的包名旁路。

## 5. 实现和回归

实现提交：`d80c9e1538ed60152094d6c4ed4b7bc66d01f1ce`。

`scheduleReadinessObservation` 现在按以下顺序执行：

1. `observation.close()` 并评估 `GuestLaunchGate`；
2. 填充 request/operation、Activity、Window、首帧、fatal/ANR 和阶段字段；
3. 调用 `owner.publishLaunchReadiness(activityToken, details)`；
4. 仅在发布完成后调用 `removeObservationMappings(observation)`。

静态回归脚本 `scripts/check-c4-r03-readiness-publication.py` 验证该顺序只出现一次，
并与 C4-R04 fail-closed、C4-R05 orchestrator 检查共同通过。Gradle debug modules
构建、`python tools/static_android_compile.py` 和 `git diff --check` 均通过。

在同一 MuMu `RD测试` 上的定向 DingTalk user0 cold→hot 2 行回归为 PASS，两个 case
均具备相关 `LAUNCH_PASS`、`FIRST_FRAME_DRAWN`、Window/Surface/non-black evidence，
attempt=1、retryBudget=0、无自动重试；摘要位于：

`verification/catch-up/C4-R05/readiness-publication-fix-20260831/c4-r03-summary.json`

## 6. 后续门禁

`KI-R03-063` 在完整 R05 两轮 clean-commit regression 之前保持 `RECORDED` 和
`blocks_current_campaign: true`。只有完整 R05 的两轮、商业样本矩阵、C1/C2/C4/SX
回归以及两个用户各 15 分钟且至少 50 周期短测全部通过后，才能将该 KI 关闭并决定
C4 是否 DONE。
