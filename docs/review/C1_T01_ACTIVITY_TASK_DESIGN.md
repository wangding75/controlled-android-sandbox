# C1-T01 Activity/Application 与任务栈验收设计

## 结论

当前 HEAD 已具备 framework-owned Activity route/task ledger、物理 Stub 映射、ActivityResult、配置重建、进程代际恢复和虚拟用户隔离的实现与静态自测。C1-T01 本轮关闭的是当前 HEAD 在 MuMu `RD测试` 上的可回溯任务栈证据缺口，不重写已通过的运行时路径。

## DISCOVER / CLASSIFY

- `python tools/capability/run_local_capability_audit.py --all`：42 个门禁，30 `PASS`、12 个已登记 `KNOWN_ISSUE`、0 `NEW_REGRESSION`；`activity-task` 为 `PASS`。
- `python scripts/check-activity-task-virtualization.py`：`PASS`；ActivityTaskLedger、Broker transport、checkpoint、ActivityResult 和 framework interceptor 的静态契约齐全。
- `python -m unittest tools/capability/test_a01_semantic_runner_gate.py`：18 tests `PASS`。
- `python tools/static_android_compile.py`：全量静态 Android 编译与 self-test `PASS`。
- 既有 P5 报告将 A01 task/evidence runner 的时序稳定化列为待办；因此本轮分类为 `TEST_EVIDENCE_GAP`，不是可由 marker 或单次 `LAUNCH_PASS` 掩盖的 runtime PASS。

## DESIGN

新增 `tools/capability/run_c1_t01_rd.py`，只负责 C1-T01 RD_BASELINE 证据：

1. 每次运行通过 `resolve_rd_environment('RD测试')` 动态解析设备，不接受固定 serial 作为默认值。
2. 对虚拟 user 0 和 user 1 分别执行 cold/hot Activity task matrix；默认每个用户每个 mode 运行 50 轮。
3. 每轮保存 before/transition/after `dumpsys activity activities`、完整 logcat、fixture lifecycle observation、CAS runtime route/mapping 和判定 JSON。
4. 语义判定复用 A01 的 system evidence evaluator；fixture 只能提供 lifecycle observation，不能直接提供 `pass`、top 或 stack 结论。
5. `BACK_REQUEST` 与真实 Back 之间保留 800ms 的 fixture 快照窗口；失败最多在清理 Host/Guest task 后重试一次；仍失败则输出明确分类并以非零退出，禁止改名为 PASS。
6. 设备专项补跑 ActivityResult transport 和 process-death generation recovery；配置重建与 singleInstance 以现有 ledger self-test 覆盖，显式 referrer 和物理 rotation/configuration change 不从间接证据推断。
7. 证据仅声明 `RD_BASELINE`，不外推 API matrix、OEM、商业应用或 VA PRO 等价性。

实现逻辑不改变生产 runtime；若 RD 证据复现 runtime defect，应先保留原始 evidence，再进入下一轮分类和修复，不在 runner 中降级断言。

## 验收门

- user 0、user 1 各 50 轮的 standard、singleTop（top/non-top）、singleTask、CLEAR_TOP（standard/singleTop）和 REORDER_TO_FRONT 全部通过；
- 每个结果均同时具备 fixture lifecycle、framework task dump、runtime route/token mapping 和真实 Back 后栈证据；
- ActivityResult transport 与 process-death generation recovery 专项 probe 通过；
- 当前 commit、APK SHA-256、设备快照、API/ABI、profile/user、原始日志目录和 Known Issue 分类写入 receipt；
- 任一用户/轮次失败即整体失败，receipt 不得标记 `DONE`。

正式结果与未覆盖维度见 `verification/catch-up/C1-T01/c1-t01-rd-summary.json` 和
`verification/catch-up/C1-T01/c1-t01-supplemental-probes.json`。
