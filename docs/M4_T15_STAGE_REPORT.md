# M4-T15 Activity 与 Task 虚拟化补强阶段报告

## 1. 阶段结论

**PASS — SOURCE/HOST VERIFIED**

M4-T15 已按 B1、B2、B3 三个批次完成源码开发与 Host 验证，并形成可进入 M4-T16 的正式源码基线。

设备测试尚未开始。模拟器/真机完成度仍为 0，不能把源码证据换算为 APK 启动率或稳定运行率。

## 2. 三批次交付

| 批次 | 结果 | 主要交付 |
|---|---|---|
| B1 | PASS | 五种 LaunchMode、Intent Flag 组合、Document Mode、finish/move Task、APK Revision 清理、checkpoint 兼容 |
| B2 | PASS | typed Result Intent、Result Who/Request Code/Registry Key、Intent Sender Result、Configuration 重建、schema 3 恢复、事务回滚 |
| B3 | PASS | Running/Recent/AppTask Framework 入口、Task 操作入口、framework token 映射、Android 投影层、禁止宿主回落 |

## 3. 冻结范围完成情况

### 3.1 LaunchMode

- `standard`
- `singleTop`
- `singleTask`
- `singleInstance`
- `singleInstancePerTask`

五种模式已经进入统一 Task ledger 和专项矩阵测试。真实 Android/OEM 回调顺序仍待设备验证。

### 3.2 Intent Flags

已实现并测试：

- `FLAG_ACTIVITY_NEW_TASK`
- `FLAG_ACTIVITY_CLEAR_TOP`
- `FLAG_ACTIVITY_CLEAR_TASK`
- `FLAG_ACTIVITY_NEW_DOCUMENT`
- `FLAG_ACTIVITY_MULTIPLE_TASK`
- `FLAG_ACTIVITY_REORDER_TO_FRONT`
- `FLAG_ACTIVITY_NO_HISTORY`
- `FLAG_ACTIVITY_FORWARD_RESULT`

非法组合会在进入 Broker 状态前拒绝。

### 3.3 Result 链路

已实现：

- `startActivityForResult` 的 Broker 所有权；
- Result Who；
- Request Code；
- Result Code；
- 有界 typed Result Intent；
- Activity Result registry key；
- Intent Sender Activity Result token；
- Configuration 重建和进程恢复后的 Result owner 迁移；
- Guest `onActivityResult` 投递入口。

一次性 route、Binder 传输权和已失效结果投递不会在 Broker 重启后复活。

### 3.4 Task 状态与恢复

已实现：

- 虚拟 Task ID；
- Affinity；
- Document Mode 和 Document Key；
- Task 栈和 Saved State checkpoint；
- Activity 销毁、重建和 Configuration Change；
- Process Generation 恢复；
- APK Revision 更新后的旧 Task 清理；
- 实例删除清理；
- `finishAffinity`；
- `finishAndRemoveTask`；
- `moveTaskToBack`；
- Running/Recent Task typed 查询；
- ActivityManager/ActivityTaskManager Framework 投影；
- 本地 IAppTask Binder。

Checkpoint 使用版本、CRC、容量限制、原子替换和损坏隔离。写入失败时恢复精确内存状态。

## 4. 能力矩阵

阶段完成后的仓库证据矩阵：

| 维度 | 结果 |
|---|---:|
| 能力条目 | 96 |
| 源码 complete | 92 |
| 源码 partial | 4 |
| 源码加权完成度 | 97.9% |
| 生产接线 wired | 88 |
| 生产接线 partial | 6 |
| 生产接线加权完成度 | 95.8% |
| 设备 verified | 0 |
| 设备证据完成度 | 0.0% |

这些统计描述仓库证据，不描述真实应用兼容率。

## 5. 与 VA/NBB 的对比

| 能力 | 当前项目 | VA/NBB 参照水平 | 判断 |
|---|---|---|---|
| LaunchMode/Flag 策略 | Broker ledger 已覆盖主要组合并有 Host 测试 | 长期经过 Android 版本与应用样本验证 | 源码广度接近中段，设备可信度明显落后 |
| Result 链路 | typed、持久化、事务回滚和 owner 迁移 | 具备成熟 Framework/Instrumentation 接入 | 状态模型较完整，真实回调兼容仍不足 |
| Running/Recent Task | Broker 隔离查询并投影 Android 对象 | 多版本 AMS/ATMS 适配更成熟 | 已补关键源码缺口，系统 Recents 仍有差距 |
| AppTask | 本地 IAppTask Binder，支持查询、前移和移除 | 通常与完整 AMS/ATMS/Window 栈协同 | 入口已存在，版本与窗口系统适配不足 |
| 恢复与一致性 | CRC checkpoint、容量限制、损坏隔离、精确回滚 | 各项目实现方式不同，设备经验更丰富 | 源码安全边界明确，但缺少设备压力证据 |
| 多窗口/转场 | 未完成 | VA/NBB 有更多历史适配 | 明显落后 |
| 第三方 APK 兼容 | 未测试 | 有历史设备运行积累 | 无法比较启动率与稳定性 |

M4-T15 完成后，Activity/Task 的源码能力广度可以称为接近 VA/NBB 的中段范围。由于设备证据为 0，不能称为达到 VA/NBB 的实际兼容水平。

## 6. 代码质量判断

支持点：

- Framework 入口、Broker client、Android 投影和 Task ledger 职责分离；
- 新增跨进程业务契约继续 typed 化；
- Task 查询和操作绑定完整 Guest 身份；
- 禁止宿主查询回落；
- 持久化变更具备回滚；
- M4-T14 Service 回归门禁继续通过。

剩余风险：

- `ActivityTaskLedger` 仍约 1,857 行，是下一次总收口需要拆分的主要 God Class；
- `RuntimeBrokerService` 保持 1,370 行，仍高于 M4-T14 原始“低于 1,100 行”愿景；
- Framework 隐藏 API 的反射投影只有 Host stub 证据；
- host mirror 失败后缺少设备级恢复策略；
- Window、Transition、TaskFragment、PiP 和多 Display 尚未进入模型。

## 7. 后续迭代

下一阶段严格进入冻结计划的 **M4-T16：系统调度与通知深化**，不再继续扩展 M4-T15 范围。

主要任务：

- PendingIntent mutable/immutable、FillIn Intent、ClipData、Creator 身份、持久 Token 和跨进程恢复；
- Alarm 持久调度、Guest 离线保留、Runtime 恢复投递、Exact/Repeating 和两类回调路径；
- Notification Channel/Group 生命周期、点击/删除/Action PendingIntent、Foreground Service Notification 映射和状态恢复；
- JobScheduler 约束 DTO、Periodic/Latency/Deadline/Expedited、持久化、重试与退避。

M4-T16 完成后继续 M4-T17 Native Hook 与 ABI 架构，最后由 M4-T18 做设备测试前源码总收口。

## 8. 仍不确定的点

- API 26～36 的隐藏 Binder 签名是否全部与当前审计覆盖一致；
- OEM 对 TaskInfo、Recents、IAppTask 和 Activity token 的修改；
- 系统 Recents UI、缩略图与转场的真实表现；
- 进程强杀、低内存、升级和存储故障组合下的恢复顺序；
- 第三方 APK 的真实启动率和 20 分钟稳定性。
