# C6-T01B — Android API33 Platform Convergence

## Result

```text
RESULT=PASS
START_HEAD=68f4877684bbde636985b5497037b7367f76e402
FINAL_HEAD=HEAD (the single C6-T01B commit; exact SHA is in the final receipt)
SCOPE=Android API33 only
```

The API33 work was performed on the required AOSP/Google APIs x86_64 emulator. API34+
and the explicitly deferred ARM64, 16 KB, OEM, and cross-bitness scopes were not started.

## API33 device metadata

| Field | Verified value |
|---|---|
| AVD / serial | `C6_T01B_API33_GoogleApis_x86_64` / `emulator-5554` |
| manufacturer / model | `Google` / `sdk_gphone64_x86_64` |
| Android / API | `13` / `33` |
| ABI / ABI list | `x86_64` / `x86_64` |
| page size | `4096` |
| build fingerprint | `google/sdk_gphone64_x86_64/emu64x:13/TE1A.240213.009/12342917:userdebug/dev-keys` |
| kernel | `Linux localhost 5.15.119-android13-8-00034-gd34029c8258b-ab10871489 #1 SMP PREEMPT Wed Sep 27 18:42:24 UTC 2023 x86_64 Toybox` |

The contract was confirmed from system properties, `getconf PAGE_SIZE`, `uname -a`, and
`adb get-state`; the AVD name was not treated as proof.

## Baseline-first result

The unmodified start head was run first with the existing unified harness:

```text
run: out/verification/c6-t01b-api33-20260903-baseline1/
raw matrix: 10 total / 0 pass / 10 fail
```

S01 failed while installing the 32-bit Companion APK on the x86_64-only API33 image with
`INSTALL_FAILED_NO_MATCHING_ABIS`. S02-S10 were cascading setup failures because the
required fixture setup did not complete. The raw 0/10 matrix is retained as evidence; the
failures were reclassified as one API33 environment/setup mismatch plus harness
precondition cascades, not ten product failures. No product source was changed before
this baseline run.

## Defects and root causes

| Finding | Classification | Resolution |
|---|---|---|
| API33 runner attempted the 32-bit Companion on an x86_64-only image | `HARNESS_DEFECT` / `ENVIRONMENT` | API33 runner now validates properties, omits Companion32 with an explicit `UNSUPPORTED_PLATFORM` record, and continues with the supported Host/fixture set. `fixture32` has x86_64 only for package/identity routing; cross-bitness remains C6-T02. |
| API33 `wifiscanner` and `dnsresolver` are not application-facing Binder capabilities on this image | `EXPECTED_PLATFORM_BEHAVIOR` | Added centralized `PlatformServiceCompatibility`; the app-facing Wifi/DNS facades remain exercised. No Host fallback or swallowed `SecurityException` is used. |
| Live Guest runtime status `DEGRADED` was treated as dead during Activity callbacks | `PRODUCT_DEFECT_GENERAL` | Lifecycle reuse now requires live Activity transport/component lifecycle readiness and accepts `DEGRADED` for non-mandatory expected platform capabilities. It does not rebuild the same generation during the callback. This closed the isolated API33 S04/S08 failures and was regressed on API32. |
| API33 Google WebView provider was selected only at API35+ | `PRODUCT_DEFECT_API33` | The compatibility default now selects `com.google.android.webview` from API33. WebView initialization and JNI load pass on API33. |
| Debug service command rejected `PREPARED_DEGRADED` even when the operation was valid | `OBSERVABILITY_DEFECT` | Debug command accepts the explicit degraded terminal statuses; S05 then passes without weakening service assertions. |
| API33 notification permission is denied by default; external adb-shell delivery cannot satisfy a `NOT_EXPORTED` dynamic receiver | `FIXTURE_DEFECT` / `EXPECTED_PLATFORM_BEHAVIOR` | Fixtures assert permission denial without claiming a posted notification, and the dynamic receiver is exercised by a same-Guest send. External delivery is not treated as equivalent. |
| A non-clean intermediate run left a non-launcher virtual task root and S04 correctly declined reuse | `ENVIRONMENT` | Exact test packages were uninstalled from the dedicated AVD before final Smoke. The clean final run proves real `PRE_REUSE_*`, `NEW_INTENT`, resumed, and first-frame evidence. |

The only unresolved-looking API33 full-run failure was the deliberately diagnosed non-clean
state case; it reproduced as a missing launcher-task reuse witness and passed from clean
state. No unresolved core `PRODUCT_DEFECT` remains.

## Implemented files

```text
app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java
app/src/main/java/com/warden/controlledsandbox/VirtualCompatibilityDefaults.java
fixture-basic/src/main/AndroidManifest.xml
fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C2T05SchedulingInteractionActivity.java
fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/FrameworkProbeActivity.java
fixture-compat32/build.gradle
fixture-compat32/src/main/AndroidManifest.xml
sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java
sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PlatformServiceCompatibility.java
sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/DnsResolverServiceHook.java
sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestLifecycleCoordinator.java
sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/DeviceServiceProxyReadiness.java
tools/verification/capabilities/smoke.py
tools/verification/run_api33_capabilities.py
tools/verification/run_rd_smoke.py
```

The product changes are centralized in the platform compatibility/service boundary and
Guest lifecycle coordinator. No app-package or OEM special case was added; no retry, sleep,
`catch Throwable` continuation, assertion reduction, or Host-fallback success path was used.

## VA / NewBlackbox comparison

The local `ref/upstream/VirtualApp` and `ref/upstream/NewBlackbox` trees were inspected for
framework proxy, service descriptor, package identity, task/process owner, provider, and
PendingIntent patterns. In particular, the typed `IWifiScanner.Stub` and `wifiscanner` /
`dnsresolver` service-proxy references were used to confirm the distinction between a
system/control-plane Binder and an app-facing facade. The comparison informed the adapter
boundary only; no upstream code was copied and `ref/` is unchanged.

## API33 smoke and capability suites

Final clean API33 Smoke:

```text
run: out/verification/c6-t01b-api33-final-smoke-20260903-clean/
S01-S10: 10 total / 10 pass / 0 fail
```

S04 evidence includes `PRE_REUSE_RESTARTED`, `PRE_REUSE_STARTED`, `PRE_REUSE_RESUMED`,
`PRE_REUSE_PAUSED`, `LIFECYCLE_NEW_INTENT`, `ACTIVITY_RESUMED`, and `FIRST_FRAME_DRAWN`.
S05-S10 also pass in the same final run, including service lifecycle, broadcast PendingResult,
`CURSOR_READY` provider access, PendingIntent, package clear/relaunch, and process recovery.

API33 capability suite:

```text
run: out/verification/c6-t01b-api33-capabilities-20260903-final/
7 total / 6 pass / 0 fail / 1 skip
```

PMS/package visibility, permission/AppOps/AttributionSource identity, Activity/task,
service/FGS, broadcast, provider, PendingIntent/IntentSender, notification behavior,
Alarm/Job scheduling, network/media/DNS/VPN, shortcut/launcher identity, WebView/classloader,
and basic JNI/native loading passed through the existing fixtures. AppWidget is explicitly
`SKIP=NOT_COVERED_BY_API33_DYNAMIC_SUITE`; it is not reported as PASS.

## API32 regression

The affected API32 cases passed after each relevant fix (`5/5` in
`out/verification/c6-t01b-api32-affected-regression-20260903/`). The final full API32 gate
passed:

```text
run: out/verification/c6-t01b-api32-final-smoke-20260903/
S01-S10: 10 total / 10 pass / 0 fail
```

This includes the mandatory S04 Activity reuse, S05 Service, S06 Broadcast, S07 Provider,
S08 PendingIntent, and S09 package-lifecycle regressions.

## Build, tests, false-pass, and evidence hygiene

```text
./gradlew.bat projects        PASS
./gradlew.bat assembleDebug   PASS
./gradlew.bat test            PASS
python -m unittest tools.verification.test_harness -v   PASS (6/6)
python -m compileall -q tools/verification               PASS
git diff --check                                         PASS
FALSE_PASS_CHECK                                         PASS
```

The harness remains fail-closed: accepted/pending launches, black frames, missing lifecycle
markers, forbidden identity markers, and unsupported dynamic coverage cannot become PASS.
Runtime logs, screenshots, dumpsys, raw traces, APKs, build outputs, and AVD data remain in
ignored local evidence under `out/verification/`; `git ls-files out build` is empty. The
compact report and progress ledger are the only committed evidence artifacts.

## Remaining limitations

- API33 validation is Google APIs x86_64, 4096-byte pages only; ARM64, 16 KB, OEM, and API34+
  are outside this task.
- The API33 image has no 32-bit ABI, so the 32-bit Companion/cross-bitness lane is explicit
  `UNSUPPORTED_PLATFORM` and deferred to C6-T02.
- AppWidget dynamic host/provider coverage is not present in the current fixture suite.
- Notification permission denial and system-only `wifiscanner`/`dnsresolver` visibility are
  reported as platform behavior; CAS does not bypass either policy.

## Evidence paths

```text
out/verification/c6-t01b-api33-20260903-baseline1/
out/verification/c6-t01b-api33-capabilities-20260903-final/
out/verification/c6-t01b-api33-final-smoke-20260903-clean/
out/verification/c6-t01b-api32-affected-regression-20260903/
out/verification/c6-t01b-api32-final-smoke-20260903/
```

All are local ignored runtime evidence. Final Git checks require clean status, unchanged
`ref/`, and no tracked runtime/build output.
