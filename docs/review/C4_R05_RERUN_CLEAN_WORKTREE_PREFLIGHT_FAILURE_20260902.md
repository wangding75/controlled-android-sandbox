# C4-R05 重启前 clean-worktree 首次失败记录

日期：2026-09-02  
任务：`C4-R05`  
分类：验收编排/证据落盘顺序问题，不是设备或产品测试失败

## 首次失败原始事实

- 续接预检命令：`python scripts/verify-catch-up-continuation.py`
- 预检结果：`PASS`，识别 `C4-R05`，本地与远端 HEAD 均为
  `72b3c374c1b6afa72f0c09de978dd011eb01d8f9`。
- 预检更新了已跟踪文件
  `verification/catch-up/C0-T01/continuation-preflight.json` 的时间、HEAD 和设备
  boot ID 字段。
- 随后第一次启动 R05 命令立即返回：
  `C4-R05 formal acceptance requires a clean worktree before evidence capture`
- 此次失败发生在 build、安装、formal matrix 和任一回归子任务之前；没有新的设备测试
  失败、隐藏重试或产品根因结论。

## 证据和处理

- 原始差异已保留在当时的 Git diff 中，只有上述预检证据文件的动态快照字段变化。
- 失败决策：不盲目重试，先记录原因并提交新的预检快照。
- 纠正方式：将新的 `continuation-preflight.json` 与本记录、进度变更作为独立证据提交；
  提交后重新确认 local/remote HEAD 一致、工作区干净，再启动 R05。
- 这是一次有幂等条件的编排重试：重试仅重新执行同一 R05 续接入口，不删除或覆盖已有
  formal durable evidence，也不改变 timeout、LOW_MEMORY 或首帧验收门槛。
