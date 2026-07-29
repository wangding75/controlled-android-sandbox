# M4-T16 与 VirtualApp／NewBlackbox 对比报告

## 对比范围

本报告只比较 M4-T16 的 PendingIntent、Alarm、Notification 和 JobScheduler 增量。Controlled Sandbox 的结论来自源码、静态 Android 编译和 Host self-test。VA/NBB 仅作为成熟实现参照，不把其 README 或历史声称当作当前 Android 设备证据。

## 本迭代新增能力

- 持久、Revision 绑定的 PendingIntent Token 和完整发送语义。
- Exact/Repeating Alarm、离线保留和持久 PendingIntent 恢复投递。
- Notification Channel/Group、FGS 映射和交互 PendingIntent 生命周期。
- Typed Job 约束、周期、Latency、Deadline、Expedited、Persisted 和失败退避。
- Package Service schema、容量限制、原子回滚、Generation 恢复和 Revision 清理。

## 能力对比

| 能力 | Controlled Sandbox M4-T16 | VirtualApp | NewBlackbox | 当前差距 |
|---|---|---|---|---|
| PendingIntent 身份 | Package/user/generation/revision 绑定的持久 Token；Creator UID/Package 虚拟化 | 成熟虚拟 AMS/IntentSender 路由，分支差异较大 | 较完整现代 IntentSender Hook | Android 版本签名和真实系统回调未验证 |
| Mutable/FillIn/ClipData | typed 模型、权限校验和 fail-closed 路由 | 长期兼容积累 | 较广接口覆盖 | 复杂平台边界与 OEM 行为缺少设备证据 |
| Alarm 持久化 | Package Service 持久 exact/repeating 状态；Guest 离线保留 | 成熟 AlarmManager 虚拟化，版本依赖 | 较广 Alarm Hook | Wakeup、Doze、配额、重启和省电模式未验证 |
| Alarm PendingIntent 恢复 | 可在旧 Binder 消失后按持久 Token 重新物化 | 通常与虚拟 IntentSender 联动 | 通常与 Binder Hook 联动 | 真实系统进程重启顺序没有设备证据 |
| Notification 资源隔离 | scoped ID、Channel/Group、FGS、Content/Delete/Action Sender | 成熟通知重写和资源映射 | 广泛通知代理 | SystemUI、Listener、通知权限和 OEM Channel 字段未验证 |
| 安全 cancelAll | 枚举并取消当前 Guest 拥有的 Host ID | 成熟隔离模型 | 成熟隔离模型 | 设备回调顺序未验证 |
| Job ID 和服务重写 | 持久 Guest/Host ID；Host JobService 桥到 Guest JobService | 成熟 JobScheduler 代理，版本依赖 | 较广 JobScheduler Hook | API/OEM Binder 变体未验证 |
| Job 约束策略 | typed 网络/充电/电量/存储/空闲、周期、Latency、Deadline、Expedited、Persisted | 通常保留平台 JobInfo 并代理 | 通常保留并重写 JobInfo | 复杂 NetworkRequest 和配额策略仍依赖 Host |
| Job 重试 | Package Service 持久线性/指数退避、失败次数和 next-run | 成熟平台语义积累 | 实现随分支变化 | 真实 JobScheduler 时间和配额未验证 |
| APK 更新清理 | 四类资源均按 Revision 清理 | 成熟包生命周期集成 | 成熟包生命周期集成 | 设备更新、进程并发和回调竞态未验证 |
| 设备证据 | 0% | 有长期使用历史，但当前版本/分支需单独核验 | 项目和分支差异显著 | Controlled Sandbox 仍缺正式 Android 构建和模拟器证据 |

## 证据判断

M4-T16 已把四类系统调度资源从基础 ID 重写推进到 typed、持久和 Revision-aware 的源码能力。PendingIntent、Alarm、Notification 和 Job 的资源所有权都能由 Package Service 独立查询和清理，失败写入会回滚，Guest 进程重启不再自动丢失持久状态。

VA/NBB 仍具有更强的 Android 版本适配、隐藏 Binder 签名覆盖、系统进程交互和设备兼容积累。Controlled Sandbox 当前不能称为达到 VA/NBB 的运行时水平。更准确的判断是：M4-T16 在源码模型和生产接线广度上缩小了中段差距，但设备证据差距没有缩小。

## 当前项目实际完成度

- 能力条目：102。
- 源码：98 complete、4 partial，权重 98.0%。
- 生产接线：94 wired、6 partial、1 blocked、1 n/a，权重 96.0%。
- 设备：0 verified，权重 0.0%。

这些数字是仓库证据统计，不是 APK 兼容率。

## 未完成项

1. Alarm wakeup、Doze、配额、重启和 OEM 定时行为。
2. Notification SystemUI、Listener、权限和 FGS deadline 的设备路径。
3. JobScheduler Standby Bucket、quota、复杂 NetworkRequest 和系统重启持久 Job。
4. Android API 版本和 OEM Binder 签名矩阵。
5. 真机/模拟器上的 Binder 顺序、进程死亡和资源清理竞态。
6. 第三方 App 的实际兼容性和稳定性。

## 下一阶段优先级

按冻结路线进入 M4-T17 Native Hook 与 ABI 架构。优先完成 Native 文件系统、动态库、ABI/跨位宽进程、网络身份和录音路径的源码架构；设备测试仍留到 M4-T18 正式源码收口之后。
