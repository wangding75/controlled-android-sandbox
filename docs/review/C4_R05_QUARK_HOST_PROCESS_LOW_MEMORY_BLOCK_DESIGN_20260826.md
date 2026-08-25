# C4-R05 Quark 宿主低内存阻断分类与恢复设计

日期：2026-08-26
任务：`C4-R05`
关联问题：`KI-R03-058`
状态：`BLOCKED`，本次观察未执行自动重试

## 1. 首发观察

本机 `RD测试` 的 user0 剩余启动矩阵按 R03 fail-fast runner 执行。fixture 已完成
50/50；Quark 的 `cold-001`、`hot-001`、`cold-002`、`hot-002` 通过后，首个失败发生在
`quark/user-0/cold-003`。该请求的 `requestId` 为
`4647d04f6e5540b2a4c74c313331f311`，attempt=1，retryBudget=0，
`automaticRetryPerformed=false`。

权威 runner 结果是 `LAUNCH_RESULT_NOT_PASS`，因为没有取得同 requestId 的生产
`operation`。`debug-command-result.json` 采集结果为 0 字节，runner 以
`RD_ENVIRONMENT_RESOLUTION_BLOCKED: debug-command-result timeout` 结束并停在首发失败处。

## 2. 证据分类

失败快照同时记录了 `com.quark.browser/com.ucpro.BrowserActivity` 的 focused Window、
非空 Surface、非黑截图和空 FATAL/ANR marker；因此不能把它归类为“Quark 没有启动”，
也不能把截图或 Window 观察升级为 PASS。设备 `ApplicationExitInfo` 进一步显示，启动请求
期间被杀的是 CAS 宿主 `com.warden.controlledsandbox.debug` pid 17929，原因是
`LOW_MEMORY`（PSS 约 120 MB、RSS 约 201 MB）。这与 `KI-R03-058` 的 CAS 进程 owner /
MuMu 低内存边界相符；本条不新增 Quark 专属生产修复结论。

完整机器证据：

- `verification/catch-up/C4-R05/continuation-local-launch-user0-remaining-20260826/c4-r03-summary.json`
- `verification/catch-up/C4-R05/continuation-local-launch-user0-remaining-20260826/attempts/quark/user-0/cold-003/case.json`
- `verification/catch-up/C4-R05/continuation-local-launch-user0-remaining-20260826/attempts/quark/user-0/cold-003/first-failure-full/`
- 结构化分类：`verification/catch-up/C4-R05/continuation-local-launch-user0-remaining-20260826/local-launch-failure-receipt.json`

## 3. 恢复门槛

在恢复前不得继续 Hongguo/Fanqie，也不得从 Quark `cold-003` 自动续跑。后续恢复必须满足：

1. 重启或明确恢复 `RD测试` 宿主/Guest 进程 owner 边界，并保留 `ApplicationExitInfo`、内存和
   host result 文件证据；
2. 使用新的 requestId、独立目录和显式 `--resume-of` 做一次手动续接，不能覆盖本次首失败；
3. 续接仍要求 request/operation/package/user 关联、完整 readiness 状态机、Window、Surface、
   非黑首帧和 retry=0；任何新失败再次停止；
4. 即使恢复观察通过，也只能作为独立观察，不能抹除本次首发阻断或直接关闭 `KI-R03-058`。

本设计不放宽生产 cold/hot SLO，不增加隐式重试、固定 sleep、deadline 延长或 Quark 包名分支。
在该恢复门槛满足前，C4-R05 和 C4 阶段均不得标记 DONE。
