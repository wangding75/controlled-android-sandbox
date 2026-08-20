# T57-R03-P4-FIX02-A01-FIX03-REPAIR01

## 任务结果

本修复将 Activity/Task reuse 的最终决策与物理 ActivityRecord 转换收敛到 Host framework 的 ATMS/ActivityStarter 链路。Guest 侧只提交带有虚拟语义、真实物理组件和原始 launch flags 的 Host Intent，不再执行 finish-child、move-task-to-front 或手工 `onNewIntent` 拼接。

## 架构闭环

```text
Guest Context/Application Context
  -> RuntimeActivityLaunchCoordinator
  -> Host Intent (physical component + launch flags)
  -> ActivityStarter / ATMS
  -> ActivityRecord / task stack / lifecycle
  -> Guest ActivityThread instrumentation
```

- B1：`CLEAR_TOP`、`SINGLE_TOP`、`SINGLE_TASK`、`REORDER_TO_FRONT` 的所有复用动作由 Host ATMS 执行；Guest instrumentation 不再清理子 Activity。
- B2：所有复用来源统一走 `Context.startActivity` / instrumentation delegate 进入 ATMS，移除了直接返回 `APPLIED` 的决策 applicator 和注册表。
- 物理 identity：`PhysicalActivityIdentityAllocator` 使用固定 bounded pool，无 modulo wrap；第 17 个同时存活 identity fail-closed，释放/重绑/恢复均校验碰撞。
- 冻结架构：普通进程 64 slots、isolated 进程 16 slots、每 slot 16 个 bounded physical windows；物理组件只由 slot × window family 生成。

## 结构化语义证据

`FRAMEWORK_TASK_EVIDENCE {JSON}` 由 fixture 发出，A01 runner 按字段校验 standard、singleTop top/non-top、singleTask、CLEAR_TOP standard/singleTop、REORDER_TO_FRONT。旧的 marker-only PASS 不再作为 task 语义通过条件。每个设备/用例保留 before、transition、after 的 dumpsys activity 与映射/生命周期证据；缺字段、超时或缺 API 均 fail-closed。

## 已执行验证

- `python tools/static_android_compile.py`：PASS；包含 Activity/Task self-tests、physical identity allocator fail-closed self-test。
- `python tools/capability/test_a01_semantic_runner_gate.py`：PASS。
- `python tools/capability/run_local_capability_audit.py --all`：要求 `NEW_REGRESSION=0`；其余 FAIL 均按仓库既有 known issue 分类，不改变无关阶段。
- `adb devices -l`：当前环境无连接设备，因此 API 32/35/36 设备验收不能伪造为 PASS；A01 矩阵明确记录 `missing_api_32`、`missing_api_35`、`missing_api_36` 并 fail-closed。

## 交付边界

本任务不启动 A02，不签发 VA Pro 通过结论。最终 HEAD/TREE、API 证据路径与 SHA256 由 `tools/capability/build_p4_review_pack.py` 写入 review pack manifest。
