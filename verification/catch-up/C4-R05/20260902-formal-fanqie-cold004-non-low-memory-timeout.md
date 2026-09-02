# C4-R05 formal matrix: Fanqie cold-004 non-LOW_MEMORY failure

> 本文件首段和原始分类是历史首失败记录，保留其 `BLOCKED` 时点事实，不因后续续接而
> 删除或改写。2026-09-02 用户随后批准了明确 `TimeoutException` 的 5 次性能异常重试；
> 该决定见 `docs/review/C4_R05_PERFORMANCE_TIMEOUT_RETRY_POLICY_20260902.md`，并只改变
> 当前续接策略，不改变以下原始证据。

- **Task / status**: `C4-R05` / `BLOCKED`; this is not a completion receipt and does not close C4.
- **Failure kind**: first non-`LOW_MEMORY` terminal failure after the durable-lane continuation.
- **Observed local time**: `2026-09-02 11:34:27.141` request start; command failure at
  `11:35:40.899`; full first-failure snapshot at `11:35:50.879` (Asia/Shanghai).
- **UTC evidence time**: request `2026-09-02T03:34:27.141569Z`; command completion
  `2026-09-02T03:35:40.899270Z`; snapshot `2026-09-02T03:35:50.879392Z`.
- **Start baseline**: commit `29e4f72246e24f37430249c0a660ba23f2443249`, branch
  `feature/t57-r03-va-pro-capability-campaign`, remote branch at the same commit; Git identity
  `OpenAI <openai@users.noreply.github.com>`.
- **RD environment**: dynamically resolved MuMu `RD测试`, API 32, model `22041211A`, ABI
  `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`, boot
  `754f6e00-da46-426d-857e-4bce363cad10`, Android ID `398eea33120cd887`. The resolved ADB
  endpoint is recorded only in the device snapshot and was not introduced into source code.
- **Dynamic sample**: 番茄免费小说, package `com.dragon.read`, version `7.1.9.32` / code
  `71932`, base-only APK (0 splits), primary ABI `arm64-v8a`, revision/base hash
  `35493ffa0979bc1e10d5e177a0526c3d3d922af779dca4d8c91505c50757daf9`, launch activity
  `com.dragon.read.pages.splash.SplashActivity`.

## First failure signature

The exact coordinate is `round-2-retained-hot-recovery`, `fanqie`, `user=0`, `cold-004`:

| Field | Value |
|---|---|
| request ID | `82ed7754c0af4a80a5ed1e3d290a64a0` |
| operation ID | `82ed7754c0af4a80a5ed1e3d290a64a0-launch` |
| runner operation ID | `c4-r03-fanqie-u0-cold-4-a2-82ed7754c` |
| attempt / retry budget | `2 / 0` |
| automatic retry | `false` |
| retryable | `false` |
| command result | `FAIL`, return code `1` |
| error | `IllegalStateException: launch failed: status=FAILED, errorType=java.util.concurrent.TimeoutException` |
| runner classification | `LAUNCH_RESULT_NOT_PASS` |
| readiness | no `FIRST_FRAME_DRAWN`; `readinessElapsedMs=null` |

The `attempt=2` value is the durable-lane observation after the earlier Quark Host
`LOW_MEMORY` recovery. It is not a hidden retry of this Fanqie coordinate. This coordinate has
no automatic retry and no retry budget.

## Evidence and phase timeline

The runner saved the complete first-failure bundle before any further launch attempt:

`verification/catch-up/C4-R05/formal-two-round-20260902-timeout12h-v1/round-2-retained-hot-recovery/launch-matrix/attempt-002/attempts/fanqie/user-0/cold-004/first-failure-full/`

The bundle contains request-scoped `logcat`/critical logcat, Activity/process/window/Surface
dumps, `dumpsys activity exit-info`, package and Host package snapshots, process list, device
properties, ADB device snapshot and screenshot. The durable lane also contains the case record at
the coordinate directory and the round/attempt summaries.

Relevant request-scoped Guest/framework stages:

| Local time | Stage | Observation |
|---|---|---|
| 11:35:25.232 | `SYSTEM_SERVICE_BEGIN` | target process PID `17941` entered the request |
| 11:35:28.075 | `CLASSLOADER_END` | classloader completed in `2629 ms` |
| 11:35:34.858 | `PROVIDER_PREPARE_BEGIN` | Guest provider preparation started |
| 11:35:36.792 | `PROVIDER_PREPARE_END` | provider preparation completed in `1941 ms`, cumulative `11561 ms` |
| 11:35:36.806 | `APPLICATION_ONCREATE_BEGIN` | no matching end/Activity resume/first-frame event before failure |
| 11:35:37.018 | `GUEST_PREPARE_ROLLBACK` | rollback began with `IllegalStateException` |
| 11:35:37.043 | `GUEST_PREPARE_END` | `30233 ms`, duration `30168 ms` |
| 11:35:37.085 | `PREPARE_RETURN` | `FAILED`, `TimeoutException` at `30222 ms` |
| 11:35:37.321 | Host command | `FAIL launch com.dragon.read` |

The snapshot observed a non-empty Surface and a non-black, non-uniform screenshot, but
`resumed_guest_stub_count=0`, no target Activity/Window evidence and no `FIRST_FRAME_DRAWN`.
The screenshot therefore cannot convert the request-scoped launch failure into PASS.

## Classification

- **Not the user-approved exception**: the full exit-info snapshot contains only explicit
  `USER REQUESTED` cold-stop records for the Host processes at 11:34:24/11:34:35. It contains
  no Host `ApplicationExitInfo reason=LOW_MEMORY`; the complete snapshot has no `LOW_MEMORY`
  marker.
- **Not a restart/rebootstrap failure**: the earlier Quark Host `LOW_MEMORY` event was already
  recovered with a dynamic MuMu restart and a separate Guest rebootstrap; this Fanqie failure
  occurred later on the new boot and returned a concrete Guest `TimeoutException`.
- **Not proven as a crash or ANR**: no FATAL, ANR or process-death evidence was found. The target
  process was still present in the snapshot, while the request had already rolled back and had
  no Activity/Window/first-frame result.
- **Root cause**: **待验证**. The evidence proves that the Guest prepare deadline expired while
  `com.dragon.read` was still in `Application.onCreate`; it does not yet prove whether the
  remaining delay is app/SDK startup work or a generic CAS Guest/broker boundary. It must not be
  converted into a package-specific fix or a deadline extension from this one observation.
- **Known Issue owner**: this is a new formal occurrence of `KI-R03-059`, not a new independent
  `LOW_MEMORY` event. `KI-R03-059` remains `RECORDED`, `NOT_FIXED` and blocking.

## VA/NBB boundary check

The applicable reference mapping remains the R03/R05 mapping already recorded in the ledger:

- CAS `GuestContentProviderFrameworkInterceptor`, `GuestRuntimeBrokerBridge`,
  `RuntimeActivityLaunchCoordinator` and `GuestLaunchGate` provide the observed provider,
  broker, readiness and first-frame boundary.
- The VA/NBB comparison covers `VActivityManagerService`/`ActivityStack`/`VirtualRuntime` and
  `BProcessManagerService`/`ActivityStack`/`BActivityThread` process owner, Activity token,
  Window identity, package and lifecycle boundaries.
- The current evidence does not identify a new mismatch in Window/Surface identity or a CAS
  exception that justifies a production patch. No source change, SLO change, fixed sleep or
  hidden retry was made for this failure.

## Acceptance and recovery decision

C4-R05 requires every commercial sample launch to reach `FIRST_FRAME_DRAWN` within the cold
30-second deadline. One Fanqie cold launch returned a real Guest timeout and no first frame, so
the retained/hot round is not complete and C4-R05 cannot be `DONE`. The lane stopped at the
first non-allowed terminal failure; no later matrix coordinates, regressions or pressure test
were run after it.

Recovery requires an evidence-backed classification/fix for the Guest prepare/Application
startup boundary, a clean pushed commit, and a new fail-closed continuation from this exact
coordinate while preserving this original failure. Host `LOW_MEMORY` remains independently
non-blocking per the campaign policy: each such event is recorded and dynamically recovered,
but it does not authorize this non-`LOW_MEMORY` timeout to pass.

## Policy overlay and current continuation decision (2026-09-02)

The historical observation above remains authoritative: it is a real request-scoped launch
failure, with no first frame and no Host `LOW_MEMORY`. The user has now classified this specific
kind of explicit launch/Guest `TimeoutException` as a host-performance exception that may be
retried at most five times. The current occurrence is therefore **accepted as retryable**, not
as a PASS and not as a root-cause closure.

The continuation wrapper now accepts only a failed launch result containing the explicit
`TimeoutException` type, preserves every full failure bundle, and launches the same coordinate in
a separate attempt. A generic collector timeout, phase timeout, black screen, missing window or
any non-timeout failure remains fail-closed. If a later observation reaches the real
`FIRST_FRAME_DRAWN` contract, it may replace this coordinate's terminal row while the original
failure stays in `observations`; if five retries are exhausted, the lane is blocked with
`PERFORMANCE_TIMEOUT_RETRY_BUDGET_EXHAUSTED`.
