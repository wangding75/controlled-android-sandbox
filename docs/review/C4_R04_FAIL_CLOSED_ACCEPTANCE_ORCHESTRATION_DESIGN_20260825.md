# C4-R04 fail-closed 验收编排设计

日期：2026-08-25
任务：`C4-R04`
基线：`46a59f3a9c1f8703213705562f9f48c2bff02691`
前置：`C4-R02`、`C4-R03`；`KI-R03-060` 保持开放并作为后续回归输入

## 1. 目标与边界

C4-R04 只重写验收编排的终态判定和故障注入，不把静态 marker、Guest 进程存在或命令返回成功
当作真实显示成功。生产启动修复属于 C4-R03，正式两轮 RD 关门属于 C4-R05；R04 通过并不关闭
`KI-R03-053`、`KI-R03-054`、`KI-R03-056` 或 `KI-R03-060`。

R04 的编排器由两层组成：

1. `tools/capability/c4_r04_fail_closed.py`：纯、无设备副作用的 request-scoped predicate，
   负责首帧、窗口、Surface、截图、阶段 timing、retry decision、事务残留和 artifact schema。
2. `tools/capability/run_c4_r04_rd.py`：入口编排器。默认执行故障注入；`--mode recovery` 是独立
   恢复用例；`--mode live` 只调用已有的 R03 首帧 collector，不增加自动 launch retry。

编排器没有固定 sleep readiness，也没有 catch-all 后重跑。首次失败与恢复观察写入不同文件，
恢复结果不能覆盖首次失败。

## 2. fail-closed 合同

一个 launch 只有同时满足以下动态证据才是 `PASS`：

- 同一 request/operation/package/user/revision 关联；
- `REQUEST_ACCEPTED → GUEST_READY → ACTIVITY_RESUMED → FIRST_FRAME_DRAWN` 顺序完整；
- 每个阶段有开始、结束、duration 和 deadline，且 timing 可校验；
- 当前 operation `firstFrameDrawn=true`；
- `windowsCount>0`、`reportedDrawn=true`、`hasVisible=true`，目标 Activity/package/revision 正确；
- Surface 非空且绑定同一目标 package/revision；
- 截图有非透明、非黑像素、非 Host 占位，且有 frame hash；
- 当前 operation 无 FATAL、ANR、BadToken 或 ViewRoot 错误；
- `attempt=1`、`retryBudget=0`、`automaticRetryPerformed=false`，retry decision 为明确的
  `NO_RETRY/NONE/NOT_APPLICABLE`。

`GUEST_ACTIVITY_CREATE`、`RESUMED`、`LAUNCH_PASS` 等 marker 只记录为非权威字段。即使 marker 存在，
`windows=[]`、draw timeout、Surface 缺失、截图黑屏或 request/revision 不匹配仍必须为 `FAIL`。

添加负面合同要求同 package/user 的 mutating operation 单飞；第二个请求只能是明确 `BUSY` 或
幂等结果。结束时 staging、in-flight transaction、半发布 revision 和孤儿实例必须为空。

## 3. 故障注入与独立恢复

`python tools/capability/run_c4_r04_rd.py --mode failure-injection` 注入并逐项确认 runner 的
最终状态为 `FAIL`：

| 注入场景 | 预期分类 | 不能被什么掩盖 |
|---|---|---|
| `windows-empty` | `WINDOWS_EMPTY` | `LAUNCH_PASS`、Guest Activity marker |
| `draw-timeout` | `DRAW_TIMEOUT` | 总命令仍返回、Activity 已创建 |
| `bind-failure` | `BIND_FIRST_ATTEMPT_FAILED` | 静默重试或扩大 deadline |
| `duplicate-add` | `DUPLICATE_MUTATION_ACCEPTED` | 两次都返回成功 |
| `staging-residue` | `STAGING_RESIDUE` | 主 operation 已返回成功 |

每个场景保存 `first-observation.json`、`first-decision.json`、`final-observation.json`、
`final-decision.json`；其 `attempt=1` 且没有自动重试。`--mode recovery` 另存一条不同
request ID 的恢复观察，只验证恢复本身，不改变首失败结论。

## 4. VA/NBB 参考映射

设计前完整查阅以下参考实现，提取状态边界而不复制代码：

| CAS 编排关注面 | NBB 参考 | VA 参考 | 采纳合同 |
|---|---|---|---|
| 安装/事务 | `ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/BPackageManagerService.java`、`BPackageInstallerService.java` | `ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/server/pm/VAppManagerService.java` | 分阶段、单入口、失败后不发布半状态 |
| 启动/生命周期 | `BlackBoxCore.java`、`BActivityManagerService.java`、`ActivityStack.java`、`BProcessManagerService.java`、`BActivityThread.java` | `VActivityManager.java`、`server/am/ActivityStack.java`、`StubActivity.java`、`HCallbackStub.java`、`AppInstrumentation.java` | process/Activity/window 生命周期分阶段，Binder death 驱动清理 |
| Window 合同 | `ContextCompat.java`、`IWindowManagerProxy.java`、`IWindowSessionProxy.java` | `WindowManagerStub.java`、`WindowSessionPatch.java` | Host identity 只作最小 capability，最终仍由 framework addView/draw |
| 编排重试 | NBB/VA 的 bind/process death 边界 | 同左 | 仅对明确可重试错误由独立恢复用例验证；主门禁首次失败即停 |

采纳：状态机、请求关联、明确 deadline、death/cleanup 和成功后的动态窗口/首帧证据。
不采纳：复制旧实现、固定 sleep、无分类重试、用 Stub/marker 替代 Window/Surface/截图、把一次
恢复成功覆盖首失败。

## 5. 验收与产物

本地门禁：

```text
python scripts/test_c4_r04_fail_closed.py
python scripts/check-c4-r04-fail-closed.py
python tools/capability/run_c4_r04_rd.py --mode failure-injection
python tools/capability/run_c4_r04_rd.py --mode recovery
```

机器产物位于 `verification/catch-up/C4-R04/acceptance/`，包括失败注入摘要、恢复摘要、每个
场景的首失败/最终观察和 `artifact-index.json`。live 模式的原始设备目录位于对应 `live-r03/`，
记录 command、stage timing、attempt、error classification、retry decision 和索引。

`KI-R03-060` 的历史 `hot-017/hot-019` 首失败仍是权威证据；live 或恢复 lane 的非确定性 PASS
只能作为独立观察，不能关闭该 Issue。R04 完成条件是编排器本身满足上述 fail-closed 注入、恢复、
静态和 schema 门禁；R05 仍必须在同一 clean commit 上执行两轮完整 RD 验收。
