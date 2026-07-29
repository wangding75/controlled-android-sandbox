# M4-T16 B3 开发报告：JobScheduler 约束、重试与阶段收口

## 基线与提交

- B3 起点：`0fee00868e687f5990bc2d8ca2c39622f566f338`（M4-T16 B2）
- B3 功能提交：`f9f7deb`（`feat(m4-t16-b3): deepen job scheduler policy`）
- 开发分支：`feature/m4-t16-b3-jobscheduler`
- 设备验证：未执行，设备证据保持为 0

## 冻结范围

本批次只完成 M4-T16 中剩余的 JobScheduler 子域并进行阶段收口：

- 网络、电量、存储和空闲约束 DTO。
- Periodic Job、Minimum Latency、Override Deadline、Expedited 和 Persisted 状态。
- 线性／指数失败退避。
- Package Service 重启恢复、Guest Generation 重新绑定。
- APK Revision 和实例删除清理。
- Framework `JobInfo` 策略提取、Guest/Host Job ID 投影。
- Guest `JobService` 执行桥回归。

不包含 Android 设备上的配额、Standby Bucket、Doze、重启和 OEM JobScheduler 时序验证。

## 实现结果

### Typed Job 策略契约

`VirtualJobSnapshot` 从“宿主 ID + 原始 JobInfo payload”扩展为完整的 typed 调度快照，包含：

- Guest Job ID、Host Job ID、状态。
- Owner Process、Generation、APK Revision。
- Required Network Type。
- Charging、Battery-not-low、Storage-not-low、Device-idle。
- Periodic、Interval、Flex。
- Minimum Latency、Override Deadline。
- Expedited、Persisted。
- Linear／Exponential Backoff、Initial Backoff。
- Failure Count、Next Run Time、Last Failure Time。
- 有界 Parcelable JobInfo payload。

`IVirtualSystemServiceSession.reserveJob` 改为直接接收 `VirtualJobSnapshot`。B3 没有新增业务 `Bundle` 或无类型跨进程策略载荷。

### Framework JobInfo 策略提取

新增 `VirtualJobPolicySnapshotFactory`。Guest 调用 `schedule`／`enqueue` 时，在 Host Job ID 和 JobService 重写之前提取以下 Android `JobInfo` 语义：

- `getNetworkType`／Required Network。
- `isRequireCharging`。
- `isRequireBatteryNotLow`。
- `isRequireStorageNotLow`。
- `isRequireDeviceIdle`。
- `getIntervalMillis`／`getFlexMillis`。
- `getMinLatencyMillis`。
- `getMaxExecutionDelayMillis`。
- `isExpedited`。
- `isPersisted`。
- `getBackoffPolicy`／`getInitialBackoffMillis`。

反射提取失败时使用有界默认值，不读取或返回宿主 JobScheduler 的其他应用状态。原 `JobInfo` 的约束仍随重写后的 Host 调度调用传给 Android；调用结束后恢复 Guest Job ID 和原 Service 字段。

### Package Service 持久状态

`VirtualSystemServiceStore` schema 升级为 5，并继续读取 schema 1～4。新增持久字段包括全部约束、周期、Deadline、Expedited、Persisted、退避和失败状态。

状态仍受以下边界约束：

- 每个 package/user 最多 512 个 Job。
- Job payload 最大 512 KiB。
- Package、Virtual User、Process、Generation、APK Revision 全部进入所有权模型。
- 原子文件替换失败时恢复变更前内存快照。
- 损坏或不支持的 schema 拒绝加载。

### 调度和恢复策略

实现并验证：

- Minimum Latency 到期前拒绝早到的 Host callback；Override Deadline 到期可越过最早执行时间。
- Guest 返回 `needsReschedule=true` 时增加失败次数并计算下一次执行时间。
- Linear Backoff 按 `initialBackoff × failureCount`。
- Exponential Backoff 按 `initialBackoff × 2^(failureCount-1)`。
- 两种退避均封顶 5 小时，防止持久状态无限增长。
- 非周期 Job 成功后删除。
- 周期 Job 成功后清零失败状态，并按 Interval 进入下一个 `SCHEDULED` 周期。
- Package Service 重建时，旧 `DISPATCHING`／`RUNNING` 状态恢复为 `SCHEDULED`，不复活旧执行 Binder。
- 同一 Guest 进程的新 Generation 可重新绑定持久 Job。
- APK Revision 变化时删除旧 Job；旧 Revision 活动执行 Binder 同时失效。
- 实例删除继续清除当前 scope 的 Job 和活动执行资源。

Host Android 仍负责真实网络、电量、存储、空闲和配额判定。Package Service 保存并检查可独立确定的时间、重试、所有权和恢复状态。

## 测试结果

新增或强化的 Host 证据：

- 完整 `VirtualJobSnapshot` Parcelable 往返。
- Framework `JobInfo` 约束提取和 typed 权威状态。
- Typed Binder reserve/list 链路。
- Package Service 重建后的约束、策略和 Host ID 恢复。
- Linear Backoff 的失败次数和下一运行时间。
- Periodic Job 成功后的下一周期。
- APK Revision 更新后的旧 Job 清理。
- 旧 Revision 活动执行能力失效。
- Guest `JobService` start/stop/jobFinished 回归。
- scoped cancelAll、Guest/Host Job ID 映射回归。
- M4-T14 Service、M4-T15 Activity/Task、M4-T16 B1/B2 回归。

新增门禁：`scripts/check-job-scheduler-policy.py`，并纳入 `scripts/verify-all.sh`。

## 阶段指标

| 维度 | B3 结果 |
|---|---:|
| 能力条目 | 102 |
| 源码 complete | 98 |
| 源码 partial | 4 |
| 源码加权完成度 | 98.0% |
| 生产接线 wired | 94 |
| 生产接线 partial | 6 |
| 生产接线加权完成度 | 96.0% |
| 设备 verified | 0 |
| 设备证据完成度 | 0.0% |

这些数字是仓库证据覆盖率，不代表 APK 兼容率。

## 限制

- 没有 Android SDK/设备上的真实 JobScheduler 回调证据。
- Android 配额、Standby Bucket、Doze、重启持久 Job 和 OEM 时序未验证。
- `NetworkRequest` 的复杂能力集合当前只归并为有界网络类型，不复制平台内部对象。
- Package Service 不独立模拟电量、存储和空闲状态，依赖 Host JobScheduler 执行这些约束。
- `VirtualSystemServiceStore` 已达到约 1,692 行，M4-T18 总收口时需要评估按资源域拆分。

## 结论

M4-T16 B3 在源码和 Host 测试范围内 PASS。JobScheduler 已从“持久 ID + 执行桥”提升为“typed 约束、时间策略、失败退避、Revision 清理和恢复状态”完整链路。设备和 OEM 行为仍没有证据，不作兼容率声明。
