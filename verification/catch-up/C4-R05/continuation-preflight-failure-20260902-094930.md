# C4-R05 续接预检首次失败证据（2026-09-02 09:49:30 +08:00）

- 命令：`python scripts/verify-catch-up-continuation.py --output verification/catch-up/C4-R05/continuation-preflight-nonblocking-20260902.json`
- 失败分类：`RD_ENVIRONMENT_RESOLUTION_BLOCKED`
- 原始错误：`adb -s 127.0.0.1:16416 shell settings get secure android_id failed (1): error: closed`
- 影响范围：续接预检无法取得动态 `RD测试` 的 android_id/boot 快照；未开始新的矩阵
  case，也未把本次预检失败当作矩阵通过。
- 处理纪律：这是该预检调用的第一次失败，已先保存证据；后续只允许通过 `RD测试` 的
  动态 MuMu 实例解析执行宿主恢复，再重新运行预检。未使用固定 serial 作为源码或
  runner 选择逻辑。
