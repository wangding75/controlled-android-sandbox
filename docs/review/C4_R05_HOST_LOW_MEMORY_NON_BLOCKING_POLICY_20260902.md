# C4-R05 Host LOW_MEMORY 非阻断策略（2026-09-02）

> 2026-09-02 追加执行覆盖：明确的启动/Guest `TimeoutException` 现按宿主机性能限制
> 允许最多 5 次显式重试，具体分类和证据规则见
> `docs/review/C4_R05_PERFORMANCE_TIMEOUT_RETRY_POLICY_20260902.md`。本文件关于 Host
> `LOW_MEMORY` 的独立无限次数记录/恢复规则仍然有效；以下历史“不放宽”文字按该追加
> 覆盖理解，不得用泛化 timeout 文本触发重试。

## 当前决定

受 MuMu `RD测试` 宿主机性能限制，Host 进程的显式 `ApplicationExitInfo reason=3
(LOW_MEMORY)` 属于允许的环境边界事件。本次 C4-R05 formal lane 对这类事件不设置按次数
阻断：每次事件都必须保存原始失败证据，动态重启并重新解析 MuMu 环境，完成 Host/Guest
rebootstrap 后，从原始失败的精确 target/user/iteration/mode 坐标继续矩阵。

该策略只适用于同时满足以下条件的事件：

- case command 为 `ERROR`，且错误属于 `RD_ENVIRONMENT_RESOLUTION_BLOCKED` 或等价的
  环境解析失败；
- failure bundle 中存在当前 Host package 的 `ApplicationExitInfo LOW_MEMORY` 证据；
- request、operation、attempt、阶段时间、设备 boot、Host/Guest、Window/Surface、进程、
  transaction 和截图证据均被保留；
- 动态 MuMu restart/rebootstrap 成功，并且恢复坐标完整可解析。

## 不放宽的边界

`LOW_MEMORY` 的非阻断只改变环境事件的次数策略，不改变启动验收门槛。cold/hot
`FIRST_FRAME_DRAWN` deadline、动态 Window/Surface/截图检查、add gate、两轮完整矩阵、
回归和 30 分钟双用户短测均保持原任务书要求。没有 Host `LOW_MEMORY` 证据且没有明确
launch/Guest `TimeoutException` 类型的黑屏、启动失败、添加失败、静态 marker、FATAL、
ANR、非环境错误、恢复失败、坐标缺失或 phase deadline 到期，仍然立即 fail-closed。
明确 `TimeoutException` 仅按追加策略最多重试 5 次，不能扩大 SLO 或 phase deadline。

每个 `LOW_MEMORY` 事件是独立的环境恢复记录，不是隐藏的 case 自动重试；原始失败行
进入 `observations`，后续成功行只替换该坐标的 terminal observation，不删除历史失败。
实际执行仍受 R05 的 12 小时 phase envelope 约束，不通过扩大 timeout 或 fixed sleep
掩盖阶段超时。

## 实现与验证入口

- 编排实现：`tools/capability/run_c4_r03_low_memory_continuation.py`
- R05 编排入口：`tools/capability/run_c4_r05_rd.py`
- 当前事件回执：`verification/catch-up/C4-R05/20260902-formal-second-low-memory-blocked.md`
- 当前 Known Issue：`KI-R03-069`，`blocks_current_campaign: false`

原 `C4_R05_HOST_PHASE_BOUNDARY_CONTINUATION_DESIGN_20260901.md` 记录的是此前“单次
LOW_MEMORY 恢复”的历史策略；本文件是 2026-09-02 当前 formal lane 的策略覆盖说明。
