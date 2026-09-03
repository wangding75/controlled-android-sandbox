# C6-T01C — Android API34 Platform Convergence

## Result

~~~~text
RESULT=PASS
START_HEAD=6656f2cd466ff4297e599d819d4c0481d48f3e3f
FINAL_HEAD=HEAD (the single C6-T01C commit; exact SHA is in the final receipt)
SCOPE=Android API34 only
~~~~

The API34 lane used a Google APIs x86_64 emulator with a 4096-byte page size. API35-37,
ARM64, 16 KB pages, OEM ROMs, C4-R05 loops=50, and the commercial-app matrix were not
started.

## API34 device metadata

| Field | Verified value |
|---|---|
| AVD / serial | C6_T01C_API34_GoogleApis_x86_64 / emulator-5560 |
| manufacturer / model | Google / sdk_gphone64_x86_64 |
| Android / API | 14 / 34 |
| ABI / ABI list | x86_64 / x86_64, arm64-v8a |
| page size | 4096 |
| build fingerprint | google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys |
| kernel | Linux localhost 6.1.23-android14-4-00257-g7e35917775b8-ab9964412 #1 SMP PREEMPT Mon Apr 17 20:50:58 UTC 2023 x86_64 Toybox |

The contract was confirmed from getprop, getconf PAGE_SIZE, uname -a, and adb get-state;
the AVD name was not treated as proof.

## Build configuration

The repository-wide configuration used by Host and fixtures is:

~~~~text
COMPILE_SDK=36
HOST_TARGET_SDK=35
FIXTURE_TARGET_SDK=35
MIN_SDK=26
BUILD_TOOLS=35.0.0
~~~~

## Baseline-first

No production source was changed before the complete API34 baseline. The first raw setup
probe recorded in out/verification/c6-t01c-api34-baseline-raw/: S01 could not install
the 32-bit Companion on the API34 x86_64 image (INSTALL_FAILED_NO_MATCHING_ABIS), so
S02-S10 were setup cascades. The API34-only lane adaptation then omitted Companion32
explicitly and validated the real device properties. The complete baseline, still at the
start product HEAD, is recorded in out/verification/c6-t01c-api34-baseline-complete/:

~~~~text
API34_BASELINE_TOTAL=10
API34_BASELINE_PASS=2
API34_BASELINE_FAIL=8
API34_BASELINE_SKIP=0
~~~~

The eight case failures shared the same wifiScanner readiness error and were not counted
as eight independent defects. The initial capability baseline at the same product HEAD is
in out/verification/c6-t01c-api34-capability-baseline-complete/:

~~~~text
7 total / 0 pass / 6 fail / 1 skip
~~~~

The six capability failures had the same readiness cause; AppWidget was already an explicit
NOT_COVERED_BY_API34_DYNAMIC_SUITE skip.

## Defect and root-cause matrix

| Finding | Classification | API32/API33 comparison | Resolution |
|---|---|---|---|
| API34 wifiScanner/dnsResolver application readiness used the API33-only restriction gate | PRODUCT_DEFECT_API34 | Corresponding API32 and API33 smoke/capability paths pass; API34 alone failed before product changes | Extended the existing centralized PlatformServiceCompatibility adapter to API33+; system-only Binder absence remains an explicit platform restriction and no Host fallback is used |
| API34 public InputMethodManager calls bypassed the legacy manager field through IInputMethodManagerGlobalInvoker | PRODUCT_DEFECT_API34 | API32/API33 C2T05 IME checks pass | Added an API34 global lazy-service-cache hook at the service compatibility boundary; C2T05 C2_T05_IME_PASS is observed |
| API34 LauncherActivityInfoInternal/IncrementalStatesInfo projection requires the API34 constructor contract and virtual UserHandle | PRODUCT_DEFECT_API34 | API32/API33 launcher checks pass | Added the API34 constructor projection with hidden-access setup and virtual-user ownership; C2T07 shortcut/launcher checks pass |
| API34 FGS type validation checks the physical StubService UID for FOREGROUND_SERVICE_DATA_SYNC | PRODUCT_DEFECT_API34 | The failure was API34-only; API32/API33 related FGS checks pass | Declared the type-specific permission for the Host physical StubService while retaining the Guest dataSync type semantics; no generic/specialUse bypass was added |
| 32-bit Companion install on the API34 x86_64 image | HARNESS_DEFECT plus UNSUPPORTED_PLATFORM boundary | The image has no 32-bit ABI; API32 retains the Companion lane | API34 runner omits Companion32 with an explicit C6-T02 cross-bitness limitation and continues with x86_64 Host/fixtures |
| Split capability runner initially passed the wrong package/component representation | HARNESS_DEFECT | No product behavior was implicated | The runner now uses the existing class-name launch contract and the split check passes without assertion reduction |

The baseline S03-S10 and capability failures were cascades of the first root cause, not
separate unresolved product defects. There are no unresolved PRODUCT_DEFECT_GENERAL or
PRODUCT_DEFECT_API34 findings.

## Implemented files

    app/src/main/AndroidManifest.xml
    sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkApplicationEnvironmentObjectFactory.java
    sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PlatformServiceCompatibility.java
    sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ReflectiveServiceHook.java
    sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/InputMethodManagerHook.java
    tools/verification/run_api33_capabilities.py
    tools/verification/run_rd_smoke.py
    reports/t57-r03/c6/C6_T01C_API34_CONVERGENCE_REPORT.md
    docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md

The production changes are concentrated in platform compatibility, framework-object
projection, and the Host service permission boundary. The verification changes add explicit
API34 device/lane validation and an existing split-fixture check. No package-name or OEM
special case, retry-as-success, fixed sleep, broad exception continuation, Host fallback, or
weakened assertion was added.

## VA / NewBlackbox comparison

The local reference trees ref/upstream/VirtualApp and ref/upstream/NewBlackbox were inspected
for service-proxy, package identity, launcher/shortcut, input-method, task, provider, and
PendingIntent ownership patterns. In particular, their WifiScanner system-service handling
was used to distinguish system/control-plane Binder visibility from the supported application
facade, and their LauncherApps/InputMethod proxy patterns were used as architecture references.
No upstream code was copied or mechanically transplanted. ref/ is unchanged.

The API34 framework contract was checked against the Android 14 SDK/AOSP sources for the
three-argument IncrementalStatesInfo constructor, the UserHandle-bearing
LauncherActivityInfoInternal constructor, and the process-global InputMethodManager
invoker/cache path. The adapter remains centralized in CAS's existing hook layer.

## API34 final smoke

Final run: out/verification/c6-t01c-api34-smoke-final/.

~~~~text
API34_SMOKE_TOTAL=10
API34_SMOKE_PASS=10
API34_SMOKE_FAIL=0
~~~~

S01-S10 all passed. S04 required real warm reuse/task and request-scoped lifecycle evidence;
S05-S09 include the service, broadcast/PendingResult, valid provider terminal state,
PendingIntent, and package clear/relaunch paths; S10 includes process kill/cleanup and
recovery evidence with a real first frame.

## API34 capability suite

Final run: out/verification/c6-t01c-api34-capabilities-final-r3/.

~~~~text
API34_CAPABILITY_TOTAL=8
API34_CAPABILITY_PASS=7
API34_CAPABILITY_FAIL=0
API34_CAPABILITY_SKIP=1
~~~~

The passing suite covers virtual PackageManager/package visibility, permission/AppOps/
AttributionSource identity, Activity/task/window, Service/FGS, broadcast and PendingResult,
Provider, PendingIntent/IntentSender, notification permission behavior, Alarm, JobScheduler,
network/media/DNS/VPN, shortcut/launcher identity, WebView, Guest ClassLoader, native/JNI
load, and the existing base+dynamic-feature split fixture. The split check proved two physical
APK paths, virtual multi-artifact import, base loading of a feature-only class, and feature
Activity creation.

The only capability skip is:

~~~~text
CAP-APPWIDGET-DYNAMIC=SKIP
reason=NOT_COVERED_BY_API34_DYNAMIC_SUITE
~~~~

Static AppWidget readback is not promoted to dynamic provider/host coverage. External runtime
DEX/JAR injection is not a current fixture contract; the API34 class-loading claim is limited
to Guest APK/split ClassLoader and native initialization. Direct BAL/killBackgroundProcesses
probes are also not in the current dynamic fixture and remain NOT_IN_CURRENT_SCOPE; no
unknown result is reported as PASS.

P0 capability status:

~~~~text
RECEIVER_API34=PASS
PENDING_INTENT_API34=PASS
DYNAMIC_CODE_LOADING_API34=PASS_FOR_GUEST_APK_SPLIT_CLASSLOADER; EXTERNAL_RUNTIME_DEX=NOT_IN_CURRENT_SCOPE
FGS_API34=PASS
JOB_API34=PASS
EXACT_ALARM_API34=PASS
PROCESS_API34=PASS
~~~~

## API33 and API32 regression

API33 related capability run out/verification/c6-t01c-api33-capabilities-final/ passed
7/7 executable cases with one explicit AppWidget skip; the shared runner's added split
case also passed. API33 final smoke out/verification/c6-t01c-api33-smoke-final/ passed:

~~~~text
API33_REGRESSION_TOTAL=10
API33_REGRESSION_PASS=10
API33_REGRESSION_FAIL=0
~~~~

API32 final smoke out/verification/c6-t01c-api32-regression-final/ passed:

~~~~text
API32_REGRESSION_TOTAL=10
API32_REGRESSION_PASS=10
API32_REGRESSION_FAIL=0
~~~~

The API32 result includes the mandatory S04 Activity reuse, S05 Service, S06 Broadcast,
S07 Provider, S08 PendingIntent, and S09 package-lifecycle regression cases.

## Build, tests, false-pass, and evidence hygiene

~~~~text
./gradlew.bat projects        PASS
./gradlew.bat assembleDebug   PASS
./gradlew.bat test            PASS
python -m tools.verification.test_harness  PASS (6/6)
python -m compileall -q tools/verification PASS
git diff --check                         PASS
FALSE_PASS_CHECK                         PASS
~~~~

The false-pass check confirmed that final S01-S10 results are typed PASS results with no
failure classification, and that the final capability matrix contains no FAIL or UNKNOWN
state. The harness continues to require real launch readiness/first-frame, marker evidence,
and explicit supported terminal states; accepted/pending operations, black frames, missing
markers, or Host fallback cannot become PASS.

All logcat, screenshots, dumpsys, process dumps, raw traces, APKs, build outputs, and AVD data
remain local ignored evidence under out/verification/. git ls-files out build is empty.

## Remaining limitations

- API34 was verified on Google APIs x86_64 with 4096-byte pages only.
- The image has no 32-bit ABI; Companion32/cross-bitness is explicitly deferred to C6-T02.
- Dynamic AppWidget host/provider coverage is not present and is an explicit SKIP.
- External runtime DEX/JAR loading and direct BAL/killBackgroundProcesses probes are not in
  the current fixture scope; Guest APK/split ClassLoader, WebView initialization, and basic
  JNI/native loading are covered.
- API35-37, ARM64, 16 KB pages, OEM ROMs, C4-R05 loops=50, and the commercial-app matrix were
  not started.

## Evidence paths

    out/verification/c6-t01c-api34-baseline-raw/
    out/verification/c6-t01c-api34-baseline-complete/
    out/verification/c6-t01c-api34-capability-baseline-complete/
    out/verification/c6-t01c-api34-capabilities-final-r3/
    out/verification/c6-t01c-api34-smoke-final/
    out/verification/c6-t01c-api33-capabilities-final/
    out/verification/c6-t01c-api33-smoke-final/
    out/verification/c6-t01c-api32-regression-final/

These are local runtime evidence only. The final Git gate requires one C6-T01C commit,
unchanged ref/, no tracked runtime/build output, and a clean worktree after push.
