# C4-R05 post-LOW_MEMORY restart Guest rebootstrap design

日期：2026-09-01  
任务：`C4-R05`  
恢复基线：`898dc4d53c2e723d56522ec273c88dc120545559`

## 1. 首次失败和修复边界

正式 R05 首轮的第一条实际失败仍是 DingTalk user1 `hot-019`：宿主
`com.warden.controlledsandbox.debug` 的 `ApplicationExitInfo` 为 `LOW_MEMORY`，随后一次
动态 MuMu 重启改变了 boot id。重启后的独立续接使用了新 request/operation，并真实到达
`FIRST_FRAME_DRAWN`，但 `READINESS_SLO_EXCEEDED`（18,345 ms > hot 10,000 ms）。这两条失败
均保留为权威观察，本设计不把它们改写为 PASS。

复核源码和失败阶段时间线确认：MuMu 重启同时结束 Host `:sandbox_server`，而
`RuntimeBrokerService` 的 `SessionRegistry`、`BrokerStateStore`、Guest connection pool 和
prewarm `pending` 均为进程内状态；新 boot 后不存在上一 lane 所谓的 hot runtime state。现有
续接器却只跳过 install/import-only 并直接以 hot 请求进入 `prepareGuest`，所以 package state、
broker connect、Guest prepare 被压进 hot deadline。这是恢复编排与 Host/Guest owner 边界问题，
不是放宽 SLO 的理由。

## 2. 采纳的最小修复

在“明确 host-scoped `LOW_MEMORY` → 一次动态 MuMu 重启 → 同一 target/user/iteration/mode
独立续接”边界内，续接器向现有 `DebugCommandActivity` 发出一次独立 `prepare` 操作：

- 生成新的 request/operation ID，记录新旧 boot、target/package/user、attempt、retry budget、
  阶段耗时、完整命令结果和 artifact path；
- 等待明确的 `PASS` + `PREPARED/ALREADY_PREPARED` 终态，不使用固定 readiness sleep，不启动
  Activity，不计入 launch row，也不覆盖原始 LOW_MEMORY/hot 失败；
- rebootstrap 成功后，原失败坐标仍以原 mode（hot 或 cold）和原 10/30 秒 production SLO
  发出一个新的真实 launch request；该 launch 仍必须独立达到
  `REQUEST_ACCEPTED → GUEST_READY → ACTIVITY_RESUMED → FIRST_FRAME_DRAWN`、Window/Surface
  非空及非黑截图；任何失败立即终止；
- 动态重启预算严格为一次。第二次 LOW_MEMORY、rebootstrap 失败或续接 launch 的任何非
  LOW_MEMORY 失败都直接 BLOCKED。

该操作复用已有 package-neutral `prepare` 合同和 R03 的真实 readiness gate；不增加
package/serial/model 分支，不持久化或恢复失效 Binder/token，不增加 launch retry，不扩大
deadline。VA/NBB 对照仍以真实 ProcessRecord/process death/rebind 为边界：VA
`VActivityManagerService.startProcessIfNeedLocked/processDead`、`ActivityStack.startActivityProcess/
processDied`、`VirtualRuntime.crash`，以及 NBB `BProcessManagerService.startProcessLocked`、
`ActivityStack.startActivityProcess`、`BActivityThread.bindApplication` 都先建立新的 process
owner，再处理 Activity；rebootstrap 对应的是这个新 owner 建立阶段，不是伪造旧 Activity 存活。

## 3. 证据和回归边界

rebootstrap 证据位于独立 child attempt 的 `post-restart-rebootstrap.json`，与失败坐标的
`case.json`、`first-failure-full`、restart record 和新的 launch case 一起进入 artifact index。
原始 failure lane 不删除、不替换；aggregate 只在唯一终态坐标满足完整期望行数且没有 terminal
failure 时判定 PASS。

先执行 Python 单测、静态 R05 gate、Android debug build 和定向 DingTalk post-restart lane；
定向 lane 通过后，必须在新的 clean commit 上重新执行完整两轮 R05、C1/C2/C4/SX 回归和
user0/user1 各 15 分钟且至少 50 周期短测，才能关闭 `KI-R03-066` 和 C4。
