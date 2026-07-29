# M4-T16 B1 开发报告：PendingIntent 完整生命周期

## 基线与提交

- 正式起点：`f1e683293dbe9a2d1c3a03ef573996476afafda2`（M4-T15）
- 开发计划提交：`b93ca096ac7c3c93e0812fac12f74903080ed0dd`
- B1 功能提交：`7a762e642c92641c1c80bfd779236c49df380d89`
- 开发分支：`feature/m4-t16-system-scheduling`

## 冻结范围

本批次仅覆盖 M4-T16 的 PendingIntent 子域：

- Activity Result 类型。
- Mutable／Immutable。
- FillIn Intent 与 flags mask/value。
- ClipData 传递。
- Sender 权限。
- Creator Package、Creator UID 虚拟化。
- PendingIntent 相等性。
- 跨进程恢复。
- 持久化 Token。
- APK Revision 和实例状态清理边界。

Alarm、Notification 和 JobScheduler 留在 B2、B3，不在本批次扩大范围。

## 实现结果

### Typed Binder 契约

新增 `VirtualPendingIntentSnapshot` Parcelable/AIDL，持久字段包括：

- 稳定 Token ID。
- 类型、Request Code。
- Intent filter identity。
- Creator Package、虚拟 Creator UID。
- Sender 权限。
- Owner Process、Generation。
- APK Revision。
- 有界 Parcelable payload。
- 发送次数和取消状态。

`IPackageService.openVirtualSystemServiceSession` 增加可信 `virtualUid` 和 `packageRevision` 绑定。Package Service 会拒绝伪造 Creator UID。

### 生命周期和身份

`VirtualPendingIntentRegistry` 已支持：

- `FLAG_NO_CREATE`。
- `FLAG_CANCEL_CURRENT`。
- `FLAG_UPDATE_CURRENT`。
- `FLAG_ONE_SHOT`。
- `FLAG_IMMUTABLE`。
- `FLAG_MUTABLE`。
- 持久 Token 跨 Guest Generation 重绑。
- 进程关闭时仅释放本地 Binder handle，不删除持久 Sender。
- 显式 cancel 和 cancelAll。
- APK Revision 更新后清理旧 Sender。

持久化端口位于 routing 边界，Runtime Adapter 负责连接 Binder 状态，未引入 Framework 内部包反向依赖。

### Intent 语义

PendingIntent 相等键包含：

- Sender 类型。
- Request Code。
- Action。
- Data URI。
- MIME Type。
- Explicit Package。
- Component。
- Categories。
- Intent Identifier（可用时）。

Extras 不参与相等判断。Mutable Sender 支持 FillIn Intent、ClipData 和 flags mask/value；Immutable Sender 拒绝任何可变填充。

### Activity Result PendingIntent

新增 `ActivityResultRequest.SEND`：

- 绑定虚拟 Activity token。
- 保留 Result Who。
- 保留 Request Code 和 Result Code。
- 使用 typed Result Intent。
- 经过 Runtime Broker 的 Activity Result Ledger 投递。
- 写 checkpoint 失败时恢复 Ledger 变更前状态。

## 测试结果

通过：

- Typed AIDL 和 Parcelable 往返。
- Creator Package／虚拟 UID 绑定。
- PendingIntent 等价与差异测试。
- Extras 不影响 equality。
- MIME Type 参与 equality。
- Mutable／Immutable。
- FillIn Intent、ClipData、flags mask/value。
- Sender 权限拒绝。
- NO_CREATE／UPDATE_CURRENT／CANCEL_CURRENT／ONE_SHOT。
- Activity Result PendingIntent。
- Guest 进程重建后持久 Token 重新绑定。
- Package Service 重建后的状态恢复。
- APK Revision 更新清理。
- Runtime／Framework 包边界。
- M4-T14 Service 和 M4-T15 Activity/Task 回归。
- 静态 Android 源码编译和全部 Host self-test。
- Native self-test。
- M3 严格证据门禁。
- 双次可复现源码 ZIP 字节比较。
- Shell、Python、PowerShell 静态检查。

统一 `verify-all.sh` 在执行完能力矩阵后受到单次执行时限终止，终止前无失败；剩余门禁从静态 Android 编译开始按原顺序续跑并全部通过。

## 限制

- 尚未执行 Android 模拟器或真机验证。
- 实际 Android 版本的隐藏 AMS/ATMS 签名仍需设备阶段验证。
- ClipData URI grant 的真实宿主授权时序尚未设备验证。
- Sender 权限目前以 Broker 接收到的权限上下文进行 fail-closed 校验，Binder 调用链中的平台 UID 映射仍需设备验证。

## 下一批次

M4-T16 B2：Alarm 与 Notification 深化。

- Alarm 持久调度、离线保留、Runtime 恢复、Exact／Repeating、Listener／PendingIntent 双路径、Revision／实例清理。
- Notification Channel／Group 生命周期、cancelAll、FGS 映射、点击／删除／Action PendingIntent、状态恢复。
