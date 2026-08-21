# C1-T03 Broadcast 事件模型闭环设计

## 1. DISCOVER / CLASSIFY

本任务开始基线为 `feature/t57-r03-va-pro-capability-campaign` @
`602bdc944408cedcdde5e042e3af2e1ec76d3eb2`，工作区干净，MuMu `RD测试` 尚未在本任务中解析。

前检结果：

- `python tools/capability/run_local_capability_audit.py --all`：42 gates，30 `PASS`、12
  `KNOWN_ISSUE`、0 `NEW_REGRESSION`；诊断性非零符合治理规则。
- `python scripts/check-m5-t3-broadcast-fgs.py`：PASS，确认既有 Receiver/ordered/FGS 源码入口存在，
  但不包含设备运行证据。
- `python scripts/check-broadcast-model.py`：失败项均为既有 `KI-R03-022` 的静态 token 漂移：Receiver
  委托已从 `RuntimeBrokerService` 移入 `RuntimeComponentOperationCoordinator`，恢复已由
  `RuntimeComponentRecoveryCoordinator` 调用，超时上限使用 `RuntimeKeys` 常量别名。
- `verification/catch-up/C1-T03/` 不存在；当前没有 C1-T03 的 RD 原始日志、设备快照或循环回执。

分类结论：`KI-R03-022` 是治理检查 harness 的 `TEST_EVIDENCE_GAP`，不是可据此修改生产 runtime 的
证据。修复范围限定为使检查跟随当前已审查的协调器边界，并补齐真实设备证据；若设备 campaign 暴露
runtime FAIL，另行记录其具体问题，不能由 PASS marker 掩盖。

## 2. 目标与验收矩阵

使用 package-neutral `fixture-basic`，通过真实 Guest `Context` API 触发以下路径：

| 维度 | 证据 | 失败判定 |
|---|---|---|
| manifest explicit | 独立 receiver 收到 action/extra | 缺 marker 或出现 Guest/Host identity mismatch |
| dynamic register/unregister | 每轮 Activity 注册动态 receiver并收到广播，随后停止 Guest | 缺 marker、重复注册泄漏或停止后仍有回调 |
| implicit matching | 高/低 priority receiver 均收到，返回 matched/delivered 计数可回溯 | 顺序/计数不确定 |
| ordered result chain | 高 priority 写入结果，低 priority 验证并更新结果 | result code/data/extras 或顺序不一致 |
| ordered abort | abort receiver 被调用，后续 receiver 不被调用 | abort 后仍 delivery 或状态未标明 skipped |
| ordered async | `goAsync()` 延迟完成并回传最终结果 | token 超时、重复完成或结果丢失 |
| permission | receiver-permission 允许与拒绝各一条 | 拒绝路径仍 delivery |
| virtual-user isolation | user0/user1 各自独立循环，停止后无跨用户 marker | target user/receiver 归属漂移 |
| cleanup / pressure | 双用户各 50 轮，轮间 stop/relaunch | 任一轮失败、fatal/ANR、残留回调或资源不收敛 |

RD 结果只声明 `RD_BASELINE`，不外推 API33+、ARM/16KB、OEM、SX/XH 或 VA PRO 等价性。

## 3. IMPLEMENT_BATCH 边界

1. 更新 `scripts/check-broadcast-model.py`，按当前架构检查 operation/recovery 委托位置和超时常量，
   保留禁止 Broker 直接拥有 Receiver 实现的断言。
2. 在 package-neutral fixture 增加专用 manifest Receiver、动态 Receiver 和异步 ordered Receiver，
   由一个 debug-only campaign Activity 调用公开 `Context` broadcast API。
3. 在 debug Host 增加受控 campaign 命令，按虚拟用户执行固定循环，轮间停止 Guest；只输出可审计的
   JSON 结果和 logcat marker，不把 marker 当作 runtime 成功的唯一依据。
4. 增加 Python RD runner：每次动态解析 `RD测试`，安装当前构建 APK，运行 user0/user1 各 50 轮，
   保存设备快照、APK hash、command JSON、完整 logcat 和结构化 summary；出现 runtime FAIL 时非零退出。
5. 将 `KI-R03-022` 更新为 `FIXED` 并保留原始失败证据路径；不改变其它 Known Issue 或能力范围声明。

## 4. LOCAL_VERIFY / RD_CAMPAIGN 门

提交前运行：

- `python scripts/check-broadcast-model.py`
- `python scripts/check-m5-t3-broadcast-fgs.py`
- `python tools/static_android_compile.py`
- fixture/framework/runtime/app targeted lint 与 debug APK assemble
- `git diff --check`、JSON evidence parse

设备门：

- `python tools/capability/run_c1_t03_rd.py --instance 'RD测试' --loops 50`
- 矩阵必须是 user0/user1 各 50 轮、失败 0；所有关键 marker、ordered 结果链、abort skipped、
  async completion、permission deny、cleanup 记录齐全。
- 原始日志、设备快照、APK SHA-256 和 runner summary 必须位于
  `verification/catch-up/C1-T03/` 或其索引的 `artifacts/capability-audit/catch-up-c1-t03/`。
