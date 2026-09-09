# P1-12 调度与 PendingIntent 验收报告

**状态：** 已完成（2026-09-09；API35/36 `FIXTURE_PASS`）

## 决策与修复

修改前已按 [P1-12 参考源码决策单](P1-12_REFERENCE_DECISION.md) 核对 NBB 的
`ActiveServices`、`IJobServiceProxy`、`INotificationManagerProxy` 与 VA 的 Service
解析、进程 client 附着路径。参考实现均将调度/通知的宿主边界与虚拟身份边界分开；
它们不构成把宿主的通知授权借给 Guest 的依据。

历史能力矩阵的 `CAP-SCHEDULING-NOTIFICATION-ALARM-JOB-FGS` 缺 marker 结论保留为
FAIL。API35 的首次复现证明：Guest 已拒绝 `POST_NOTIFICATIONS`，却仍因宿主身份产生
active notification。修复在 `VirtualSystemServiceInterceptor` 的普通通知入队路径先检查
Guest 的有效权限，拒绝时既不调用宿主通知服务，也不写入虚拟通知状态；前台服务的必需
通知仍走其独立路径。没有扩大宿主权限，也没有伪造回调。

`C2T05SchedulingInteractionActivity` 同时改为等待 `FixtureService.onDestroy` 写出的
停止回执后才声明 FGS stop PASS，避免把 `stopService()` 的请求返回值当作实际停止。
`DebugCommandActivity` 接受已有的 `PREPARED_DEGRADED` 准备状态，消除仅存在于调试
命令白名单中的非阻塞拒绝。静态自测把普通通知命名空间用例显式设为已授予权限，并以
独立用例覆盖拒绝权限不得借用宿主授权。

## 定向验收

- API35 会话 `07c24ba9-0411-4655-b3a9-38d63dfb5bcf`、API36 会话
  `df65752b-b6cf-458a-a6c0-cd774a94f988`：各连续 5 轮均记录真实 Alarm、
  `GUEST_JOB_STARTED`/`JOB_FINISHED_RECEIVED`、FGS promoted 与 stopped 回执；两个
  campaign 均为 `C2_T05_CAMPAIGN_PASS loops=5`。
- 两个会话均记录通知 click/delete 的 PendingIntent 回调；系统持有者的 `arm → clear`
  后未出现旧 delivery。该结论是 Broker/PendingIntent 生命周期的短场景验证，不把
  host force-stop 等同于 OEM/Doze 行为。
- 受影响的 S05、S08、S10 已重新执行。首次 S08 因 `fixture-compat32` 未安装而失败，
  原回执完整保留；安装其既有 fixture 后，S08 重跑 PASS。S05、S10 均 PASS。
- `python tools/static_android_compile.py` 最终退出码为 0，末尾含
  `PASS virtual PendingIntent identity and lifecycle self-test` 与 Binder interception
  foundation PASS；日志见下方。

## 证据

- [参考源码决策单](P1-12_REFERENCE_DECISION.md)
- [静态 Android 编译日志](../../out/verification/p1-12-static-android-compile-20260909.log)
- [S05/S08/S10 首次回执（保留 S08 前置缺失）](../../out/verification/p1-12-api35-s05-s08-s10-20260909/run.json)
- [S08 安装 fixture-compat32 后重跑回执](../../out/verification/p1-12-api35-s08-retry-after-fixture32-20260909/run.json)
- [历史 capability FAIL 矩阵](../../out/verification/realapp-compat-01-capability-final-20260907/capability-matrix.json)

## 未覆盖范围

熄屏 Doze、HyperOS 策略与小米真机仍属于 P2-08/P2 阶段；大规模回归和 8 小时稳定性
测试按任务书留在最后，均未提前运行。
