# M4-T15 B2 开发报告：Activity Result、重建与事务恢复

日期：2026-07-29

起始提交：`44cb14161eeaf34ac2e81d59620b4273879e6bf0`（M4-T15 B1，已合入 `main`）

状态：**PASS — SOURCE/HOST VERIFIED，DEVICE NOT TESTED**

## 1. 本批次目标

补齐 Result Who、Request Code、Result Code、Result Intent、Activity Result 注册键、Intent Sender Result、Configuration 重建后的 token 迁移，以及持久化失败的事务性回滚。

## 2. 已实现内容

### Typed Result Intent

新增有界 typed 契约：

- `ActivityResultIntentSnapshot`
- `ActivityResultSnapshot`
- `ActivityResultRequest`
- `ActivityResultResult`

Result Intent 支持 action、data URI、MIME type、component、flags、ClipData 描述和最多 64 个字符串化 extra。跨 Binder 路径不再只传递无结构 `dataToken`。

### Activity Result API 兼容入口

新增 Broker 权威注册表：

- registration key 到 0～65535 request code 的稳定映射
- 单 Activity 最多 128 个注册项
- 重复注册幂等
- unregister
- typed drain

`GuestActivityResultBridge` 提供注册、注销、启动、Intent Sender 启动、完成和结果排空入口。Guest 结果最终仍进入 `onActivityResult`，可供基于该入口的兼容层继续分发。

### Result 所有权

未完成的 Result 链现在保存：

- caller stable ID
- Result Who
- registry key
- request code
- Intent Sender token

Activity token 在 Configuration Change、进程 generation 更新和 Broker checkpoint 恢复后变化时，Result 所有权会改写到新 token。

### Checkpoint schema 3

- schema 1、2 继续可读，并分别通过兼容性回归测试。
- schema 3 增加 Activity stable ID、注册键和待完成 Result 所有权。
- 已完成但尚未投递的 Result Intent 与一次性 New Intent route 仍作为 transport 数据 fail closed，不持久化。
- 未完成 Result 所有权属于可恢复状态，不再计入 dropped transport delivery。

### Configuration 与 Guest 投递

- Broker 返回重建后的 Activity token 后，`StubActivityBase` 同步更新 Controller 和 Result Bridge。
- Stub 恢复前台时从 Broker 排空 typed Result，并调用 Guest `onActivityResult`。
- Guest `setResult` 状态通过受限反射桥读取，转换为 typed Result Intent 后提交 Broker。

### 事务回滚

`ActivityTaskLedger` 新增精确内存 rollback snapshot，覆盖：

- Task 与 Activity
- 原 Activity token
- Activity Result 注册表
- Pending Result ownership
- Pending New Intent
- 已完成 Result delivery
- Recent Task 和序列号

Launch、生命周期事件、Task mutation、Result mutation、进程重建、APK Revision 清理和实例删除在 checkpoint 写入失败时恢复该快照。新 Route、Broker envelope 和 pending transaction 同步撤销。checkpoint 恢复中途失败时也会恢复空 Ledger，再隔离损坏文件，避免残留半恢复 Task。

## 3. 测试证据

- Result Intent Parcelable 往返。
- registration key 幂等、容量与非法参数。
- Result Who、request/result code、registry key、Intent Sender token 完整传递。
- schema 1/2/3 checkpoint 兼容读取与 schema 3 pending Result ownership 恢复。
- Configuration/进程重建 token 改写回归。
- checkpoint 写入故障注入后的 Task/Activity/Route 精确回滚。
- 重复 Task checkpoint 导致恢复中途失败时，部分 Ledger 状态清零并隔离 `.corrupt`。
- 实例删除 checkpoint 写入失败时，Task 与 Activity 保持变更前状态。
- Guest typed Result drain 到 `onActivityResult` 的源码接线检查。

## 4. 未完成项

以下进入 B3：

- Framework `getRunningTasks`、Recent Tasks 和 AppTask 投影。
- Framework Task 前移、后移、删除和 finish 入口。
- 禁止宿主任务结果回落的拦截门禁。
- 最终能力矩阵、VA/NBB 对比和正式 M4-T15 冻结。

## 5. 限制

- Result Intent extra 当前只持久化字符串表示，Parcelable、Binder、FD 和自定义对象 fail closed。
- ClipData 仅保存描述，不恢复 item 内容或 URI grant；相关 URI 权限必须走独立 Broker grant。
- Activity Result API 兼容入口提供注册键与 legacy request-code 桥接，尚未直接替换所有 Jetpack 内部实现。
- Guest `setResult` 读取依赖受限 Android 私有字段反射，设备版本差异需要后续模拟器验证。
- 当前证据为源码和 Host self-test，设备证据仍为 0。

## 6. 最终门禁结论

- Typed contract、Activity/Task 专项、架构边界、Runtime/Framework 包边界：PASS。
- 静态 Android 源码编译及全部 Host self-test：PASS。
- Native Hook 测试、M3 严格发布阻断门禁：PASS。
- 双次源码 ZIP 字节级可复现比较：PASS。
- `verify-all.sh` 在证据矩阵完成后受到执行平台时限终止；终止前无失败。剩余门禁已按脚本原顺序续跑并全部 PASS，统一写入批次验证日志。
