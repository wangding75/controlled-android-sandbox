# C1-GATE 全回归失败分类

## 结论

本次失败分类为 `RUNTIME_DEFECT`：显式框架有序广播通过独立的缓存线程池并发提交，两个合法的连续
`Context.sendOrderedBroadcast` 请求不能保证 FIFO。设备一次完成第一个请求、另一次完成第二个请求，
另一个请求在 `FRAMEWORK_RECEIVER_TIMEOUT` 后回调，说明不是单纯的 logcat 采集缺失。

## 复现证据

| 尝试 | 设备时序 | 结果 |
|---|---|---|
| stage attempt-01 | `12:41:14` 启动 `FrameworkProbeActivity`；`12:41:20.594` 异步有序 Receiver 到达；`12:41:50.653` 首个有序请求 `FRAMEWORK_RECEIVER_TIMEOUT`，随后 `FRAMEWORK_ORDERED_RECEIVER_RESULT_MISMATCH` / `FATAL EXCEPTION` | 首个 ordered marker 缺失 |
| targeted retry-01 | `12:49:23.257` 启动；`12:49:29.504` 普通有序 Receiver 到达并 PASS；`12:49:59.557` 异步请求 `FRAMEWORK_RECEIVER_TIMEOUT`，随后 `FATAL EXCEPTION` | 异步 ordered marker 缺失 |
| targeted retry-02（修复后） | `12:54:28.447` 启动；`12:54:34.633` 普通有序 Receiver 到达；`12:54:34.672` 异步 Receiver 到达；两组 framework PASS 均出现 | runtime FIFO 修复后的设备 marker 已齐，但旧的外部动态广播命令在 Activity 已完成后卡住，随后由操作者终止该次探针进程；该命令级问题另行以 `--receiver-foreground` 修复 |

两次请求的完成者互换，且均发生在同一个 Activity 的连续调用中；设备解析仍为 `RD测试`、API 32、
同一 boot id。Stage transcript 为 `c1-gate-run.txt`；完整回归在该用例未写出 PASS 结果文件，
因此本分类保留时间戳、错误类型与设备 logcat 结论，不把失败伪装成 PASS。

## 修复方向

在 `GuestContextComponentRouter` 为显式框架有序广播增加 per-Guest 单线程 FIFO 队列。请求继续在
Guest 主线程之外等待 Broker/ActivityThread 回调，避免自等待；队列在 Guest teardown 时关闭。
