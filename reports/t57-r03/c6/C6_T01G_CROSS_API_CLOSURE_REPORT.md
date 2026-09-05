# C6-T01G — API32–37 Cross-API Final Closure

## 1. 结论

本报告采用严格 Gate：API32–36 的统一回归全部通过，但当前公开 API37 AVD 在
headless 模式下无法完成稳定的最终全量 run。因此本任务不能把历史 workaround 结果
拼接成当前 60/60，也不能把环境问题写成 CAS 产品 PASS。

~~~text
RESULT=BLOCKED
START_HEAD=48aad5810f4217d93991a576983b52b03cd5b142
FINAL_HEAD=HEAD
BRANCH=feature/t57-r03-va-pro-capability-campaign
CROSS_API_HEAD=HEAD
COMMIT_MESSAGE=C6-T01G: close API32-37 cross-version convergence
AVD_MODE=HEADLESS
C6_T01F_FINAL_STATUS=PASS_WITH_ENVIRONMENT_DEFERRED
PASS_GATE=NOT_MET
C6_T01_STATUS=BLOCKED
NEXT_TASK=BLOCKED
~~~

API37 的现状是两个环境延期，而不是产品 defect：

1. 当前公开 Google APIs x86_64 AVD 为 4 GiB，Guest MemTotal 约 4008496 kB，
   cgroup v2 与 memory controller 正常，但没有 memory-limiter-config.xml，
   am memory-limiter status=disabled。因此 Memory Limiter M05 不能真实执行。
2. software 与 swiftshader headless renderer 均出现 readColorBufferDma，并触发
   SurfaceFlinger/RegionSampling abort。该项登记为
   KNOWN_EMULATOR_ENVIRONMENT_LIMITATION。

T01F 的 visible/workaround 10/10 仅保留为产品路径证据；它没有覆盖 T01G 要求的
clean headless environment gate。

## 2. 边界、Harness 与修复

本轮开始前冻结了最终 testcase contract：S01–S10、FIRST_FRAME_DRAWN、timeout、
retry policy 与 failure classification 均不在 API32–36 回归期间改变。最终 Harness
仍满足以下规则：

- launch 必须有 activity created/resumed、window evidence 与 FIRST_FRAME_DRAWN；
- exception 是 FAIL，timeout 不是 PASS；
- diagnostic retry 保留 first failure，不覆盖首个结果；
- capability cell 只使用 PASS、EXPECTED_PLATFORM_BEHAVIOR、
  UNSUPPORTED_PLATFORM、NOT_IN_CURRENT_SCOPE、DEFERRED_ENVIRONMENT、FAIL；
- AppWidget fixture 缺失记为 NOT_IN_CURRENT_SCOPE；API37 setup 不完整的动态能力记为
  DEFERRED_ENVIRONMENT；
- API32 lane 校验实际 API/ABI/page size，Companion32/cross-bitness 不在本任务 scope；
- Android 17 package install 采用显式 push + pm install transport，并有固定 bounded
  transport settle；这不是重试，也没有放宽 Gate。

T01G 发现并修复的真实产品问题：

| 分类 | 问题 | 修复与复跑 |
|---|---|---|
| GENERAL_DEFECT | GuestJobServiceBridge 在 JobServiceEngine 同步回调时发生 bridge/callback lock inversion，API36 的 FGS/Job callback 可卡死 | finish() 先释放 callback monitor，再执行 completion；API36 core 与 capability clean rerun 通过 |
| API_SPECIFIC_DEFECT | API33 WebView/Settings 路径以 Guest virtual UID 构造 platform ContentResolver，被真实 Binder caller attribution 校验拒绝 | platform-facing resolver 使用 Host service context，Guest-owned provider 仍使用 virtual caller；API33 core 与 capability clean rerun 通过 |

Harness/contract 收口包括：S09 re-add 后增加真实 relaunch readiness gate、API32
platform lane 与 capability flag、AppWidget/Companion32 scope 状态归一化、以及 API37
package-manager transport adapter。没有新增 App-specific hook、OEM 适配、compat override
或 Host fallback。

## 3. 设备环境快照

每个 lane 均在执行前从设备属性读取 serial、Android version、API、ABI、ABI list、
PAGE_SIZE、manufacturer、model、fingerprint 与 kernel；不依赖 AVD 名称判定 API。

| API | serial | Android | ABI | ABI list | PAGE_SIZE | manufacturer / model |
|---:|---|---|---|---|---:|---|
| 32 | 127.0.0.1:16416 | 12 | x86_64 | x86_64, arm64-v8a, x86, armeabi-v7a, armeabi | 4096 | Redmi / 22041211A |
| 33 | emulator-5556 | 13 | x86_64 | x86_64 | 4096 | Google / sdk_gphone64_x86_64 |
| 34 | emulator-5558 | 14 | x86_64 | x86_64, arm64-v8a | 4096 | Google / sdk_gphone64_x86_64 |
| 35 | emulator-5560 | 15 | x86_64 | x86_64, arm64-v8a | 4096 | Google / sdk_gphone64_x86_64 |
| 36 | emulator-5562 | 16 | x86_64 | x86_64, arm64-v8a | 4096 | Google / sdk_gphone64_x86_64 |
| 37 | emulator-5566 | 17 | x86_64 | x86_64, arm64-v8a | 4096 | Google / sdk_gphone64_x86_64 |

Fingerprint and kernel snapshot:

| API | fingerprint | kernel |
|---:|---|---|
| 32 | Redmi/rubens/rubens:12/V417IR/2428:user/release-keys | Linux localhost 5.4.32-perf-gda349bfae95e #3 SMP PREEMPT Wed Aug 19 10:55:20 UTC 2026 aarch64 |
| 33 | google/sdk_gphone64_x86_64/emu64x:13/TE1A.240213.009/12342917:userdebug/dev-keys | Linux localhost 5.15.119-android13-8-00034-gd34029c8258b-ab10871489 #1 SMP PREEMPT Wed Sep 27 18:42:24 UTC 2023 x86_64 Toybox |
| 34 | google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys | Linux localhost 6.1.23-android14-4-00257-g7e35917775b8-ab9964412 #1 SMP PREEMPT Mon Apr 17 20:50:58 UTC 2023 x86_64 Toybox |
| 35 | google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys | Linux localhost 6.6.50-android15-8-g8adecb593e9b-ab12525588 #1 SMP PREEMPT Fri Oct 18 23:59:20 UTC 2024 x86_64 Toybox |
| 36 | google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys | Linux localhost 6.6.66-android15-8-gd0c43a640eab-ab13812146 #1 SMP PREEMPT Mon Jul 21 17:41:13 UTC 2025 x86_64 Toybox |
| 37 | google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys | Linux localhost 6.12.58-android16-6-gccafb60de224-ab14828483 #1 SMP PREEMPT Tue Feb 3 03:46:47 UTC 2026 x86_64 Toybox |

API37 environment snapshot: MemTotal=4008496 kB; cgroup v2 is mounted with memory
controller; /system/etc/memory-limiter-config.xml is absent; am memory-limiter status is
disabled. No fake config, forced framework enable, kill -9 or LMK simulation was used.

## 4. Core Smoke Matrix

The authoritative current T01G results are the final-Harness runs in
out/verification/c6-t01g-final-api32..36-*-final-harness-settled-rerun1. API37 had no
complete accepted clean-headless run, so every incomplete API37 core cell is explicitly
DEFERRED_ENVIRONMENT.

| API | S01 | S02 | S03 | S04 | S05 | S06 | S07 | S08 | S09 | S10 | accepted total |
|---:|---|---|---|---|---|---|---|---|---|---|---:|
| 32 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 10/10 |
| 33 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 10/10 |
| 34 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 10/10 |
| 35 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 10/10 |
| 36 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 10/10 |
| 37 | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | 0/10 accepted |

~~~text
CORE_SMOKE_TOTAL=60
CORE_SMOKE_PASS=50
CORE_SMOKE_FAIL=0
CORE_SMOKE_DEFERRED=10
CORE_SMOKE_SKIP=0
~~~

API32 mandatory regression at the final Harness:

| Case | Contract | Result |
|---|---|---|
| S04 | Activity task projection / reuse | PASS |
| S05 | Service ownership / bridge / record / startId | PASS |
| S06 | teardown fencing | PASS |
| S07 | CURSOR_READY Provider assertion | PASS |
| S08 | derived Guest context / framework bridge ownership | PASS |
| S09 | stale controller ActivityRecord / result publication | PASS |

S09 now includes add V1, cold launch, warm launch, Service, Provider, PendingIntent,
clear, relaunch, delete, re-add and a final relaunch with FIRST_FRAME_DRAWN. The final
relaunch is a real Gate, not a process-exists assertion.

## 5. Unified Capability Matrix

The frozen common suite has eight cells per API. It covers PMS/package visibility,
permission/AppOps/AttributionSource, Activity/Task/window transport, Service/Broadcast/
Provider/PendingIntent, Job/Alarm/Notification/FGS, network/media/DNS/VPN,
Shortcut/Launcher, split/ClassLoader, WebView and native/JNI basic paths.

| Capability case | API32 | API33 | API34 | API35 | API36 | API37 |
|---|---|---|---|---|---|---|
| PMS / visibility / Permission / AppOps / Attribution | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| framework transport / Activity / Task / identity | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Service / Broadcast / PendingIntent / scheduling / FGS | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| network / media / DNS / VPN | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Shortcut / Launcher / environment | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| split APK / ClassLoader | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| AppWidget dynamic | NOT_IN_CURRENT_SCOPE | NOT_IN_CURRENT_SCOPE | NOT_IN_CURRENT_SCOPE | NOT_IN_CURRENT_SCOPE | NOT_IN_CURRENT_SCOPE | NOT_IN_CURRENT_SCOPE |
| WebView / ClassLoader / native | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |

~~~text
UNIFIED_CAPABILITY_TOTAL=48
UNIFIED_CAPABILITY_PASS=35
UNIFIED_CAPABILITY_FAIL=0
UNIFIED_CAPABILITY_SKIP=0
UNIFIED_CAPABILITY_NOT_IN_CURRENT_SCOPE=6
UNIFIED_CAPABILITY_DEFERRED=7
~~~

API32–36 capability run summaries are each 8 total, 7 PASS, 0 FAIL and one
NOT_IN_CURRENT_SCOPE. The old AppWidget SKIP representation was removed from the final
Harness contract. API37's previous 7/0/1 workaround result is retained only as historical
product evidence, not as a current clean-headless T01G result.

## 6. Version-Specific Matrix

Only the allowed cell states are used below. The API36 target probes are the verified
C6-T01E results, normalized so the fixture-less MediaStore row is
NOT_IN_CURRENT_SCOPE. The targetSdk36 probe lane ended with formal targetSdk35 restored.

| API | Case | Status | Evidence / boundary |
|---:|---|---|---|
| 34 | Dynamic Receiver export semantics | PASS | API34 receiver export contract |
| 34 | PendingIntent / Intent semantics | PASS | API34 PendingIntent path |
| 34 | Exact Alarm | PASS | Exact-alarm fixture path |
| 34 | Dynamic loading / split ClassLoader | PASS | Guest APK split/ClassLoader |
| 34 | FGS | PASS | FGS lifecycle case |
| 34 | Job | PASS | Job lifecycle case |
| 35 | Package stopped state | PASS | package lifecycle and stopped-state evidence |
| 35 | PendingIntent stopped/revision lifecycle | PASS | revision-bound delivery |
| 35 | Safer Intent | PASS | filter/redirect compatibility |
| 35 | FGS target35 behavior | PASS | formal targetSdk35 |
| 35 | non-SDK exact adapters | PASS | exact class adapter; no broad exemption |
| 35 | package lifecycle | PASS | S01–S10/S09 strengthened lifecycle |
| 35 | background network | PASS | network capability |
| 35 | Window / FIRST_FRAME_DRAWN | PASS | strict launch readiness |
| 35 | WebView | PASS | provider/asset path |
| 35 | native basic | PASS | JNI/native basic |
| 36 | Intent redirection protection | PASS | C6-T01E probe and current sanity |
| 36 | ordered broadcast semantics | PASS | ordered/async finish markers |
| 36 | Job quota | PASS | target35 semantics; no quota bypass |
| 36 | FGS / Job ownership | PASS | virtual ownership and callback completion |
| 36 | target36 compatibility probes, seven executed | PASS | change IDs 288912692, 377864165, 161252188, 29623414, 356174596, 349487600 plus controlled target36 smoke |
| 36 | MediaStore version lockdown | NOT_IN_CURRENT_SCOPE | no current dynamic fixture; enable/reset path verified |
| 36 | Package | PASS | current API36 core/capability |
| 36 | Activity / Window | PASS | current API36 core/capability |
| 36 | Service / Provider / PendingIntent / lifecycle | PASS | current API36 core/capability |
| 37 | new MessageQueue probe | PASS | controlled ChangeId probe from T01F; override reset verified |
| 37 | static-final audit | PASS | final static audit, 111 writes scanned, API37 unguarded risk absent |
| 37 | ART / non-SDK | PASS | exact adapter/static and targeted evidence |
| 37 | URI grant | NOT_IN_CURRENT_SCOPE | no independent fixture proof |
| 37 | Memory Limiter dynamic recovery | DEFERRED_ENVIRONMENT | status disabled; no real M05 evidence |
| 37 | renderer backend / TaskSnapshot | DEFERRED_ENVIRONMENT | KNOWN_EMULATOR_ENVIRONMENT_LIMITATION |

~~~text
VERSION_SPECIFIC_TOTAL=31
VERSION_SPECIFIC_PASS=27
VERSION_SPECIFIC_FAIL=0
VERSION_SPECIFIC_DEFERRED=2
VERSION_SPECIFIC_NOT_IN_CURRENT_SCOPE=2
TARGET36_PROBE_TOTAL=8
TARGET36_PROBE_PASS=7
TARGET36_PROBE_FAIL=0
TARGET36_PROBE_NOT_IN_CURRENT_SCOPE=1
~~~

No version-specific row has an unresolved PRODUCT_DEFECT. Environment-deferred rows are
not converted into PASS.

## 7. Package Lifecycle and Area Closure Matrix

The following is the current T01G dynamic closure view. API37 source/static evidence can
remain PASS in the adapter audit below, but its current clean-headless dynamic closure is
DEFERRED_ENVIRONMENT.

| Area | API32 | API33 | API34 | API35 | API36 | API37 |
|---|---|---|---|---|---|---|
| Activity | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Service | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Broadcast | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Provider | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| PendingIntent | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| PMS | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Permission / AppOps | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Job / Alarm | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| WebView | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| ClassLoader | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Native basic | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |
| Process / Recovery | PASS | PASS | PASS | PASS | PASS | DEFERRED_ENVIRONMENT |

The strengthened package lifecycle verifies old revision fencing for Activity, Service,
Provider, PendingIntent, Job, Alarm, Shortcut, ClassLoader, process slot and Binder/session.
API32–36 passed; API37 needs the environment prerequisite before this dynamic matrix can
be accepted.

## 8. Compatibility Adapter Matrix and Audit

| Area | API32 | API33 | API34 | API35 | API36 | API37 |
|---|---|---|---|---|---|---|
| Activity | PASS | PASS | PASS | PASS | PASS | PASS |
| Service | PASS | PASS | PASS | PASS | PASS | PASS |
| Provider | PASS | PASS | PASS | PASS | PASS | PASS |
| PendingIntent | PASS | PASS | PASS | PASS | PASS | PASS |
| PMS | PASS | PASS | PASS | PASS | PASS | PASS |
| WebView | PASS | PASS | PASS | PASS | PASS | PASS |
| non-SDK | PASS | PASS | PASS | PASS | PASS | PASS |
| MessageQueue | PASS | PASS | PASS | PASS | PASS | PASS |

The repository scan found no suspicious exact current-runtime conditions of the form
SDK_INT == 33, 34, 35, 36 or 37. Current production compatibility uses semantic boundaries
such as >=33, >=35, >=36 and >=37 with exact API reflection signatures where the Android
framework shape changed. The API equality selectors in the verification runners are lane
routing and actual device validation, not runtime fallback. Reference-only ref/ sources
were excluded from the product adapter audit.

~~~text
COMPAT_ADAPTER_AUDIT=PASS
API37_TEMPORARY_HACK=NONE
HOST_FALLBACK_IN_CURRENT_FIXES=NONE
~~~

Static-final/JNI audit:

~~~text
STATIC_FINAL_WRITE_SCAN_TOTAL=111
FRAMEWORK_STATIC_FINAL_WRITE_COUNT=2
JNI_STATIC_FINAL_RISK_COUNT=0
REFLECTION_ACCESS_SITES=90
REFLECTION_FIELD_WRITE_SITES=111
~~~

The two framework writes are API<=36 guarded legacy paths. No API37 unguarded static-final
or JNI risk was found.

## 9. Performance Sanity

Times are whole-case durations from the final Harness, in milliseconds. They are not a
performance optimization campaign.

| API | import/add S02 | cold launch S03 | warm launch S04 | assessment |
|---:|---:|---:|---:|---|
| 32 | 6806 | 6405 | 3985 | PASS |
| 33 | 13951 | 17804 | 9498 | PASS |
| 34 | 15570 | 20474 | 8774 | PASS |
| 35 | 23381 | 25386 | 8230 | PASS |
| 36 | 22220 | 25409 | 8762 | PASS |
| 37 | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | DEFERRED_ENVIRONMENT | no valid complete clean-headless timing |

No API32–36 result showed a blocker-level minute-scale or unexplained adjacent-API
order-of-magnitude regression. API37 has no accepted timing because the environment did
not complete the contract.

~~~text
PERFORMANCE_SANITY=PASS
~~~

## 10. False-Pass Audit

The final source/Harness audit and self-tests confirm:

- exception becomes FAIL;
- timeout remains timeout/failure classification;
- the first diagnostic result remains authoritative;
- process-exists is not launch success;
- ActivityRecord exists is not launch success;
- unsupported and fixture-less cases are not PASS;
- FIRST_FRAME_DRAWN is required for launch readiness;
- no SKIP is counted as PASS in the final capability summaries;
- current API37 environment failure is recorded as DEFERRED_ENVIRONMENT rather than PASS.

~~~text
FALSE_PASS_CHECK=PASS
~~~

## 11. Build, Test and Evidence Hygiene

~~~text
GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
UNIT_TESTS=PASS
HARNESS_TESTS=PASS (8/8)
PYTHON_COMPILEALL=PASS
API37_STATIC_AUDIT=PASS
TARGET_SDK=35
COMPAT_OVERRIDES_RESET=PASS
REF_STATUS=UNCHANGED
EVIDENCE_GIT_HYGIENE=PASS
~~~

The final verification evidence remains under ignored out/verification. No logcat,
screenshot, dumpsys, AVD snapshot, raw JSON, retry directory, heap dump, APK or build output
is added to Git. The release configuration remains compileSdk=36 and targetSdk=35. No
temporary compat override or memory-limiter configuration is present.

## 12. Known Limitations and Deferred Item

| Category | Item | Status |
|---|---|---|
| Deferred to C6-T02 | ARM64 dynamic | DEFERRED_ENVIRONMENT |
| Deferred to C6-T02 | 16 KB dynamic | DEFERRED_ENVIRONMENT |
| Deferred to C6-T02 | Companion32 / cross-bitness | NOT_IN_CURRENT_SCOPE |
| Deferred to C6-T02 | ARM32 scope | NOT_IN_CURRENT_SCOPE |
| Environment Deferred | API37 Memory Limiter termination and recovery | DEFERRED_ENVIRONMENT |
| Environment Deferred | API37 emulator graphics backend / TaskSnapshot | DEFERRED_ENVIRONMENT |
| Target Migration | target36 onBackPressed to OnBackInvokedDispatcher | NOT_IN_CURRENT_SCOPE |
| Target Migration | future target37 behavior | NOT_IN_CURRENT_SCOPE |
| Product Scope | dynamic AppWidget | NOT_IN_CURRENT_SCOPE |
| Product Scope | external runtime DEX | NOT_IN_CURRENT_SCOPE |

~~~text
C6-D01=DEFERRED_ENVIRONMENT
MEMORY_LIMITER_DYNAMIC=DEFERRED_ENVIRONMENT
MEMORY_LIMITER_RECOVERY=NOT_PROVEN_ENVIRONMENT
API37_RENDERER=KNOWN_EMULATOR_ENVIRONMENT_LIMITATION
~~~

C6-D01 is triggered when a real Android 17 environment with enabled Memory Limiter becomes
available: formal API37 Emulator, Android 17 Cuttlefish, controllable AOSP/GSI, or an
Android 17 ARM64 device with limiter enabled. The deferred work is real
MemoryLimiter termination plus CAS cleanup/recovery evidence. It must not be replaced by
fake config, kill -9, LMK simulation or framework modification.

## 13. Final Receipt

~~~text
C6-T01G
RESULT=BLOCKED
START_HEAD=48aad5810f4217d93991a576983b52b03cd5b142
FINAL_HEAD=HEAD

C6_T01F_FINAL_STATUS=PASS_WITH_ENVIRONMENT_DEFERRED
MEMORY_LIMITER_DEFERRED=DEFERRED_ENVIRONMENT
RENDERER_ENVIRONMENT_LIMITATION=KNOWN_EMULATOR_ENVIRONMENT_LIMITATION

API32_CORE=10/10 PASS
API33_CORE=10/10 PASS
API34_CORE=10/10 PASS
API35_CORE=10/10 PASS
API36_CORE=10/10 PASS
API37_CORE=DEFERRED_ENVIRONMENT

CORE_SMOKE_TOTAL=60
CORE_SMOKE_PASS=50
CORE_SMOKE_FAIL=0

UNIFIED_CAPABILITY_TOTAL=48
UNIFIED_CAPABILITY_PASS=35
UNIFIED_CAPABILITY_FAIL=0
UNIFIED_CAPABILITY_SKIP=0
UNIFIED_CAPABILITY_DEFERRED=7

VERSION_SPECIFIC_TOTAL=31
VERSION_SPECIFIC_PASS=27
VERSION_SPECIFIC_FAIL=0
VERSION_SPECIFIC_DEFERRED=2

GENERAL_DEFECTS_FOUND=1
API_SPECIFIC_DEFECTS_FOUND=1
HARNESS_DEFECTS_FOUND=2
PRODUCT_FIXES=2
HARNESS_FIXES=4

PACKAGE_LIFECYCLE_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
ACTIVITY_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
SERVICE_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
BROADCAST_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
PROVIDER_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
PENDING_INTENT_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
PMS_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
PERMISSION_APPOPS_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
JOB_ALARM_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
WEBVIEW_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
CLASSLOADER_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
NATIVE_BASIC_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT
PROCESS_RECOVERY_MATRIX=API32 PASS; API33 PASS; API34 PASS; API35 PASS; API36 PASS; API37 DEFERRED_ENVIRONMENT

COMPAT_ADAPTER_AUDIT=PASS
FALSE_PASS_CHECK=PASS
PERFORMANCE_SANITY=PASS

TARGET_SDK=35
COMPAT_OVERRIDES_RESET=PASS

HARNESS_TESTS=PASS (8/8)
GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
UNIT_TESTS=PASS
EVIDENCE_GIT_HYGIENE=PASS

REF_STATUS=UNCHANGED
REPORT=reports/t57-r03/c6/C6_T01G_CROSS_API_CLOSURE_REPORT.md
GIT_STATUS=CLEAN

C6_T01_STATUS=BLOCKED
NEXT_TASK=BLOCKED
~~~

Because API37 did not complete the current clean-headless final contract, the exact
conditional closure is C6-T01G=BLOCKED and C6-T01=BLOCKED. C6-T02A is not started.
