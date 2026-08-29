# C4-TEMP-01：2026-08-30 动态首失败分类证据

## 结论

本任务继续保持 `BLOCKED`，不得进入 C4-R05。两条独立动态夸克冷启动链均在 MuMu `RD测试`
上触发同一 `LAUNCH_FAILED / LAUNCH_GATE_FAILED` 首帧门禁失败；本轮没有重发同一 request，
没有自动 launch retry，也没有延长 30 秒门槛。

候选实现修复了先前观察到的 `ApplicationInfo.nativeLibraryDir` 指向 U4 私有目录导致的
`libsgmainso-6.6.230703.so` 缺失路径问题：本轮未再出现 `UnsatisfiedLinkError` 或该库缺失日志。
但是，夸克 `BrowserActivity` 的真实 framework `callActivityOnCreate` 仍分别耗时约 15.8 秒和
16.337 秒，目标 Guest 在 deadline 内没有报告真实 `FIRST_FRAME_DRAWN`。因此不能把候选修复
误判为完成，也不能把剩余延迟无证据地归为 CAS 通用或夸克专属；当前分类仍为
`CAS_READINESS_GATE_FAILURE_WITH_NESTED_QUARK_HANDOFF_UNRESOLVED`，其中 callback 内部的
App/SDK/Guest 环境边界待进一步输入。

## 环境与样本

- MuMu 实例：`RD测试`，动态解析结果 `MuMuPlayer-12.0-1`，API 32，型号 `22041211A`。
- ABI：`x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`；Android ID：`398eea33120cd887`。
- boot ID：`bd6fc459-0d52-4689-868a-420364ea407c`。
- 本次运行时 serial 仅写入 `environment.json`，未写入脚本常量或验收分支。
- 动态商业样本：夸克 `com.quark.browser`，`com.ucpro.MainActivity`，版本
  `10.10.5.1080/code1080`，base 1、split 0，primary ABI `arm64-v8a`。
- revision：`v1080:sha256:2cb38172da5da4aee03826da0feccb77ff0391ee7356f1562052ddc1fae9ecb3`。

## 独立运行结果

### 运行 A：`20260830T034123`

- import-only：PASS；request `783aac5d491343bcab3e6ca90a7f6693`，operation
  `5bd197d0-118f-454f-8e2a-735c5a04dc8e`，全链路耗时 `16728 ms`。
- 直接冷启动：3/3 PASS，`6430/6603/6044 ms`，每轮
  `attempt=1/retryBudget=0/automaticRetryPerformed=false`。
- 沙箱首失败：request `f54f6ec3009d489ca33769e14df29ec4`，operation
  `f54f6ec3009d489ca33769e14df29ec4-launch`，session
  `ef529806-f368-482a-bc8f-9fe48658345c`，task `167`，attempt 1、retry budget 0、无自动重试。
- Gate：`03:43:17.955` 报 `LAUNCH_FAILED`；真实 child `com.ucpro.BrowserActivity` 的
  `FIRST_FRAME_DRAWN` 于 `03:43:21.608` 才到达，晚于 gate，不能回写为 PASS。
- child：`GUEST_READY 03:42:49.245`，`CREATED 03:43:05.070`，
  `RESUMED 03:43:05.175`；首帧事件包含 `windowAttached=true/windowRegistered=true`。
- 首失败快照：截图 12504 bytes，SHA-256
  `4afb293f262964138b1d2e2a08733ad4d4216150e508b9f62e4610e47e0cb930`；
  `surfaceNonEmpty=false`，快照窗口/Surface 为空。

### 运行 B：`20260830T035048`

- import-only：PASS；request `033aeb3fb7a54178b9cfc8e997549942`，operation
  `11c4c43a-09a8-40bc-bb5c-fc3282dea1c4`，全链路耗时 `18716 ms`；import stage timing 保留于
  `sandbox/sample-01/import-only.json`。
- 直接冷启动：3/3 PASS，`8737/8405/7803 ms`，每轮
  `attempt=1/retryBudget=0/automaticRetryPerformed=false`。
- 沙箱首失败：request `7bc5f68e932b413ea03c3f300df92526`，operation
  `7bc5f68e932b413ea03c3f300df92526-launch`，session
  `3f0dd041-3ac6-4494-b711-28d72e8fc715`，task `168`，attempt 1、retry budget 0、无自动重试。
- Gate：`03:52:58.800` 报 `LAUNCH_FAILED / LAUNCH_GATE_FAILED`。失败快照于
  `03:53:33.317617+08:00` 收集，截图 12504 bytes，SHA-256 同上，`surfaceNonEmpty=false`。
- callback 分段：child `GUEST_READY 03:52:30.094`；
  `CALLBACK_CREATE_DELEGATE_BEGIN 03:52:30.108`；
  `CALLBACK_CREATE_DELEGATE_RETURN 03:52:46.444`，`delegateElapsedMs=16337`；
  `CREATED 03:52:46.445`，`RESUMED 03:52:46.620`。runner 在首帧到达前收口，因此本轮没有
  将后续可能到达的帧当作成功证据。

## 失败边界与分类

- CAS 在 host 侧的 `PACKAGE_STATE`、`PACKAGE_UNIVERSE`、Guest prepare 和 host start 均有
  request/operation trace；运行 B 的 host `PACKAGE_UNIVERSE` 为 `6857 ms`，Guest prepare
  为 `11706 ms`，但 observation gate 从真实 host start 后独立计时，不能用减少前置耗时替代
  child 的 FIRST_FRAME_DRAWN 合同。
- framework `ActivityThread` 已实例化 root/child，nested child 通过同一 session/task 被关联，
  `frameworkHost=true`、`existingBySession=true`、`existingByCallerTask=true`、parent lookup
  `found=true`。这证明当前 token/task 关联不是已验证的首要失败点。
- 运行 B 在 `delegate.callActivityOnCreate` 内出现约 16.337 秒空档；此段由 platform
  Instrumentation delegate 和目标 App/SDK 同步回调构成，现有证据不足以把所有延迟归因于 CAS
  通用层。故保留 `NEEDS_REPRODUCTION_AND_CLASSIFICATION`，不提交 package-specific fix。
- 运行 A 的首帧晚于 gate；运行 B 在 gate 收口前仍没有首帧。两者都是真实 fail-closed 结果，
  不是静态 marker、Guest 进程存在或 Host 占位替代。

## Native boundary 候选修复验证

- `CS_NATIVE_RUNTIME` 显示 CAS runtime U4 路径与 APK-owned packaged 路径分离；
  `CS_NATIVE_BIND` 的 guest dex search path 同时保留两者。
- `GuestApplicationInfoFactory` 现在把 `ApplicationInfo.nativeLibraryDir` 保持在 immutable
  APK revision 的 packaged lib 根；U4/WebView runtime 仍由 defining ClassLoader/NativePolicy
  单独投影。
- 运行 A/B 均没有 `UnsatisfiedLinkError` 或 `libsgmainso-6.6.230703.so not found`；仍有
  ART `DexLoadReporter` 无法在只读 revision 的 `lib/.../oat` 建 profile 的非致命日志。
  该现象未被提升为 gate 根因。

## 证据索引

- 运行 A：`quark-latency/20260830T034123/summary.json`、`.../sandbox/sample-01/case.json`、
  `.../sandbox/sample-01/first-failure-full/`、`live-logcat-20260830T034123-native-dir-fix.txt`。
- 运行 B：`quark-latency/20260830T035048/summary.json`、`.../environment.json`、
  `.../quark-discovery.json`、`.../install.json`、`.../direct/sample-01..03/case.json`、
  `.../sandbox/sample-01/case.json`、`.../sandbox/sample-01/first-failure-full/`、
  `live-logcat-20260830T035047-callback-timing.txt`。
- 运行时 device snapshot、Activity/Window/Surface/process、catalog、package lifecycle、
  revision 和截图均由 `first-failure-full` 保存；APK hash 见任务回执。

## 复现与验证命令

- `python scripts/check-c4-temp-01-latency.py`：PASS。
- `python scripts/check-c4-r05-orchestrator.py`：PASS。
- `python scripts/check-apk-revision-binding.py`：PASS。
- `./gradlew :sandbox-runtime:compileDebugJavaWithJavac --no-daemon`：PASS。
- `./gradlew :app:assembleDebug :fixture-basic:assembleDebug :sandbox-companion32:assembleDebug :fixture-compat32:assembleDebug --no-daemon`：PASS。
- `python tools/capability/run_c4_temp_01_quark_latency.py --samples 3`：两条独立运行均按
  首个沙箱失败退出，exit 1；没有继续发送后续 sandbox sample，也没有进入 R05。

恢复条件：先完成 CAS readiness 与 Quark/app-SDK/Guest 环境延迟的有界归因；如需生产修改，
先补充独立 VA/NBB 设计与回归用例，再在新的 clean commit 上使 TEMP-01 3 次沙箱冷启动均在
30 秒内达到真实 `FIRST_FRAME_DRAWN`，并通过其余静态/回归门禁后，才能恢复 C4-R05。
