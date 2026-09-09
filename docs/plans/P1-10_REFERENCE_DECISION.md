# P1-10 参考实现与载荷边界决策

日期：2026-09-09；前置：P1-09 已完成。

## 已核对的执行方法

| 参考实现 | 方法与实际边界 | CAS 对应点 | 采用的约束 |
|---|---|---|---|
| NewBlackbox | `ActivityStack.startActivityLocked` 决定 task/source record；`startActivityInNewTaskLocked` 与 `startActivityInSourceTask` 各创建 `ActivityRecord` 后才构造 Stub shadow Intent；`realStartActivityLocked` 只将该 shadow Intent 交给真实 AMS。 | `RuntimeActivityLaunchCoordinator.launch` 在 Broker Binder 边界后 materialize；`ActivityRuntimeRouteCoordinator.launch/consume` 将完整 bytes 放入 `OneTimeRouteStore`，而 Host envelope 去除大 payload。 | 完整 Intent 只能有一个 broker-owned authoritative copy；宿主 Stub 不能携带第二个 extras Bundle。 |
| VirtualApp OSS | `ActivityStack.startActivityLocked` 先根据 `TaskRecord`/复用规则决定目标；`startActivityProcess` 生成代理 Intent；`startActivityFromSourceTask`/`realStartActivityLocked` 再透传 `resultWho/requestCode/options`。 | `GuestIntentResolver.activityRequest` 调 `RuntimeIntentWireCodec.encodeActivity`；Broker route store 保存 one-time payload，consume 时按 owner/generation 取回。 | task 路由与 payload ownership 都必须在服务端有界；跨用户、跨代或重复 token 不得回放。 |

## CAS 当前实现与结论

1. `RuntimeIntentWireCodec.encodeActivity` 将完整 `Intent` Parcel 编码；小于等于 256 KiB 内联，较大载荷经 `ParcelFileDescriptor` 传输，最大为 1 MiB。wire payload 成功时不会再写 `INTENT_EXTRAS`，正是 KI-R03-057 所述重复 extras Binder 复制的修复边界。
2. `RuntimeActivityLaunchCoordinator.launch` 只在 Broker Binder 接收后调用 `materializePayloadForBroker`，不会把 byte[] 回显给 `RuntimeOperationResult`。
3. `ActivityRuntimeRouteCoordinator` 将 payload 交给 `OneTimeRouteStore`（1 MiB、30 秒），从 broker-side envelope 中 `stripRoutePayload`；consume 只对匹配 `RouteOwner(user, package, process, generation)` 的 token 创建受限 FD。`BrokerStateStore` 保存的是已剥离的控制 envelope，512 KiB 限制不承载完整 Intent。
4. `OneTimeRouteStore.consume` 为原子一次性消费，owner/kind 不匹配抛出安全错误，过期/重复消费返回缺失；`revokeOwner/revokeStaleGenerations` 清理该 generation 的未消费 payload。

## 本项验证与修改规则

- 先以 270/283/308 KiB 历史量级、256 KiB 内联阈值两侧、1 MiB 上限两侧验证真实 round-trip；内容摘要、Parcelable、FD 数量和 route token 必须可关联。
- 明确断言超限、错误 classloader、跨用户/跨代/重复 token、过期 token 均为失败；禁止静默截断、延长 deadline 或重试。
- 仅当当前源码或当前 AVD 复现不满足上述合同，才修改产品代码；若需新增 fixture/runner，只作为 package-neutral 观测，不改变运行时策略。
