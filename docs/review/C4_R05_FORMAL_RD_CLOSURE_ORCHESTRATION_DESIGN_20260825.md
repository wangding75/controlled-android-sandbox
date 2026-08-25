# C4-R05 formal RD closure orchestration design

日期：2026-08-25  
任务：`C4-R05`  
前置：`C4-R04`  
设备：MuMu instance name `RD测试`，运行时动态解析

## 1. 目标与边界

R05 是 C4 的正式关门门禁，不把 R03 的历史 `482 PASS + 8 首次失败 + 10 未执行` 汇总升级为
当前通过。`tools/capability/run_c4_r05_rd.py` 在同一 clean commit 上构建 APK、动态解析设备、
依次执行两轮正式门禁，并在第一个非 PASS 阶段停止。它保留子 runner 的完整原始证据和命令输出，
但不自动重试、不中途 resume、不扩大 deadline 来掩盖失败。

## 2. 两轮和阶段顺序

每轮都记录环境、安装结果、attempt policy 和阶段摘要：

1. `clean-install-cold`：清理 CAS host data，安装当前 commit APK，运行 R04 failure-injection
   与 recovery contract，随后运行 R02 添加矩阵和 R03 500-row 首帧矩阵；
2. `retained-hot-recovery`：保留上一轮状态，重新运行同样的 R04、R02、R03 门禁；
3. 只有两轮均通过，才运行 C1 Activity、C2 Window/Audio、C2 device/audio、C4 CAS-only 和
   SX F1-F5/DingTalk 业务回归；
4. 只有前述回归均通过，才运行 user0/user1 各 15 分钟且各至少 50 cycle 的压力 lane。

R02 runner 增加了向 R05 指定 evidence directory 的参数，默认行为和既有 C4-R02 lane 不变。
R03 的 `--loops 50 --users 0,1 --targets fixture,dingtalk,quark,hongguo,fanqie` 生成每轮
500 个 cold/hot row；其中任何 add、首帧、Window、Surface、截图、FATAL/ANR 或 SLO 失败都直接
写入 `first-failure.json` 并停止。

## 3. 通过合同

- 两轮的 R04 failure-injection/recovery 摘要均为 `PASS`；
- fixture add/delete/re-add 为 50 次，DingTalk、夸克、红果、番茄小说各 10 次，全部首次成功；
- 两用户各 cold/hot 50 次均首次到 `FIRST_FRAME_DRAWN`，动态 Window/Surface/非黑截图有效；
- 无隐藏 retry、固定 readiness sleep、启动超时、黑屏、FATAL、ANR、半发布 revision、in-flight
  transaction、`.install-*` residue 或未收敛资源；
- C1/C2/C4/SX 回归和双用户压力满足任务书；Known Issues 没有新的 P0/P1 阻断。

## 4. 阻断合同和证据

任何阶段返回非零、summary 非 `PASS`、摘要缺失或动态证据不完整，R05 runner 输出
`status=FAIL, outcome=BLOCKED`，保存阶段、命令、return code、summary、stdout/stderr 路径和
首次失败快照；不执行后续阶段。账本应保持 `C4-R05 (BLOCKED)`，记录恢复条件，不能进入 C6。

Durable summary/index：

- `verification/catch-up/C4-R05/summary.json`
- `verification/catch-up/C4-R05/c4-r05-summary.json`
- `verification/catch-up/C4-R05/artifact-index.json`
- `verification/catch-up/C4-R05/start-state.json`

原始 child lane 路径会在 round summary 和 command record 中逐项记录。R05 通过后才允许把 C4
改为 `DONE`、C5 保持 `NOT_APPLICABLE`、下一任务改为 `C6-T01`；否则保留已确认 Known Issues
和原始首次失败证据。
