# B3-T4D Receiver 综合一致性复审

## 目标

将动态 Receiver、Manifest Receiver 索引与进程绑定、隐式 Action Index、Ordered Receiver Token 的跨注册表生命周期收口到单一 Broker 协调器，避免 Session、generation、实例和 Broker 销毁路径各自清理不同资源。

## 统一资源模型

`ReceiverLifecycleCoordinator` 管理：

- 动态 Receiver 注册与 Action 订阅；
- Manifest 包索引、Receiver 声明、Action Index 和启动模板；
- Manifest Receiver 的 Session/generation 进程绑定；
- Ordered Receiver 等待 Token。

静态 Manifest 声明属于虚拟 App 实例；动态注册、进程绑定和 Ordered Token 属于具体 Session/generation。

## 生命周期语义

| 场景 | 动态注册 | Manifest 静态索引 | 进程绑定 | Ordered Token |
|---|---|---|---|---|
| Guest 可恢复断开 | 删除 | 保留 | 删除 | 取消 |
| Guest 终止断开 | 删除 | 保留 | 删除 | 取消 |
| generation 恢复成功 | 不恢复 | 保留 | 绑定新 generation | 旧 Token 取消 |
| Session 显式停止 | 删除 | 保留 | 删除 | 取消 |
| 实例停止/卸载 | 删除 | 删除 | 删除 | 取消 |
| Broker 销毁 | 删除 | 删除 | 删除 | 全部取消 |
| Token 到期 | 不变 | 不变 | 不变 | 超时终止 |

动态 Receiver 与 Android 进程生命周期一致，进程死亡后不会被自动重建。Manifest Receiver 的静态声明继续存在，并在新 Guest Session READY 后重新绑定对应进程。

## 一致性快照

统一 Snapshot 同时返回：

- 动态注册数；
- 动态 Action 订阅数；
- Manifest 包、Receiver、进程绑定数；
- Action Index Key 与 Entry 数；
- 启动模板数；
- Ordered Pending Token 数；
- Receiver 总资源数。

Runtime Status 使用同一次 Receiver Snapshot，不再分别读取三个 Registry，避免跨时间点状态。

## 修复事项

1. `RuntimeBrokerService` 不再直接执行 Receiver 跨注册表清理。
2. 实例停止能够清理残留动态注册，即使 Session 列表已经不完整。
3. Manifest Session Binding 不允许绑定未索引包或不承载 Receiver 的进程。
4. Broker 销毁会同时清除 Manifest 静态索引和启动模板。
5. Ordered Token 过期进入统一维护入口。
6. Dynamic Receiver Registration 增加 packageName，使实例级清理可验证。
7. Runtime Status 增加 Action Index、启动模板、Ordered Token 和总资源计数。

## 验证边界

本阶段验证的是纯 Java Registry、Broker 接线、并发清理和制品复现。真实 Android 广播注册、AMS 分发、进程死亡、隐藏 API、Binder、后台限制和 OEM 行为继续记录为 `not-tested`。
