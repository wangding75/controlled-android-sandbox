# P1-11 进程 Owner、失败清理与短恢复决策

日期：2026-09-09；前置：P1-05、P1-06、P1-09 均已完成。本文先于 P1-11 的任何产品或验证代码改动完成。

## 参考方法与 CAS 对应

| 参考实现 | 已核对的执行方法 | CAS 对应方法与结论 |
|---|---|---|
| NewBlackbox | `BProcessManagerService.startProcessLocked`（49 行）以 `(buid, processName)` 建立 `ProcessRecord`，`initAppProcessL` 获得 client 后由 `attachClientL` 在 client Binder 上 `linkToDeath`（149、165 行）；death 回调进入 `onProcessDie`，移除 name/pid 索引、杀进程并删除 proc/通知资源（192 行）。 | CAS `SessionRegistry.allocate/transition/markSlotDisconnected/beginRecovery` 以 `(package, user, declared process)` 和 generation 管理 slot。`RuntimeGuestConnectionPool.GuestConnection.onServiceConnected` 先注册 death recipient，再发布 Binder；`clearAndDisconnect` 只退役当前 connection。采用 owner-before-publish、death-to-terminal、显式新 generation；不复制 NBB 的旧 ProviderCall 或 proc 文件模拟。 |
| VirtualApp OSS | `VActivityManagerService.startProcessIfNeedLocked`（750 行）按 `(processName, vuid)` 重用活 client，否则分配 stub；`performStartProcessLocked` 取得 client 后 `attachClient`（680 行）注册 death；`onProcessDead`（730 行）删除 process-name/pid 索引、清理 Service/Activity history 并释放等待者。 | CAS `RuntimeBrokerService.handleGuestDisconnect` 将断开的 slot 标为 `RECOVERING` 或 `FAILED`、执行 ownership sweep，随后 `GuestRecoveryPrewarmCoordinator` 仅对同 generation 的恢复项调用 prepare。`RuntimeGuestLifecycleCoordinator.prepareGuest` 在 `ALLOCATED/PREPARING` 任一失败时回滚为 `FAILED` 并释放 slot。采用 VA 的记录清除和 component cleanup 边界；不采用其旧版主动 kill-all/低槽位 GC 策略。 |
| Android Binder 合同 | `ServiceConnection` 可在超时后晚到；Binder death、binding died、disconnect 都不得让旧 client 覆盖新 owner。 | `RuntimeGuestConnectionPool` 以 map 中的同一 `GuestConnection`、`closing` 和 `failureReason` 防止旧回调发布/退役新连接；`RebindableServiceConnector` 以 Attempt epoch 拒绝并关闭晚到 capability。恢复不重放有副作用的 Guest call，也不把恢复成功改写成首个故障成功。 |

## 已定位的现状与 P1-11 验收设计

1. `RuntimeGuestConnectionPoolSelfTest` 已覆盖死 Binder、回调 death、同 slot 并发重连、death-link 发布栅栏、bind timeout、framework disconnect、release 和物理 shutdown barrier；`RebindableServiceConnectorSelfTest` 已覆盖晚到 `onServiceConnected` 的关闭与不复活。
2. `SessionRegistry` 已将 `PREPARING` disconnect 转为 `FAILED` 并释放 slot，`READY/ACTIVE` disconnect 转为 `RECOVERING`；`beginRecovery` 必须匹配原 generation，生成新的 `PREPARING` generation。`RuntimeGuestLifecycleCoordinator` 与 `GuestRecoveryPrewarmCoordinator` 都对 structured `FAILED` 结果回收 `ALLOCATED/PREPARING`，避免 `SESSION_BUSY:PREPARING` 残留。
3. 现有静态回归不是 API35/36 上五类实进程故障的证据。因此新增的 P1-11 runner 必须把 Java crash、native abort、Broker service death、prepare failure、late ServiceConnection 作为各自独立的首次故障，并为每类运行三次。每例记录故障前后 PID/slot/session/generation、首错、显式恢复结果和遗留资源快照。

## 约束与拒绝方案

- 不把 `killProcess` 当作 LMK；P1-11 只验证可控 Java/native/Broker/prepare/late-callback 故障，MuMu LOW_MEMORY 行为保留给 P2-90。
- 不增加 launch deadline、自动重试、权限或 owner 范围；正常与故障首次操作均为 `attempt=1`。恢复在回执中单列，绝不覆盖首错。
- 不以 slot 数量等同稳定并发。P1-11 仅验证普通 `0..63`、isolated `0..15` 合同及 slot 62/63 的短用例；耗尽/压力归 P2-90。
- 若动态复现表明上述既有 fail-closed 合同已经满足，只新增 fixture/runner/证据，不修改产品运行时代码。若发现缺口，先保留原始回执，再以本表所列对应方法为界做最小修复。
