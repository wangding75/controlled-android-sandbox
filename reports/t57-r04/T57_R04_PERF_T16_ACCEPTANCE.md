# T57-R04 PERF-T16 — VA / SX / CAS / Native Android 性能验收

## TASK

`PERF-T16` 是 T57-R04 导入与启动性能专项的最终收口任务。本报告记录当前代码头的可复现结构性验收结果，以及因无连接 Android 设备而未执行的动态性能矩阵。

## START_HEAD

`e542a00c` (`perf-T15-async-component-recovery`)

## FINAL_HEAD

`e542a00c`（代码验收头；本报告随后作为独立文档提交）

## RESULT

`FUNCTIONAL_PASS / PERFORMANCE_UNMEASURED / DEVICE_GATE_BLOCKED`

静态编译、全量静态自测和结构检查通过；没有把未采集的 Median/P95/Max 写成通过。当前 `adb devices -l` 只有 `List of devices attached`，因此无法执行真实设备/模拟器上的四方对照、重复运行或 Perfetto 二次验证。

## ROOT_CAUSE

动态验收依赖 Android/MuMu/AVD 设备和已安装的 A01–A08 样本。本工作区在验收时没有任何 `adb` 设备，故以下数据均为 `UNMEASURED`，不是零耗时，也不是性能通过：

- S01–S11 启动状态矩阵；
- N=1/10/50/100 package 扩展矩阵；
- I01–I07 导入矩阵；
- 每个场景至少 10 次、关键场景 30 次、Final Gate 50 次的 min/median/mean/p90/p95/p99/max/stddev；
- VA、SX、CAS、Native Android 的同 APK 同模拟器对照；
- CPU/Binder/disk/fsync/page-fault/mmap/ZIP/dex2oat/main-thread 的 Perfetto 证据。

## CODE_PATH

本轮 T00–T15 已落地的关键路径：

- 导入证据与 revision 快路径：`ApkImportManager`、`SandboxPackageLifecycle`、`InstalledApplicationImportProof(Store)`、`PackageRevisionSetVerifier`；
- 目录级 native 检测、流式 native digest 与 catalog 快照：`ApkImportManager`、`SandboxCatalogRepository`；
- 启动边界与观测解耦：`LaunchDeadline`、`RuntimeActivityLaunchCoordinator`、`GuestLaunchGate`、`GuestLaunchObservation`、`RuntimePerformanceTrace`；
- Guest 初始化收敛：`GuestPreparePlan`、`GuestRuntimeEnvironment`、`ProcessInitializationGate`；
- Binder 重绑等待：`RebindableServiceConnector`；
- daemon 恢复异步化：`RuntimeComponentRecoveryCoordinator`（generation-fenced single-thread daemon）。

## STRUCTURAL PERFORMANCE GATES

| Gate | 状态 | 证据/边界 |
| --- | --- | --- |
| Launch critical path fixed sleep = 0 | `PASS (scoped)` | `sandbox-runtime/src/main/java` 的启动路径无固定 retry sleep；剩余 10ms 删除失败兜底位于 `ApkImportManager.deleteTreeOrThrow`，watchdog 的 heartbeat sleep 位于诊断线程，均不属于正常 launch edge。 |
| Hot launch full APK SHA bytes = 0 | `STATIC_PASS` | revision proof/cache 路径（T01/T02/T07）和 `InstalledApplicationImportProofSelfTest`；真实热启动字节数仍待设备测量。 |
| Same revision import full APK copy = 0 | `STATIC_PASS` | `SandboxPackageLifecycle.sameRevisionFastPath` 在 proof 命中时直接返回已有 record；I02 P95 未测。 |
| Same revision native extraction = 0 | `STATIC_PASS` | fast path 在 proof 命中时不进入 normal importer/native extraction；native 结构由目录扫描和已发布 revision 复用。 |
| `containsNativeCode` non-lib stream open = 0 | `STATIC_PASS` | `ApkImportManager.containsNativeCode` 只枚举 ZIP central directory 名称，不打开任意 entry 内容。 |
| Single request nested full timeout reset = 0 | `STATIC_PASS` | `LaunchDeadline` 统一 deadline；`LaunchDeadlineSelfTest` 在静态矩阵中通过。 |
| Base APK duplicate SHA = 0 | `STATIC_PASS` | revision-set/proof metadata 复用与流式 digest（T01/T02/T09/T10）；跨设备重复导入计数未测。 |
| Repeated ServiceManager full init = 0 | `STATIC_PASS` | `ProcessInitializationGate` 的 OWNER/WAITER/REJECTED 状态和共享 future；`ProcessInitializationGateSelfTest` 通过。 |
| Package-universe O(N) Binder fan-out | `STATIC_PASS (controlled)` | `SandboxCatalogRepository` 批量快照路径；N 扩展的 Binder/内存曲线未测。 |
| Black screen accepted as launch success = 0 | `STATIC_PASS (semantic)` | product launch 与 readiness observation 解耦，`GuestLaunchGate` 要求独立首帧证据；真实黑屏样本仍需设备回归。 |

上述 `STATIC_PASS` 只表示源码/自测覆盖了结构性条件，不等价于性能 P95 达标。

## BEFORE

```text
median: UNMEASURED
p95:    UNMEASURED
max:    UNMEASURED
```

专项计划中的“CAS 约 20–30 秒”是待复现现象，不作为本报告的可比基线；本轮没有连接设备可生成 T00 stage-level baseline。

## AFTER

```text
median: UNMEASURED
p95:    UNMEASURED
max:    UNMEASURED
```

未连接设备，不能宣称 Hot Launch ≤1.5s、Cold Product Launch ≤3–5s、Cold First Frame ≤7s、Same Revision Import ≤1s 或 sandbox overhead ≤2–3s。

## IMPROVEMENT

结构性工作已完成（重复 digest/proof、重复 catalog/Package Universe、重复 Guest prepare、固定 Binder retry sleep、同步 daemon Service recovery 已分别收敛），但数值改善 `UNMEASURED`。因此本项不能标记为性能通过。

## SEMANTIC_CHANGE

`YES`。启动产品 API 与 readiness observation 分离；Guest 初始化变为进程级状态机；Binder retry 改为可被 close/death 唤醒的条件等待；Service crash recovery 改为 generation-fenced 后台任务。上述变化均保留 fail-closed、revision integrity 和 stale-generation 隔离语义。

## REGRESSION

静态检查未发现新增编译/自测回归。动态回归（真实 Activity、首帧、WebView/Chromium、Native ABI、split、钉钉/夸克、VA/SX 对照）尚未执行，不能作无回归结论。

## VERIFICATION

- `git diff --check`：通过；
- `./gradlew.bat :app:compileDebugJavaWithJavac`：`BUILD SUCCESSFUL`；
- `python tools/static_android_compile.py`：通过，包含 `rebindable service connector self-test`、`process initialization gate self-test`、Activity launch/first-frame 相关自测；
- `adb devices -l`：无设备（device gate blocked）。

## DEFERRED

连接同一模拟器并准备 A01–A08 后，按计划执行 S01–S11、N=1/10/50/100、I01–I07；每个场景保存 requestId/stage trace 并计算 min/median/mean/p90/p95/p99/max/stddev。随后使用 Perfetto/System Trace 复核 Binder、磁盘、fsync、page fault、mmap、ZIP、dex load 和主线程阻塞，再补充本报告的 BEFORE/AFTER 数值及四方对照结论。

## GIT_STATUS

工作树在提交本报告前保持 clean；T13–T15 的实现提交为：

- `817bc18f perf-T13-converge-process-initialization`；
- `245bb8b1 perf-T14-wake-binder-retry-waits`；
- `e542a00c perf-T15-async-component-recovery`。

