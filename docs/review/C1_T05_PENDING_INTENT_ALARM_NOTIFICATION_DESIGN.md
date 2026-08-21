# C1-T05 PendingIntent、Alarm、Notification 系统持有者设计

## 目标与边界

本批次只关闭 `C1-T05`：跨进程、延迟触发、Guest stub 死亡/重连期间，PendingIntent 的
creator identity、virtual user、package revision 和 Broker route 不漂移。验收覆盖
activity/service/broadcast sender、mutable/immutable、update/cancel、AlarmManager 以及
NotificationManager 的持有和清理路径。当前任务书 1.1 已移除显式八小时 soak；本任务仍执行
50 轮、30 分钟压力（若 runner 参数启用）和清理收敛检查。

## DISCOVER / CLASSIFY 事实

- `RD测试` 每次由 `scripts/mumu_instance.py` 按实例名解析；本机基线为 API 32，不能在 runner
  中固化 ADB serial。
- `VirtualPendingIntentRegistry` 和 `VirtualSystemServiceStore` 已有持久 token、creator UID、
  generation、package revision、mutable/immutable flags、update/cancel 和 stale token fencing。
- 现有系统持有方路径在 `PendingIntentFrameworkInterceptor` 创建 Broker sender 后，尝试改写
  `getIntentSender` 参数为 Host `RuntimePendingIntentRelayReceiver`。
- 2026-08-21 的 pre-fix RD 证据出现 `PENDING_INTENT_BROKER_RELAY_CREATED`，但没有
  `SYSTEM_HOLDER_RELAY`，随后 ActivityManager 直接尝试 Guest action 并报后台启动权限拒绝。
  该事实登记为 `KI-R03-033`，在设备修复验证前不能宣称 C1-T05 完成。
- `check-alarm-notification-lifecycle.py` 仍引用已迁移的 `VirtualSystemServiceInterceptor` 位置，
  属于静态检查器漂移，不改变运行时结果。

## 最小修复批次

1. 让系统持有方使用唯一的、显式指向 Host relay receiver 的 Intent；保留原始 Guest action
   作为 AMS callback-visible action，原始 data/extras 和目标组件只由虚拟 token 在 Broker/Guest
   侧恢复，不能交给 AMS 直接解析。
2. 对 `Intent`、`Intent[]` 及反射包装产生的数组统一改写，并记录 token、Host component、原始
   action 的审计 marker；如果没有可改写的 Intent，不得静默声称 host-held。
3. 将静态 Alarm/Notification 检查器的证据位置更新到当前实现，增加 route/revision/stale
   清理的静态契约检查。
4. 增加 C1-T05 RD runner 和 package-neutral fixture：双虚拟用户、sender 类型/flag、update/
   cancel、Alarm 延迟回调、Notification content/delete 路径、Guest death/rebind、旧 revision
   拒绝和最终 ledger/notification 清理。

## 验收判定

必须同时满足：静态治理检查通过；双用户每用户至少 50 个闭环；至少一次 Guest death 后系统
回调由 `SYSTEM_HOLDER_RELAY` 到 Broker，再回到正确 user/revision；旧 token 被拒绝；
Alarm/Notification clear/cancel/delete 后无残留；30 分钟压力如 runner 启用则记录实际时长。
失败必须先修复重跑；仅在需要人工介入的外部条件下记录 BLOCKED。
