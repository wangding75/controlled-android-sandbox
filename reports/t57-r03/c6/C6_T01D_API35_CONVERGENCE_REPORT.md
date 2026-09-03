# C6-T01D — Android API35 Platform Convergence

## Result

```text
RESULT=PASS
START_HEAD=daab4cbec81e3819e8f54ce2cd1b6ad9996bec3d
FINAL_HEAD=HEAD (the single C6-T01D commit; exact SHA is in the final receipt)
SCOPE=Android API35 only
```

API36/37, OEM ROMs, full ARM64, dynamic 16 KB runtime, C4-R05 loops=50, Companion32
cross-bitness, and the commercial-app matrix were not started.

## API35 device metadata

| Field | Verified value |
|---|---|
| AVD / serial | `C6_T01D_API35_GoogleApis_x86_64` / `emulator-5562` |
| manufacturer / model | `Google` / `sdk_gphone64_x86_64` |
| Android / API | `15` / `35` |
| ABI / ABI list | `x86_64` / `x86_64, arm64-v8a` |
| page size | `4096` |
| build fingerprint | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` |
| kernel | `Linux localhost 6.6.50-android15-8-g8adecb593e9b-ab12525588 #1 SMP PREEMPT Fri Oct 18 23:59:20 UTC 2024 x86_64 Toybox` |

The serial, model, manufacturer, API, release, ABI, ABI list, page size, fingerprint,
kernel, and `adb get-state=device` were recorded from system properties/commands. The AVD
name was not treated as proof of API level. This is the required Google APIs x86_64 image;
its runtime page size is 4 KB.

## Build configuration

```text
COMPILE_SDK=36
HOST_TARGET_SDK=35
FIXTURE_TARGET_SDK=35
MIN_SDK=26
BUILD_TOOLS=35.0.0
NDK=27.2.12479018
CMAKE=3.22.1
```

## Baseline-first

The complete API35 baseline ran at `START_HEAD` before any product source change. The
API35 verification-lane additions were limited to explicit API35 device validation and
the fail-closed omission of an unavailable 32-bit Companion APK; no product source was
changed before the baseline.

```text
run: out/verification/c6-t01d-api35-baseline/
API35_BASELINE_TOTAL=10
API35_BASELINE_PASS=5
API35_BASELINE_FAIL=5
API35_BASELINE_SKIP=0
```

S01, S02, S06, S07, and S08 passed unchanged. S03, S04, S09, and S10 failed at the
API35 launch gate with the framework `InputMethodInfoSafeList` null extraction signature;
S05 failed with `SERVICE_START_CALLBACK_TIMEOUT`. API34, API33, and API32 targeted
comparison runs for S03/S04/S05/S09/S10 were each `5/5 PASS`, establishing that these
were API35-specific findings rather than a general/API32 regression.

The no-product-change capability baseline was:

```text
run: out/verification/c6-t01d-api35-capability-baseline/
API35_CAPABILITY_TOTAL=8
API35_CAPABILITY_PASS=4
API35_CAPABILITY_FAIL=3
API35_CAPABILITY_SKIP=1
```

The failures were the same API35 readiness/Service chain findings; the AppWidget result
was already an explicit dynamic-suite skip.

## Defect and root-cause matrix

| Finding | Classification | API32/API33/API34 comparison | Resolution |
|---|---|---|---|
| API35 InputMethod list interception returned a collection/null shape where the framework expects `InputMethodInfoSafeList` | `PRODUCT_DEFECT_API35` | API32/API33/API34 targeted launch and capability paths pass | Return the framework type's `empty()` value through the existing interaction hook and add only the exact hidden-API exemption prefix for `InputMethodInfoSafeList` |
| API35 Service completion contract includes the service `Intent`; ordinary `onUnbind(false)` uses the API35 unbind completion transaction | `PRODUCT_DEFECT_API35` | API32 targeted Service lifecycle and the API34/API33/API35 affected smoke paths pass after the adapter change | Centralize the API gate in `GuestActivityThreadServiceLifecycle`, preserving the pre-35 `unbindFinished()` contract and passing the fifth `Intent` argument on API35 |
| S03/S04/S09/S10 baseline failures cascaded from the API35 launch-gate failure | `CASCADE`, not separate defects | Clean final runs prove real cold launch, warm task reuse, lifecycle, and recovery | No assertion weakening or retry-as-success was added |
| Full capability-suite C2_T05 FGS markers were intermittently absent once on API35 and once on API34; isolated C2 and a minimal T02 → Framework → C2 sequence passed | `ENVIRONMENT` / non-blocking observability condition | Same behavior on API34 and isolated API35 pass; final API35 capability run passed | Retained strict markers; no product patch, sleep, or retry was introduced |
| API35 x86_64 image has no 32-bit ABI for Companion32 | `HARNESS_DEFECT` / `UNSUPPORTED_PLATFORM` boundary | The device contract is x86_64-only; cross-bitness remains C6-T02 | Both runners validate the real ABI list and record the omitted Companion explicitly |
| Notification permission denial and external adb-shell delivery to a `NOT_EXPORTED` dynamic receiver | `EXPECTED_PLATFORM_BEHAVIOR` | Same platform policy is observed on earlier API lanes | Fixtures assert denial and use same-Guest delivery; CAS does not bypass the policy |

There are no unresolved `PRODUCT_DEFECT_GENERAL` or `PRODUCT_DEFECT_API35` findings.
There is no fixture defect. AppWidget dynamic coverage remains an explicit
`NOT_COVERED_BY_API35_DYNAMIC_SUITE` result, not a fabricated PASS.

## Implemented files

Product compatibility changes:

```text
sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InteractionServiceInvocationInterceptor.java
sandbox-native/src/main/cpp/native_policy_jni.cpp
sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestActivityThreadServiceLifecycle.java
```

Verification-lane changes:

```text
tools/verification/run_api33_capabilities.py
tools/verification/run_rd_smoke.py
```

The changes are concentrated in the existing framework interaction boundary, native
hidden-API bridge, Guest service lifecycle adapter, and verification entry points. No
app-package or OEM special case, broad hidden-API package exemption, fixed sleep, retry
as success, `catch Throwable` continuation, Host fallback, or assertion reduction was
added.

## VA / NewBlackbox and Android framework comparison

`ref/upstream/VirtualApp` and `ref/upstream/NewBlackbox` were inspected read-only for
service-proxy ownership/completion, package identity, task/process ownership, Provider,
and PendingIntent patterns. The comparison informed the existing CAS adapter boundary;
no upstream code was copied and `ref/` is unchanged.

The API35 contract was checked against the Android Open Source Project sources for
`InputMethodInfoSafeList.empty()`, the API35 `IActivityManager.serviceDoneExecuting`
transaction, and `ActivityThread`'s fifth service-completion `Intent`:

- [Android 15 InputMethodInfoSafeList](https://android.googlesource.com/platform/frameworks/base.git/%2B/7f9eba1c587886880b7a40ac1fbc4b310eed7ba1/core/java/com/android/internal/inputmethod/InputMethodInfoSafeList.java)
- [Android 15 IActivityManager.aidl](https://android.googlesource.com/platform/frameworks/base/%2B/android-15.0.0_r1/core/java/android/app/IActivityManager.aidl)
- [Android 15 ActivityThread.java](https://android.googlesource.com/platform/frameworks/base/%2B/android-15.0.0_r1/core/java/android/app/ActivityThread.java)

## API35 final smoke and capabilities

The final clean API35 Smoke run was:

```text
run: out/verification/c6-t01d-api35-final-smoke/
API35_SMOKE_TOTAL=10
API35_SMOKE_PASS=10
API35_SMOKE_FAIL=0
```

S01-S10 passed. S04 included real launcher-task preflight/reuse, request-scoped reuse
observation, `DELIVERED_NEW_INTENT`, the same Activity token, and resumed/first-frame
evidence. S05-S10 passed with Service ownership/startId/lifecycle completion, broadcast
PendingResult, valid Provider terminal state, PendingIntent routing, package clear and
relaunch, and process cleanup/recovery.

The final API35 capability run was:

```text
run: out/verification/c6-t01d-api35-capabilities-final/
API35_CAPABILITY_TOTAL=8
API35_CAPABILITY_PASS=7
API35_CAPABILITY_FAIL=0
API35_CAPABILITY_SKIP=1
```

Passing cases cover PackageManager/package visibility, permission/AppOps/AttributionSource
identity, Activity/task/window and transport, Service/FGS, broadcast/PendingResult,
Provider, PendingIntent/IntentSender, notification behavior, Alarm, JobScheduler,
network/media/DNS/VPN, shortcut/launcher identity, WebView, Guest ClassLoader, and basic
native/JNI loading. The only skip is:

```text
CAP-APPWIDGET-DYNAMIC=SKIP
reason=NOT_COVERED_BY_API35_DYNAMIC_SUITE
```

Capability status boundaries for the final API35 evidence are:

```text
STOPPED_STATE_API35=PASS
PENDING_INTENT_STATE_API35=PASS
BAL_API35=NOT_IN_CURRENT_SCOPE
SAFER_INTENT_API35=PASS
FGS_API35=PASS
BACKGROUND_NETWORK_API35=PASS
NON_SDK_API35=PASS_EXACT_CLASS_ADAPTER
WINDOW_API35=PASS
PACKAGE_LIFECYCLE_API35=PASS
WEBVIEW_API35=PASS
NATIVE_BASIC_API35=PASS
```

Direct BAL/`killBackgroundProcesses` fixture coverage is not present, so it is not
promoted to PASS. The safer-Intent status is covered by the existing framework
transport/identity and PendingIntent/creator routing checks.

## Static 16 KB readiness

The static ELF check used `llvm-readelf.exe` from NDK `27.2.12479018` over the native
libraries in the debug Host and fixture APKs. There were 19 ELF files; every `PT_LOAD`
alignment was `0x4000`, giving `19/19 PASS`.

```text
PAGE_SIZE_16K_STATIC_READINESS=PASS_STATIC_ONLY
ELF_TOTAL=19
ELF_PASS=19
PT_LOAD_ALIGN=0x4000
```

The API35 AVD itself reports a 4096-byte page size. This static result does not replace a
real 16 KB-page dynamic device run; dynamic 16 KB, ARM64, and cross-bitness remain in
C6-T02.

## API34/API33/API32 regression

Each required cross-version smoke gate was run on a clean device state with the final
product APKs:

```text
API34 run: out/verification/c6-t01d-api34-regression-smoke-final/
API34_REGRESSION_TOTAL=10
API34_REGRESSION_PASS=10
API34_REGRESSION_FAIL=0

API33 run: out/verification/c6-t01d-api33-regression-smoke/
API33_REGRESSION_TOTAL=10
API33_REGRESSION_PASS=10
API33_REGRESSION_FAIL=0

API32 run: out/verification/c6-t01d-api32-regression-smoke-final/
API32_REGRESSION_TOTAL=10
API32_REGRESSION_PASS=10
API32_REGRESSION_FAIL=0
```

The API32 result includes the mandatory C6-T01A-R01 S04 Activity reuse, S05 Service, S06
Broadcast, S07 Provider, S08 PendingIntent, and S09 package-lifecycle regressions.

## Build, tests, false-pass, and evidence hygiene

```text
python -m tools.verification.test_harness  PASS (6/6)
./gradlew.bat projects                       PASS
./gradlew.bat assembleDebug                  PASS
./gradlew.bat test                           PASS
python -m compileall -q tools/verification   PASS
FALSE_PASS_CHECK                             PASS
git diff --check                             PASS
```

The harness remains fail-closed: accepted/pending launches, black frames, missing
lifecycle markers, forbidden identity markers, and unsupported dynamic coverage cannot
become PASS. Runtime logs, screenshots, dumpsys, raw traces, APKs, build outputs, and AVD
data remain local ignored evidence under `out/verification/`; no runtime artifact is
committed.

## Remaining limitations

- API35 validation uses Google APIs x86_64 and a 4096-byte-page AVD only.
- Companion32/cross-bitness, full ARM64, and dynamic 16 KB runtime are deferred to C6-T02.
- Dynamic AppWidget host/provider coverage is not present and is an explicit SKIP.
- Direct BAL/`killBackgroundProcesses` probes and external runtime DEX/JAR injection are
  not in the current dynamic fixture scope.
- API36/37, OEM ROMs, C4-R05 loops=50, and the commercial-app matrix were not started.

## Evidence paths

```text
out/verification/c6-t01d-api35-baseline/
out/verification/c6-t01d-api35-capability-baseline/
out/verification/c6-t01d-api35-final-smoke/
out/verification/c6-t01d-api35-capabilities-final/
out/verification/c6-t01d-api34-regression-smoke-final/
out/verification/c6-t01d-api33-regression-smoke/
out/verification/c6-t01d-api32-regression-smoke-final/
```

These are local runtime evidence only. The final Git gate requires unchanged `ref/`, no
tracked runtime/build output, one C6-T01D commit with the requested message, a successful
push, and a clean worktree.
