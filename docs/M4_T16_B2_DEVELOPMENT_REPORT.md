# M4-T16 B2 开发报告：Alarm 与 Notification 完整生命周期

## 基线与提交

- B2 起点：`5c73203a14560ac8d2f939227e028ab8b3d9d1c4`（M4-T16 B1）
- B2 功能提交：`7b7ffe2447b8ffb85da47b752517cc4dbbdb012b`
- 开发分支：`feature/m4-t16-b2-alarm-notification`
- 设备验证：未执行，设备证据保持为 0

## 冻结范围

本批次仅覆盖 M4-T16 的 Alarm 与 Notification 子域：

- Alarm Package-Service 持久调度。
- Guest 离线保留和 Runtime 恢复投递。
- Exact、Allow-while-idle、Repeating 状态。
- Listener 与 PendingIntent 两类路径。
- APK Revision、实例和显式取消清理。
- Notification Channel／Group 生命周期。
- 安全 `cancelAll`。
- Foreground Service Notification 映射。
- 点击、删除、Action PendingIntent 元数据。
- Channel 删除、查询和 Notification 状态恢复。

JobScheduler 约束、周期、Deadline、Expedited、重试和退避仍属于 B3。

## 实现结果

### Typed Alarm 契约

`VirtualAlarmSnapshot` 现在持久保存：

- Alarm ID、触发时间和重复间隔。
- Exact、Allow-while-idle 状态。
- Listener／PendingIntent 投递类型。
- PendingIntent 持久 Token ID。
- Owner Process、Generation、APK Revision。
- 有界 Parcelable Token payload。
- 已成功投递次数和更新时间。

`IVirtualSystemServiceSession.scheduleAlarm` 和 `IVirtualSystemServiceObserver.onAlarm` 均改为 typed Parcelable，不使用业务 `Bundle`。

### Package-Service 调度与恢复

`VirtualSystemServiceStore` schema 升级为 4，并保留 schema 1～3 读取兼容。实现包括：

- Alarm 数量、载荷和字段边界。
- 原子持久化失败回滚。
- Package Service 重建后重建 Future。
- Guest 不在线或回调拒绝时保留 Alarm 并重试。
- 新 Guest Generation 主动接管同一进程的持久 Alarm。
- 一次性 Alarm 成功后删除。
- Repeating Alarm 计算下一触发点并持久化投递计数。
- APK Revision 更新时移除旧 Alarm。
- PendingIntent 取消或 One-shot 消费后清理依赖 Alarm。

### 持久 PendingIntent Alarm 投递

Runtime 重启后原始 Sender Binder 可能不存在。新增持久 Token 恢复路径：

- `VirtualPendingIntentRegistry.sendPersistent` 按稳定 Token ID 恢复 Sender 状态。
- 恢复记录仍校验 Creator Package、虚拟 UID 和 APK Revision。
- Guest Runtime 将恢复 Alarm 回调连接到当前 PendingIntent Dispatcher。
- Sender 不可恢复时回调失败，Package Service 保留 Alarm 并重试，不误记为成功。

### Notification 状态模型

`VirtualNotificationSnapshot` 新增：

- APK Revision。
- Content、Delete、Action PendingIntent Token ID。
- Foreground Service 标记。
- 保留大小写的 Foreground Service 组件键。

`VirtualNotificationChannelSnapshot` 增加 APK Revision。Channel／Group 状态、Notification 活跃记录和宿主 ID 映射均在 Package Service 中持久化。

### Notification 生命周期

实现并验证：

- Channel 和 Group 创建、更新、查询和删除。
- 删除 Group 时级联删除其 Channel 和关联 Notification 状态。
- 删除 Channel 时仅删除当前虚拟 package/user/revision 的关联 Notification。
- `cancelAll` 枚举并调用当前 Guest 拥有的宿主 Notification ID，不调用宿主全局 `cancelAll`。
- Notification 点击、删除和 Action Sender 必须引用同 Revision 的有效持久 PendingIntent。
- PendingIntent 取消后只清除匹配的 Notification 引用。
- Foreground Service Notification 映射状态可在 Package Service 重建后恢复。
- APK Revision 查询会清理旧 Notification 和 Channel/Group 状态。

## 架构修复

开发过程中发现并修复两项结构问题：

1. Alarm 恢复最初依赖已失效的原 Binder Token。现改为持久 Token 重新物化和投递。
2. Framework Core 最初直接依赖 `framework.routing` 内部标记接口。标记契约已移入允许共享的 `framework.identity` 边界，包依赖门禁恢复通过。

另外修复了 Foreground Service 组件键被统一转小写的问题，避免破坏 Java 类名映射。

## 验证结果

已通过：

- Typed AIDL／Parcelable 契约。
- Runtime／Framework 包边界。
- Alarm exact、allow-while-idle、Listener、PendingIntent 和 Repeating 状态。
- Package Service 重建后的 Alarm 接管和投递。
- PendingIntent 持久 Token 无 Binder 重发。
- 一次性和重复 Alarm 生命周期。
- Alarm／Notification APK Revision 清理。
- Notification Channel／Group 创建、查询、删除和级联。
- Foreground Service、Content、Delete、Action PendingIntent 恢复。
- 安全 scoped `cancelAll` 回归。
- M4-T14 Service 和 M4-T15 Activity/Task 回归。
- 静态 Android 源码编译及全部 Host self-test。
- Native 文件系统、PLT Hook、崩溃记录和 JNI 边界测试。
- M3 严格证据门禁。
- 两次源码 ZIP 字节级可复现比较。
- Shell、Python 和 PowerShell 静态检查。

## 阶段指标

| 维度 | B2 结果 |
|---|---:|
| 能力条目 | 99 |
| 源码 complete | 95 |
| 源码 partial | 4 |
| 源码加权完成度 | 98.0% |
| 生产接线 wired | 91 |
| 生产接线 partial | 6 |
| 生产接线加权完成度 | 95.9% |
| 设备 verified | 0 |
| 设备证据完成度 | 0.0% |

这些比例是仓库证据覆盖率，不代表真实 APK 兼容率。

## 与 VA／NBB 的阶段差距

本批次缩小了源码层面的持久 Alarm、PendingIntent Alarm 恢复、Notification Channel/Group、FGS 映射和交互 Sender 差距。

仍明显缺少：

- Android AlarmManager wakeup、Doze、配额、重启广播和 OEM 时序验证。
- SystemUI 中点击、删除、Action 的真实 Binder 时序。
- Foreground Service 通知时限和 Android 版本差异验证。
- Notification Listener、OEM Channel 字段和真实系统恢复验证。
- VA/NBB 长期积累的设备/API 兼容适配。

## 下一批次

M4-T16 B3：JobScheduler 约束、重试与最终收口。

- typed 网络、电量、存储、空闲约束。
- Periodic、Minimum Latency、Override Deadline、Expedited。
- 持久化 Job、失败重试和线性／指数退避。
- Runtime 恢复、Revision／实例清理和 Framework 投影。
- 更新正式 M4-T16 阶段报告、VA/NBB 对比和最终标签。
