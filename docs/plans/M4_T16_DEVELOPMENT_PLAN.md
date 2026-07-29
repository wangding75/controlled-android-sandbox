# M4-T16 系统调度与通知深化开发计划

## 1. 基线与交付规则

- 正式起点：`f1e683293dbe9a2d1c3a03ef573996476afafda2`（正式 M4-T15）。
- 每个开发批次必须完成：专项测试、全仓回归、快进合入 `main`、完整源码 ZIP、完整 Git bundle、增量 Patch、开发报告、验证日志和 SHA-256。
- 最终状态仅允许 `PASS` 或 `BLOCKED`。
- 本阶段继续区分源码能力、生产接线、Host 测试和设备证据；不将源码覆盖率解释为第三方 APK 兼容率。
- 不执行模拟器或真机验证，设备证据保持为 0。

## 2. 冻结范围

### PendingIntent

- Activity Result 类型。
- Mutable/Immutable 行为与 FillIn Intent 合并。
- ClipData 和发送权限。
- Creator Package、Creator UID 虚拟化。
- PendingIntent 相等性。
- Package-Service 持久 Token、跨 Guest 进程恢复。
- One-shot、cancel/update/no-create 生命周期和实例/APK Revision 清理。

### Alarm

- Package-Service 持久调度。
- Guest 进程离线时保留并在 Runtime 恢复后投递。
- Exact Alarm 状态、Repeating Alarm。
- Listener 与 PendingIntent 两类投递路径。
- 实例删除和 APK Revision 更新清理。

### Notification

- Channel 和 Group 完整生命周期。
- 安全 `cancelAll`。
- Foreground Service Notification 映射。
- 点击、删除和 Action PendingIntent 元数据。
- Channel 删除、查询和状态恢复。

### JobScheduler

- 网络、电量、存储、空闲等约束 DTO。
- Periodic Job、Minimum Latency、Override Deadline、Expedited 状态。
- 持久化 Job。
- 失败重试和退避模型。

## 3. 开发批次

执行状态：

| 批次 | 状态 | 提交 |
|---|---|---|
| B1 | PASS | `5c73203a14560ac8d2f939227e028ab8b3d9d1c4` |
| B2 | PASS | `0fee00868e687f5990bc2d8ca2c39622f566f338` |
| B3 | PASS | 功能提交 `f9f7deb`；最终文档提交见正式标签 |

### B1：PendingIntent 持久身份与发送语义

交付：

- 增加 typed PendingIntent 快照和 Package-Service 权威存储。
- 稳定持久 Token 跨 Guest generation 恢复。
- 完成 Activity Result、Mutable/Immutable、FillIn、ClipData、发送权限、Creator 身份和相等性。
- 增加容量限制、事务回滚、实例/APK Revision 清理和专项测试。

退出条件：PendingIntent 专项、Binder 契约、静态 Android 编译和全仓既有门禁通过。

### B2：Alarm 与 Notification 生命周期

交付：

- Alarm typed 调度模型，覆盖 exact/repeating、Listener/PendingIntent、离线保留和恢复投递。
- Notification typed 元数据，覆盖 Channel/Group、点击/删除/Action PendingIntent、Foreground Service 映射和恢复查询。
- 清理、容量、回滚和 scoped cancelAll 门禁。

退出条件：Alarm/Notification 专项、持久化重启测试和全仓回归通过。

### B3：JobScheduler 约束、重试与最终收口

交付：

- typed Job 约束与调度策略快照。
- Periodic、Latency、Deadline、Expedited、失败重试和线性/指数退避。
- Runtime 恢复、实例/APK Revision 清理、Framework 投影和 Guest JobService 回归。
- 更新 VA/NBB 对比、能力矩阵、阶段报告和正式 M4-T16 标签。

退出条件：Job 专项、全仓统一门禁、可复现打包和制品级复核通过。

## 4. 固定质量门禁

- 新增跨进程业务契约必须 typed Parcelable/AIDL，不新增业务大 `Bundle`。
- 所有持久资源必须绑定 package、virtual user、APK revision；进程所有权能力额外绑定 process/generation。
- 持久化数据必须有 schema、容量上限、损坏拒绝和事务回滚。
- Guest 查询不得回落到宿主真实 PendingIntent、Alarm、Notification 或 Job 状态。
- `cancelAll` 只作用于当前虚拟 package/user/revision 资源。
- PendingIntent 的 immutable、发送权限和 Creator 身份必须 fail closed。
- 实例删除、APK Revision 更新和显式取消必须清理对应资源。

## 5. 备份命名

每个批次：

- `controlled-sandbox-m4-t16-bN-<commit>-source.zip`
- `controlled-sandbox-m4-t16-bN-<commit>.git.bundle`
- `controlled-sandbox-m4-t16-bN-<commit>.patch`
- `M4-T16-BN-development-report-<commit>.md`
- `controlled-sandbox-m4-t16-bN-<commit>-verification.txt`
- `controlled-sandbox-m4-t16-bN-<commit>-SHA256SUMS.txt`

正式完成后另生成不带批次后缀的 M4-T16 完整备份和开发计划文档。

## 6. 后续冻结路线

M4-T16 正式 PASS 后进入 M4-T17：Native Hook 与 ABI 架构。M4-T18 再执行设备测试前源码总收口；不在 M4-T16 内提前引入 Native 或设备验收范围。
