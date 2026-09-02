# C4-R05 C1 回归主动中断记录

日期：2026-09-02  
任务：`C4-R05`  
分类：用户要求的编排调整，不是测试失败

## 事实

- 当前 R05 正式两轮矩阵已经完成并保留在：
  `verification/catch-up/C4-R05/formal-two-round-20260902-timeout12h-v1/`。
- 父编排器在 2026-09-02 12:52（Asia/Shanghai）启动 C1 回归：
  `python tools/capability/run_c1_t01_rd.py --instance RD测试 --loops 50`
- 用户在 2026-09-02 13:22 要求停止当前 C1，并将 C1、C2、C4 合并为一个回归批次，SX 保持独立。
- 通过当前 PTY 向该 R05 父进程发送 `Ctrl+C`。父进程和 C1 子进程随后退出；没有执行 kill、设备重置或删除已有证据。
- C1 当前批次没有生成 `regressions/c1-t01-rd-summary.json`，因此没有被判定为 PASS 或 DONE。
- 停止前的 C1 原始证据保留在：
  `artifacts/capability-audit/catch-up-c1-t01/20260902T045201Z/`，已观测到 `user-0/loop-001` 至 `loop-005`。

## 结论

这次停止不产生新的缺陷结论，也不改变 C4-R05 的任务状态；它只结束了旧的“每个回归门独立列在父列表中”的编排过程。现有正式矩阵、首次失败、LOW_MEMORY 记录和 Fanqie TimeoutException 证据均保持原样。

## 后续编排

新的 R05 回归顺序为：

1. `c1-c2-c4`：C1 Activity、C2 Window/Audio、C2 Device Audio、C4 CAS-only；
2. `sx-f1-f5-business`：SX F1-F5。

“合并”只改变批次和报告层级。每个子门仍有独立命令、summary、stdout/stderr、原始证据和 fail-closed 判定；任一子门失败时停止当前批次，并且 SX 不会被执行。
