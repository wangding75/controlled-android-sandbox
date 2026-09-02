# C4-R05 启动 TimeoutException 性能异常续接策略（2026-09-02）

## 1. 决定与适用范围

针对 MuMu `RD测试` 宿主机性能导致的正式矩阵抖动，用户批准将**明确的启动/Guest
`TimeoutException`** 视为可接受的性能限制异常。本策略只允许从同一
`target/user/iteration/mode` 坐标继续，最多执行 5 次显式重试；原始失败仍保留在
`observations` 和该坐标的历史 attempt 目录中，不得改写为 PASS。

本策略覆盖 R03 子 runner 返回的失败 launch command result，其中
`commandResult.status` 为 `FAIL` 或 `ERROR`，且 `result.command` 为 `launch`（或缺省）并在
`result.errorType`、`result.errorMessage` 或明确的 `commandResult.detail` 中出现
`java.util.concurrent.TimeoutException`。当前 Fanqie `user0/cold-004` 首失败符合该签名。

## 2. 不适用的失败

下列情况不属于本策略，仍然 fail-closed：

- 仅出现 `debug-command-result timeout`、日志文本 `timeout` 或宿主 phase/subprocess
  timeout，但没有失败 launch result 的明确 `TimeoutException` 类型；
- 黑屏、Window/Surface 缺失、未达到 `FIRST_FRAME_DRAWN` 但没有上述异常类型、添加事务
  失败、FATAL、ANR、坐标缺失、设备恢复失败或其他 CAS/App/SX 错误；
- 5 次重试预算用尽，或任一重试出现非该类型的真实失败。

Host `LOW_MEMORY` 仍按既有策略单独记录、动态重启和 rebootstrap；它不消耗本策略的
TimeoutException 重试预算，也不替代真实首帧验收。

## 3. 证据与重试状态机

每次失败先由 fail-fast R03 child 完成完整首失败快照，再由 wrapper 分类。每次续接都
创建独立 `attempt-NNN` 和新的 request/operation ID，输出显式
`PERFORMANCE_TIMEOUT_RETRY_ACCEPTED` 事件并记录：失败 case 路径、request/operation、
attempt、retry ordinal、预算、阶段时间、设备 boot、Window/Surface/截图、事务和日志
证据。不存在捕获异常后的隐式 in-process retry。

状态规则如下：

1. 第一次明确 TimeoutException 记为原始首失败，并允许 retry ordinal 1。
2. 后续同坐标的 TimeoutException 依次消耗 ordinal 2 至 5；成功观察替换该坐标的
   terminal row，但历史失败继续保留。
3. 第 5 次仍为 TimeoutException 时写入
   `PERFORMANCE_TIMEOUT_RETRY_BUDGET_EXHAUSTED` 并阻断；非 TimeoutException 直接阻断。
4. 宿主 phase envelope 到期仍按独立的 durable-lane continuation 规则处理，不扩大
   phase deadline，也不把 phase timeout 当成 PASS。

## 4. 实现与回归入口

- 分类和 bounded continuation：
  `tools/capability/run_c4_r03_low_memory_continuation.py`
- R05 durable-lane selector 与正式入口：`tools/capability/run_c4_r05_rd.py`
- 纯逻辑回归：`scripts/test_c4_r05_orchestrator.py`
- 固定预算：`PERFORMANCE_TIMEOUT_RETRY_BUDGET = 5`，没有可放宽的命令行覆盖参数。

该策略是对 C4-R05 当前 formal campaign 的明确执行决策，不改变生产 readiness SLO、
FIRST_FRAME_DRAWN 定义、商业样本门槛、两轮矩阵、回归项或双用户短测要求。
