# P1-02 Short Baseline Receipt

```text
TASK_ID / STATUS / SCOPE
P1-02 / DONE / reference decision protocol plus portable native fixture library; no CAS runtime behavior changed.

CAS_HEAD / DIRTY_DIFF_HASH / FIXTURE_APK_SHA256
40f629d03bbb0927f171a0e318c242398d8a141d /
tracked diff sha256=40901fdec1d88ba1678596c1093533d96b7f6eb4c947348adadf993e6edcb13c; P1-01/P1-02 untracked artifacts present /
provider=c7e57bd602889dd921825d63ad41cc5a73a7d64b8f4731cb6ca208bac9fe9d8b;
consumer=0558495101194f542bc765570f09ba63a48f677807f722de4a0fa8430e325e5b;
peer=f4acafbde048c71b318fe5740972501b2831d36eb9703290e8b09f58637d57c2.

REFERENCE_PROJECT / REFERENCE_COMMIT / FILE:LINE / METHOD
NBB / 89b59836c66f173756a4ae258cf379a957649820 / BActivityThread:358; IActivityManagerProxy:132; IPackageManagerProxy:107; BProcessManagerService:49; IOCore:107 / virtual package, provider, service, process, I/O study.
VA OSS / 802a82c2b9c15a1990e75eee1e9fe07168854772 / VClientImpl:316; PackageParserEx:212; MethodProxies:850,439; VActivityManagerService:750; IOUniformer.cpp:639 / historical lifecycle, package projection, service, provider, process, native-boundary study.

REFERENCE_BEHAVIOR / CAS_BEFORE / CHOSEN_CONTRACT / REJECTED_ALTERNATIVES
See P1-02_REFERENCE_DECISION.md. CAS has its own loader, virtual-first service, and provider-routing paths. /
Create independent app-package projection, remote Service, reentrant Provider, and absence fixture. /
The provider app's class/resource/asset must be observable; missing components retain their actual native absence result; no claim that this is privileged Android shared-library behavior. /
Rejected NBB/VA implementation copying, VA PRO changelog inference, fabricated metadata, fake callback/permission, catch-all success, retries, and native linker hooks.

REPRO_CASE / NATIVE_BASELINE / EXPECTED / ACTUAL_AFTER
run_p1_02_native_fixture_baseline.py on directly installed fixture-library-provider, fixture-library-consumer, and fixture-compat32 /
provider launch; class/resource/asset projection; missing Service; missing Provider; remote Service; reentrant Provider /
PASS. Missing Service was NULL; missing Provider was explicitly IllegalArgumentException on API36; remote and reentrant provider emitted their distinct process markers.

DEVICE_FINGERPRINT / API / ABI / PAGE_SIZE / SETTINGS
google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys / 36 / x86_64 / 4096 / explicit serial emulator-5554.

REQUEST_ID / SESSION_ID / GENERATION / FIRST_FAILURE
Native direct-install fixture; no CAS request/session/generation exists. attempt=1; no diagnostic retry; no first failure.

REQUIRED_CASES_PASS_FAIL / RAW_FAILURES / RECOVERY_RESULTS / NOT_TESTED
six required native markers PASS / none / none /
the same fixture through CAS, real Chrome/Quark behavior, ARM/OEM behavior, and all later P1/P2 scopes.

REGRESSIONS / EVIDENCE_PATHS / KI_MAPPING / NEXT_TASK
fixture-module assembleDebug PASS; py_compile PASS; check-architecture.py PASS /
out/verification/p1-02-native-api36-final-20260908; P1-02_REFERENCE_DECISION.md; P1-02_FIXTURE_SPEC.md /
old/new mapping inherits P1-01; no KI closed /
P1-03.
```
