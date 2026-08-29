# C4-TEMP-01 首次失败分类记录

- 任务：`C4-TEMP-01`
- 最终状态：`BLOCKED`
- 运行时间：2026-08-29（Asia/Shanghai）；动态运行目录 `quark-latency/20260829T224349/`
- clean baseline：`54d48c5cfa85602299531b5215cfa0c9c352fdc3`
- RD：动态解析 MuMu `RD测试`；API 32、ABI `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`、型号
  `22041211A`、boot ID `bd6fc459-0d52-4689-868a-420364ea407c`、Android ID
  `398eea33120cd887`。运行时 serial 仅保存在环境快照中，未进入脚本常量或分支。
- 动态样本：`com.quark.browser`，version `10.10.5.1080`/code `1080`，组件
  `com.quark.browser/com.ucpro.MainActivity`，base 1、split 0、primary ABI `arm64-v8a`；
  discovery 记录见 `quark-latency/20260829T224349/quark-discovery.json`。

## 首次失败签名

本记录的权威失败是第二条独立验证链中的沙箱 sample-01；它不是生产 launch 的自动重试。此前
`20260829T223756` 的失败已经原样保留；该链在故障后观测到直接启动残留，随后修正了 benchmark 的
目标清理、Host/Guest Window 归属和 fail-fast 观测边界，再独立执行本链。

- request：`bdb03fe6b78d4323ab3b1adcf170be36`
- operation：`bdb03fe6b78d4323ab3b1adcf170be36-launch`
- session：`eb8a4856-f830-4b3a-ad14-79a82f0496ef`
- attempt：`1`；retry budget：`0`；automatic retry：`false`；retry decision：`NO_RETRY`
- import-only：PASS；其事务 trace 为 `SUCCEEDED`，attempt 1、retryable `false`；import trace
  `elapsedMs=17374`，`HASH=682ms`、`MANIFEST_PARSE=54ms`、`PACKAGE_INFO=1064ms`、
  `EXISTING_REVISION_VERIFY=2517ms`、`PUBLISHED_REVISION_VERIFY=3556ms`、`CATALOG=3387ms`。
- 沙箱 launch：进程返回码 1，case elapsed `88953ms`；结构化结果为
  `status=FAIL / LAUNCH_FAILED / LAUNCH_GATE_FAILED`，错误为
  `guest Activity create/resume/window not confirmed`；没有可接受的 launch timeline 或
  `FIRST_FRAME_DRAWN`，`requiredStagesPresent=false`。
- 原始 logcat 关键行位于 `first-failure-full/snapshot/logcat.txt`：
  `08-29 22:45:46.486 ... GUEST_LAUNCH_READINESS status=LAUNCH_FAILED ...`
  （含 package、session、request、operation、route token、StubActivity、task、generation、slot）；
  紧随其后的 `22:45:46.608` 为 `CS_COMMAND: FAIL launch` 及同一异常。

## 失败时的真实显示边界

失败后只做一次零等待的边界快照，不重新发起 launch：

- Activity：Host `StubActivity60W1` 为 top-resumed，但 Guest 状态为
  `resumed_guest_stub_count=1`、`reported_drawn=false`、`has_visible=false`、`drawn=false`。
- Window：目标 Host-owned inner Window 存在，但 `mHasSurface=true` 不能单独作为显示成功；其
  `Surface: shown=false`、`mDrawState=DRAW_PENDING`，`readyForDisplay=false`、`visible=false`。
- Surface：系统列表非空，但没有通过目标 Guest Window identity 的显示证明；不是用系统 Surface
  列表替代目标首帧。
- Screenshot：`1080x1920`、`bytes=12504`、SHA-256
  `4afb293f262964138b1d2e2a08733ad4d4216150e508b9f62e4610e47e0cb930`，
  `nonBlack=false`、`uniform=true`。
- 目标清理：沙箱前 `am force-stop com.quark.browser` 返回码 0；没有用直接启动残留覆盖沙箱结果。

对照链中直接启动 3/3 达到动态 Activity/Window/Surface/非黑帧条件，耗时分别为
`6937ms`、`6147ms`、`7582ms`；因此不能用直接路径 PASS 推导沙箱 PASS，也不能计算有意义的
`sandbox/direct` 比值来绕过沙箱硬门禁。

## 分类与根因边界

当前可证实的分类为：
`CAS_READINESS_GATE_FAILURE_WITH_NESTED_QUARK_HANDOFF_UNRESOLVED`。

已证实的是 CAS fail-closed readiness gate 在该 request 的 deadline 内没有得到 Guest
`FIRST_FRAME_DRAWN` 的完整合同，因而正确返回 FAIL。已证实但尚未归因的是 Quark 的 root
`com.ucpro.MainActivity`/nested `BrowserActivity` 冷启动交接与 CAS Guest readiness 的边界；
logcat 同时保留了 Quark/SDK native profile 与 SecurityGuard 相关异常，但当前快照没有把这些
异常与 gate failure 建立充分的因果链。原始 logcat 中更早其他运行的 FATAL 也不与本 request
关联，不能作为本次根因。

因此根因结论保持“待验证”：现有证据不足以把问题归为 CAS 通用性能修复，也不足以授权
Quark/package-specific production fix。该结论符合 TEMP-01 的先分类后修复边界；不得通过延长
deadline、固定 sleep、隐藏 retry 或静态 marker 关闭阻断。

## VA/NBB 对照结论

- NBB 对照：安装解析/设置缓存后复用 `ProcessRecord`，启动阶段不重复做完整 APK universe 解析。
- VA 对照：安装阶段完成 parse/copy/cache，启动复用已建立的运行时身份与进程边界。
- CAS 当前链路：import-only 已完成并记录 hash/parse/catalog 分段；失败落在 Guest Activity
  create/resume/window readiness 合同，不是 import-only 失败。应继续对照
  `RuntimeActivityLaunchCoordinator`、`GuestLaunchObservation`、`GuestLaunchGate` 的
  token/session/task/window identity 关联；本轮不采纳固定等待、吞异常重试或包名特判。
- 对应回归：TEMP-01 的直接/沙箱首帧门禁、KI-R03-062 的 Quark root→child handoff 和后续
  C4-R05 两轮正式矩阵。

## 证据与恢复条件

完整原始 evidence 目录和文件哈希索引见
`20260829T224349-artifact-index.md`；其中包含 request/operation、attempt/retry、logcat、
Activity/process、Window/Surface、截图、安装事务、catalog、revision、设备 boot ID 和 APK
摘要。第一次失败不被覆盖。

恢复前提：完成 CAS readiness 与 Quark/app-SDK 延迟的有界分类；如需生产改动，先提交独立
VA/NBB 设计与回归用例，再在新的 clean commit 上重跑至少 3× direct/3× sandbox，并通过
`sandbox/direct <= 10x` 和真实 `FIRST_FRAME_DRAWN` 硬门禁。当前不进入 `C4-R05`。
