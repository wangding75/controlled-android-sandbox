# P1-01 Short Baseline Receipt

```text
TASK_ID / STATUS / SCOPE
P1-01 / DONE / source-and-evidence freeze plus API36 AVD S01-S04 fixture baseline

CAS_HEAD / DIRTY_DIFF_HASH / HOST_APK_SHA256 / FIXTURE_APK_SHA256
40f629d03bbb0927f171a0e318c242398d8a141d / tracked diff clean; pre-existing untracked out/ preserved /
14470e500457583e2a1627e1e8ce0523803486eee0ca6ed7b4f0db9d8e4162a0 /
a43fd685009edf92f107d3bad38ccf7dc93f52215f836e3d60bbe1aedd1449ee

REFERENCE_PROJECT / REFERENCE_COMMIT / FILE:LINE / METHOD
NewBlackbox / 89b59836c66f173756a4ae258cf379a957649820 / Bcore/build.gradle:23 / build ABI inspection
VirtualApp open source / 802a82c2b9c15a1990e75eee1e9fe07168854772 / VirtualApp/lib/build.gradle:4; README.md:7,261 / build and provenance inspection

REFERENCE_BEHAVIOR / CAS_BEFORE / CHOSEN_CONTRACT / REJECTED_ALTERNATIVES
NBB declares arm64-v8a/armeabi-v7a; VA OSS declares SDK26 + armeabi-v7a/x86 and is not modern VA PRO source. /
CAS HEAD is the 15-file REALAPP-COMPAT-02 delta. /
Freeze source/evidence provenance without adopting any reference implementation. /
Rejected: treating README ABI claims, VA commercial changelog items, or historical results as present implementation/pass.

REPRO_CASE / NATIVE_BASELINE / EXPECTED / ACTUAL_AFTER
S01,S02,S03,S04 continuous / API36 Google AVD / all four PASS once, no diagnostic retry / PASS, 4/4; S04 activity_reuse=true.

DEVICE_FINGERPRINT / API / ABI / PAGE_SIZE / SETTINGS
google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys / 36 / x86_64 / 4096 /
explicit serial emulator-5554; Google APIs system image; AVD name T57_R03_API36_x86_64.

APP_PACKAGE / VERSION / BASE_SPLITS_SHA256 / DEPENDENCY_PROVIDER_VERSIONS
com.warden.controlledsandbox.debug and fixture packages / debug / current APK SHA256 above / not a commercial-App validation.

REQUEST_ID / SESSION_ID / GENERATION / FIRST_FAILURE
S04 standalone first recorded a precondition-invalid ACTIVITY_REUSE_NOT_OBSERVED; continuous run has PASS and no failure. /
No diagnostic recovery performed.

REQUIRED_CASES_PASS_FAIL / RAW_FAILURES / RECOVERY_RESULTS / NOT_TESTED
S01-S04 = 4 PASS, 0 FAIL / Chrome launch remains historical FAIL/NPE; two historical capability FAIL retained / none /
Chrome current launch, Quark business smoke, Xiaomi current-HEAD run, and all later P1/P2 scopes.

REGRESSIONS / EVIDENCE_PATHS / KI_MAPPING / NEXT_TASK
No code change and no broad regression. /
out/verification/p1-01-api36-s01-s04-continuous-20260908; docs/plans/P1-01_* /
C0/C6 and REALAPP-COMPAT-01/02 mapping in P1-01_OLD_NEW_TASK_MAPPING.md /
P1-02.
```
