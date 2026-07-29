# M4-T16 阶段报告：系统调度与通知深化

## 基线

- 正式起点：`f1e683293dbe9a2d1c3a03ef573996476afafda2`（M4-T15）
- B1：`5c73203a14560ac8d2f939227e028ab8b3d9d1c4`
- B2：`0fee00868e687f5990bc2d8ca2c39622f566f338`
- B3 功能提交：`f9f7deb`
- 正式标签：文档和最终门禁通过后冻结为 `m4-t16-source-pass`
- 设备验证：未执行

## 本阶段新增能力

### PendingIntent

- Typed、持久、APK Revision 绑定的 Token。
- Activity Result 类型。
- Mutable／Immutable 和 FillIn Intent。
- ClipData、Sender Permission。
- 虚拟 Creator Package 和 Creator UID。
- Android 过滤身份基础上的相等性。
- NO_CREATE、CANCEL_CURRENT、UPDATE_CURRENT、ONE_SHOT。
- Guest Generation 变化后的持久 Token 重新绑定。
- 实例删除和 APK Revision 清理。

### Alarm

- Package Service 持久调度。
- Exact、Allow-while-idle、Repeating 状态。
- Listener 与 PendingIntent 两类路径。
- Guest 离线时保留。
- Runtime 恢复后按持久 PendingIntent Token 投递。
- 成功投递计数和下一触发时间。
- PendingIntent 取消、实例删除和 APK Revision 清理。

### Notification

- Channel 和 Group 的创建、更新、查询、删除和级联。
- 安全 scoped `cancelAll`，不调用宿主全局 cancelAll。
- Foreground Service Notification 映射。
- Content、Delete、Action PendingIntent 持久引用。
- Channel/Group/Notification 状态恢复。
- PendingIntent 取消后的精确引用清理。
- APK Revision 清理。

### JobScheduler

- Typed 网络、电量、存储和空闲约束。
- Periodic、Interval、Flex。
- Minimum Latency、Override Deadline。
- Expedited、Persisted。
- Linear／Exponential Backoff。
- Failure Count、Next Run、Last Failure 持久化。
- Package Service 重建和 Guest Generation 恢复。
- APK Revision 和实例删除清理。
- Framework JobInfo 策略提取、Guest/Host Job ID 映射。
- Host JobService 到 Guest JobService 的 start/stop/jobFinished 闭环回归。

## 架构变化

- PendingIntent、Alarm、Notification 和 Job 均通过 scoped typed AIDL 能力访问。
- 所有持久资源至少绑定 package、virtual user 和 APK revision。
- 进程相关资源额外绑定 process 和 generation。
- Runtime Broker 没有重新吸收资源状态；`RuntimeBrokerService` 仍为 1,370 行。
- Package Service 权威存储 schema 从 3 逐步升级至 5，并保留旧 schema 读取兼容。
- 新增资源均有限额、载荷上限、事务回滚和失效清理。

## 验证

已通过的源码和 Host 门禁包括：

- Typed AIDL／Parcelable。
- Runtime／Framework 包边界。
- PendingIntent 身份、发送和恢复生命周期。
- Alarm exact/repeating、离线保留和恢复投递。
- Notification Channel/Group、FGS 和交互 Sender 生命周期。
- Job typed 约束、周期、Latency、Deadline、Expedited、Persisted 和退避。
- Package Service 重建、Generation 恢复和 APK Revision 清理。
- Guest JobService 回调桥。
- M4-T14 Service 和 M4-T15 Activity/Task 回归。
- 静态 Android 源码编译及全部 Host self-test。
- Native 文件系统、PLT Hook、崩溃记录和 JNI 边界测试。
- M3 严格证据门禁。
- 双次可复现源码 ZIP 字节比较。
- Shell、Python 和 PowerShell 静态检查。

设备证据仍为 0。上述 PASS 仅指当前源码和本地 Host 测试。

## 仓库指标

| 项目 | 数量 |
|---|---:|
| Git 跟踪文件 | 649 |
| Java 文件 | 414 |
| AIDL 文件 | 42 |
| Java + AIDL 行数 | 50,561 |
| 能力条目 | 102 |
| 源码 complete／partial | 98／4 |
| 源码加权完成度 | 98.0% |
| 生产 wired／partial | 94／6 |
| 生产加权完成度 | 96.0% |
| 设备 verified | 0 |

## 质量判断

M4-T16 的主要改进是把原本分散在 Framework payload 和 Host 系统对象中的调度语义，提升为 Package Service 可验证、可持久、可恢复的 typed 权威状态。资源所有权、Revision 清理和失败回滚比 M4-T15 基线更完整。

当前主要技术债务：

1. `VirtualSystemServiceStore` 约 1,692 行，已同时管理 Account、PendingIntent、Alarm、Notification、Job 和 namespace。
2. Android 隐藏接口和 OEM 变体只完成源码适配，尚未经过目标 API 的真实 Binder 验证。
3. Alarm/Job 的系统配额、Doze、Standby Bucket 和设备重启语义依赖 Host 平台，没有独立模拟。
4. Notification SystemUI 回调、FGS 时限和通知权限行为缺少设备证据。
5. 现有能力矩阵接近满覆盖，但 0% 设备证据意味着不能据此声称接近 VA/NBB 的真实兼容率。

## 后续冻结路线

下一阶段为 M4-T17：Native Hook 与 ABI 架构。

- 扩展 openat2、statx、renameat2、faccessat2、readlinkat、getdents64、mmap。
- 虚拟化 `/proc/self/maps`、`cmdline`、`status`。
- 加强 dlopen、android_dlopen_ext、Native Library 和 Split APK 路径。
- 明确 arm64-v8a、armeabi-v7a、x86_64、x86 支持策略。
- 设计 64 位 Host + 32 位 Companion + 跨位宽 Binder 契约。
- 补网络身份和录音 Java/Binder/JNI 路径。

M4-T18 再执行设备测试前源码总收口，不提前扩大本阶段范围。

## 未确定事项

- Android 各 API 对 PendingIntent、Alarm、Notification、Job 隐藏 Binder 签名的实际差异。
- OEM 对精确闹钟、后台限制、FGS 通知和 Job 配额的附加行为。
- Host 进程重启、设备重启和系统升级后的真实恢复顺序。
- 第三方 APK 的实际启动率和 20 分钟稳定性。
