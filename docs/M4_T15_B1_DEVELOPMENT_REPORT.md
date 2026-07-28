# M4-T15 B1 开发报告：LaunchMode、Intent Flag 与 Task 核心策略

日期：2026-07-29

起始提交：`a22ab564a3b2515219b59e43846b645252ad8606`（M4-T15 阶段性实现）

正式迭代起点：`68a93bc9983d3a8fe8929ce992d4f56649a8af19`（M4-T14）

状态：**PASS — SOURCE/HOST VERIFIED；DEVICE NOT TESTED**

## 1. 本批次目标

固定五种 LaunchMode、关键 Intent Flag、Document Mode 和 Task 所有权模型，并将所有可恢复 Task 绑定到 APK Revision。新增 Task 操作必须通过 typed 契约进入 Runtime Broker，禁止直接使用宿主任务结果。

## 2. 已实现内容

### LaunchMode 与 Flag

- 保留并扩展 `standard`、`singleTop`、`singleTask`、`singleInstance`、`singleInstancePerTask` 测试矩阵。
- `MULTIPLE_TASK` 缺少 `NEW_TASK`/`NEW_DOCUMENT` 时 fail closed。
- `CLEAR_TASK` 缺少 `NEW_TASK` 时 fail closed。
- `NEW_DOCUMENT`、`DocumentLaunchMode.ALWAYS` 和 `INTO_EXISTING` 会生成一致的宿主启动 flag。
- `singleInstance` 与 `INTO_EXISTING` 的冲突组合被拒绝。

### Document Mode

新增 `DocumentLaunchMode`：

- `NONE`
- `INTO_EXISTING`
- `ALWAYS`
- `NEVER`

`INTO_EXISTING` 使用受限 `documentKey` 查找同 Virtual User、Package、APK Revision、组件的文档任务，并清理到根 Activity 后投递新 Intent。`ALWAYS` 每次建立独立文档任务。

### Task 操作

新增 typed Task 操作：

- `MOVE_TO_BACK`
- `FINISH_AFFINITY`
- `FINISH_AND_REMOVE_TASK`

现有查询、前移和删除操作继续保留。所有生产调用绑定 Session、Generation、Virtual User、Package 和 APK Revision。

### APK Revision 生命周期

- Active Task、Recent Task 和 checkpoint 均记录 `packageRevision`。
- 新 revision 准备前清理旧 revision Task。
- 实例停止时清理该 Virtual User/Package 的全部 Task 和 Recent Task。
- callerTask、singleTask/singleInstance 复用、Affinity 复用和查询均禁止跨 revision。

### Checkpoint

- checkpoint schema 升级为 2。
- schema 1 保持可读。
- schema 2 增加 APK Revision、Document Mode 和 documentKey。
- CRC、4 MiB 上限、原子替换和损坏隔离规则保持不变。

### 代码结构

从 `BrokerActivityRuntime` 抽离：

- `ActivityLaunchSpecFactory`
- `ActivityTaskOperationDispatcher`

`BrokerActivityRuntime` 从 423 行降至约 284 行，避免继续形成新的运行时 God Class。

## 3. 当前证据

专项静态编译与自测试已覆盖：

- Flag 非法组合。
- `INTO_EXISTING` 和 `ALWAYS` 文档任务。
- `finishAffinity`、`finishAndRemoveTask`、`moveTaskToBack`。
- APK Revision 查询隔离和清理。
- schema 1/2 checkpoint 往返。
- typed Parcelable 往返。
- Broker Task 操作生产适配。

## 4. 未完成项

以下仍属于 M4-T15，进入 B2/B3：

- Activity Result 注册键兼容入口。
- Intent Sender Activity Result。
- Result Intent typed 载荷。
- checkpoint 写入失败的事务回滚。
- Guest Framework Running/Recent Task 投影。
- Framework `finishAffinity`、`finishAndRemoveTask`、`moveTaskToBack` 入口。
- Android 设备上的真实 Task/Recents/Configuration 行为。

## 5. 风险与限制

- `documentKey` 当前由调用方提供，尚未从真实 Intent URI/identifier 自动规范化。
- `finishAffinity` 依据 Task affinity 统一处理，尚未覆盖 Activity 级自定义 affinity 的极端组合。
- schema 1 数据没有真实 APK Revision，只会恢复为 `legacy`，生产准备新 revision 后将被清理。
- 所有结论仅为源码和 Host self-test 证据，设备证据仍为 0。

## 6. 验证结论

- M4-T15 Activity/Task 专项门禁：PASS。
- Typed AIDL、架构边界、Runtime/Framework 包边界：PASS。
- 静态 Android 源码编译及全部 Host self-test：PASS。
- Native、M3 严格阻断、可复现源码包：PASS。
- `verify-all.sh` 在执行环境总时限后被终止，终止前无失败项；未执行完的同序门禁独立续跑并全部 PASS，合并日志作为本批次验证证据。
