# C6-T02B — 16 KB Page Size Dynamic Validation

## 1. 结论与范围

- `RESULT=PASS`
- `START_HEAD=5b0a9d09cea7c12c67fef6671e962032d8432047`
- `FINAL_HEAD=HEAD`（由本任务唯一最终 Git commit 解析）
- 分支：`feature/t57-r03-va-pro-capability-campaign`
- 动态结论限定为：`Android 15 / API 35 / x86_64 / PAGE_SIZE=16384`。
- 本轮没有执行 ARM64 真机、小米、Companion32/cross-bitness、ARM32、OEM 或 API37；这些边界继续延期。

本报告严格区分 `C6-T02A` 的静态 readiness 与本任务的真实运行时证据：前者是 ELF/packaging，后者是在官方 16KB Android runtime 中安装、启动、加载 native、执行 JNI、恢复进程并完成核心矩阵。

## 2. 真实 16KB 环境

| 字段 | 值 |
|---|---|
| `SYSTEM_IMAGE` | `system-images;android-35;google_apis_ps16k;x86_64` |
| `SYSTEM_IMAGE_REVISION` | `5` |
| `AVD_NAME` | `C6_T02B_API35_16K_x86_64` |
| `AVD_MODE` | `HEADLESS` (`-no-window`) |
| ADB serial | `emulator-5558` |
| Android | `15` / API `35` |
| ABI / ABI list | `x86_64` / `x86_64,arm64-v8a` |
| `PAGE_SIZE` | `16384` |
| Build fingerprint | `google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys` |
| Kernel | `6.6.50-android15-8-g8adecb593e9b-ab12525588`, x86_64 |
| `NATIVE_16K_ENVIRONMENT` | `TRUE` |
| `PAGE_SIZE_COMPAT_MODE` | `NOT_ENABLED / NOT_OBSERVED` |

SDK Manager 的实际清单同时列出了 Play Store 与 Google APIs 的 16KB x86_64 image；本轮使用的实际 `source.properties` 是 Google APIs image，`Pkg.Revision=5`、`SystemImage.TagId=google_apis,page_size_16kb`、`Addon.VendorId=google`。AVD 使用独立配置，未修改 `C6_T01D_API35_GoogleApis_x86_64`。

环境真实性 gate 在安装和每个动态 runner 的 metadata 中均通过：`adb shell getconf PAGE_SIZE` 严格返回 `16384`。同时采集了 SDK、release、ABI/ABI list、fingerprint、`uname -a`、`/proc/meminfo` 与完整 `getprop`。

compatibility gate 的证据是：官方 16KB image tag、真实 kernel/userspace page size `16384`、`ro.product.cpu.pagesize.max=16384`、`ro.product.build.no_bionic_page_size_macro=true`，以及最终 Host/Guest package dump 中 `primaryCpuAbi=x86_64`、`extractNativeLibs=false`；package dump 未发现 page-size compatibility/fallback marker。日志中 Android 的 `topActivityInSizeCompat=false` 属于显示尺寸兼容状态，不作为 page-size compatibility 结论。

证据：

- `out/verification/t57-r03/c6-t02b-16k/sdk/`：SDK Manager 清单、16KB `source.properties`、`package.xml`。
- `out/verification/t57-r03/c6-t02b-16k/avd/`：AVD 配置和 AVD 列表。
- `out/verification/t57-r03/c6-t02b-16k/environment/`：16KB 环境采集、compatibility/package dump。

## 3. Baseline-first 与根因收敛

首次启动 16KB AVD 后先以 `START_HEAD` 原样运行构建和核心 baseline；原始结果没有被覆盖：

| Gate | 初始结果 |
|---|---:|
| Harness tests | `16/16 PASS` |
| Gradle projects | `PASS` |
| `assembleDebug` | `PASS` |
| Unit tests | `PASS` |
| Android Gradle/AIDL/CMake/APK build gate | `PASS` |
| `C6-T02A` ABI/native companion checks | `PASS` |
| 16KB initial `S01-S10` | `10/10 PASS` |

初始扩展 runner 保留为独立证据，结果为 `12 total / 7 PASS / 4 FAIL / 1 NOT_IN_CURRENT_SCOPE`。四个失败并非 16KB native 失败：C3-T02/C3-T03 的异步 Guest probe 已真实产生全部 native result marker，但 debug wrapper 只接受 `LAUNCH_PASS`；NativeLoader Activity 路径不会产生 Service refresh marker；adversarial service 的真实 prepare 状态为 degraded。诊断完成后将既有通用 debug surface 接受 truthful 的 `LAUNCH_ACCEPTED`/degraded 状态，并让 runner 以 native result marker 和明确的 expected limitation 做 fail-closed 判定。

这些改动没有修改 native 产品实现、没有加入 page-size 特判、sleep、fallback 或 retry 掩盖；它们是既有统一验证 surface 的异步状态契约和页大小 metadata 能力收敛。

## 4. 16KB 核心动态结果

最终核心 run：`c6-t02b-16k-core-final`，`16K_CORE_TOTAL=10`、`16K_CORE_PASS=10`、`16K_CORE_FAIL=0`。

| Case | 结果 | 覆盖 |
|---|---|---|
| S01 Host install/launch | PASS | Host 安装、进程启动、readiness、真实 first frame |
| S02 Guest import/add | PASS | parse、staging、copy、registration、add |
| S03 cold launch | PASS | process creation、bindApplication、ClassLoader、native path、`FIRST_FRAME_DRAWN` |
| S04 warm launch | PASS | task/process reuse、Activity reuse、first frame |
| S05 Service | PASS | start/stop/bind 生命周期与 native hook refresh |
| S06 Broadcast | PASS | receiver、PendingResult、completion、runtime bridge |
| S07 Provider | PASS | authority、acquisition、query、cursor/readback |
| S08 PendingIntent | PASS | creator identity、IntentSender、Guest route、component execution |
| S09 package lifecycle | PASS | add → launch → clear → relaunch → delete → re-add → relaunch |
| S10 process recovery | PASS | Guest PID 26867 退出，恢复 PID 27007，恢复 first frame |

S10 使用现有 harness 的安全 force-stop fallback 完成物理进程终止，记录了 `pid_exited=true`、新 PID 和 recovery frame；没有将旧进程仍存活当作 PASS。S01-S10 logcat 均未出现 linker alignment error、ELF load failure、page-size error、SIGBUS、SIGSEGV 或 relocation failure marker。

## 5. 16KB 扩展能力矩阵

最终扩展 run：`c6-t02b-16k-capabilities-final3`。

| 维度 | 结果 |
|---|---:|
| 总 capability cases | `12` |
| PASS | `10` |
| FAIL | `0` |
| `EXPECTED_LIMITATION` | `1` |
| `NOT_IN_CURRENT_SCOPE` | `1`（AppWidget） |

- `CAP-NATIVE-LOADER-JNI-HOOKS=PASS`：Guest base/dex ClassLoader、native directory、native load diagnostic、JNI load 和 Guest `dlopen` 均有 marker。
- `CAP-NATIVE-PROC-FD=PASS`：C3-T02 FS/PROC/NET/FD markers 完整；`ERROR` 数为 0。`FD-003=BLOCKED_BY_POLICY` 与 `RAW-001=BYPASS_CONFIRMED` 是既有能力边界的结构化结果，不被误报为 native load failure。
- `CAP-NATIVE-MEDIA-16K=PASS`：`C3_T03_NATIVE_MEDIA_RESULT_END status=PASS abi=x86_64 pageSize=16384`；native buffer/surface、image、codec、cleanup 与 late `dlopen` 均 PASS。
- `CAP-NATIVE-ADVERSARIAL-BOUNDARY=EXPECTED_LIMITATION`：Service 在 `IN_SANDBOX` 中完成 NATIVE-ADV-001..010，随后真实 raw syscall 触发 `Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP)`。该 hostile raw-syscall 边界被明确保留为 limitation，不被转换为产品 PASS，也没有因此形成 `PRODUCT_DEFECT_16K`。
- `CAP-SPLIT-APK-CLASSLOADER=PASS`：base/feature 均报告 `classLoaded=true`。
- `CAP-WEBVIEW-CLASSLOADER-NATIVE=PASS`：Guest WebView provider、data/classloader/native provider 路径与 `NATIVE_LOAD JNI_LOADED` 均完成；不是只检查 constructor。
- Framework capability case 为 PASS，覆盖 Provider、Service、Broadcast、PendingIntent、package identity/metadata、cross-component Binder 路径。

## 6. Native runtime inventory

以 T02A 的 29 个 ELF occurrence 为静态全集，针对本轮 x86_64 动态路径区分 A/B/C/D。按 unique load contract 计，`NATIVE_EXPECTED_LOAD_TOTAL=4`，`NATIVE_ACTUAL_LOAD_PASS=4`，`NATIVE_ACTUAL_LOAD_FAIL=0`。

| Library | 分类 | EXPECTED_LOAD | ACTUAL_LOAD | 证据 / 结果 |
|---|---|---:|---:|---|
| `libcontrolled_sandbox_native.so` | A，CAS Host runtime | YES | YES | Android nativeloader `.../lib/x86_64/libcontrolled_sandbox_native.so ... ok`；PASS |
| `libcontrolled_sandbox_fixture.so` | A，Guest native fixture runtime | YES | YES | nativeloader `ok`、Guest revision `lib/x86_64`、`CS_NATIVE_BIND dlopen`；PASS |
| `libfixture_adv_payload.so` | C，本轮 C3 late-dlopen/adversarial fixture | YES | YES | `dlopen`/`android_dlopen_ext` resolved、`lateDlopen.status=PASS`；PASS |
| `libcas_native_enf.so` | C，既有 debug Native Enforcement POC | YES | YES | 独立 `native-enforcement` 命令 nativeloader `ok`，`jniAvailable=true`、`NATIVE_ENFORCEMENT_RAN`；PASS |
| `libcontrolled_sandbox_fixture_scale.so` | C，fixture-only scale case | NO | N/A | 不属于本轮当前动态 capability path，未虚报 |
| `libcontrolled_sandbox_native32.so` | D，Companion32 | NO | N/A | Companion32/cross-bitness 明确延期 |
| 其他 ABI occurrence | B | NO | N/A | 本轮限定 x86_64 |

## 7. NativeLoader、JNI、页假设与 hook

- `NATIVE_LOADER=PASS`：`PROBE nativeLoadWrap installed=1`、`nativeLoadDiagnostic=true`、`LOADER site=guest.base/guest.dex/process.install/process`，Guest `nativeDirs` 指向当前 package revision 的 `lib/x86_64`；Host CAS runtime、Guest fixture 与 late payload 均从真实路径加载。
- `JNI=PASS`：Guest 日志出现 `NATIVE_LOAD JNI_LOADED`；C3-T03 的 Java→native 与 native→Java/media 结果 PASS；Native Enforcement 隔离进程报告 `abi=x86_64,jniAvailable=true`，没有 silently fallback。
- `MMAP_MPROTECT_AUDIT=PASS`：`native_hook.cpp` 的 page base、`mprotect` 使用 `sysconf(_SC_PAGESIZE)`；C3-T03 在运行时报告 `nativePageSize=16384`。扫描记录 `native_loader.cpp:142` 一个仅在 `sysconf` 失败时使用的 `4096U` guarded fallback；本轮 sysconf 成功，未走 fallback。其余 `4096` 是 proc buffer、crash path length、测试数据/业务上限，不是 page alignment。
- `NATIVE_HOOK_16K=PASS`：S05/S07 logcat 有 `REFRESH stage=FRAMEWORK_SERVICE_START`/`PROVIDER_CREATE refreshed=true`，`patched=50` 或 `patched=10`，`targets` 匹配，`patchFailures=0`；没有 hook install/refresh failure marker。
- `CLASSLOADER=PASS`：GuestClassLoader、Guest dex PathClassLoader、Application class 及 process restart 均有证据。
- `SPLIT_APK=PASS`、`WEBVIEW=PASS`、`BINDER_IPC=PASS`、`PACKAGE_LIFECYCLE=PASS`、`PROCESS_RECOVERY=PASS`。

## 8. Static regression and APK verification

本轮最终真实 build artifacts 上重新执行静态审计：

```text
ABI_AUDIT_RESULT=PASS
ABI_MATRIX_VALIDATOR=PASS
NATIVE_INVENTORY_VALIDATOR=PASS
APK_AAR_TOTAL=13
NATIVE_LIBRARY_TOTAL=29
ELF_TOTAL=29
ELF_PASS=29
ELF_FAIL=0
PAGE_SIZE_16K_STATIC_TOTAL=29
PAGE_SIZE_16K_STATIC_PASS=29
PAGE_SIZE_16K_STATIC_FAIL=0
APK_NATIVE_PACKAGING=25/25 PASS
RPATH_RUNPATH_ISSUES=0
MISSING_NATIVE_DEPENDENCIES=0
EXPORT_CONTRACT_ISSUES=0
```

本轮实际 `zipalign -c -v -P 16 4` 检查 9 个 APK，`ZIPALIGN_TOTAL/PASS/FAIL=9/9/0`。ABI matrix accounting 为 `15 total / BUILT=6 / COMPANION_ONLY=2 / DECLARED_NOT_BUILT=0 / NOT_DECLARED=0 / TEST_ONLY=7`。最终 Harness tests 为 `16/16 PASS`，native ABI companion guard 为 PASS。

## 9. API35 4KB 对照与性能 sanity

在未改配置的既有 `C6_T01D_API35_GoogleApis_x86_64`（独立 `emulator-5560`）上，以相同最终 APK 和相同统一 S01-S10 harness 执行对照；`getconf PAGE_SIZE=4096`，结果 `API35_4K_REGRESSION_TOTAL/PASS/FAIL=10/10/0`。因此没有出现“4KB PASS、16KB FAIL”的 `PRODUCT_DEFECT_16K` 模式。

单轮、非性能专项的 duration sanity 如下；仅用于数量级检查：

| 指标 | 16KB | 4KB |
|---|---:|---:|
| import/add（S02） | 12,465 ms | 21,451 ms |
| cold launch（S03） | 11,465 ms | 20,486 ms |
| warm launch（S04） | 5,377 ms | 7,338 ms |
| S01-S10 合计 | 210,063 ms | 250,030 ms |

观察到的单轮结果没有几十秒/分钟级相对退化；不把 16KB 比 4KB 更快解释为性能结论。

## 10. Fix、False-pass 与剩余边界

- `PRODUCT_DEFECT_16K_FOUND=NO`。
- `PRODUCT_FIXES`：无 native 产品修复；`DebugCommandActivity` 补齐异步 C3 `LAUNCH_ACCEPTED` 与 degraded prepare 的 truthful contract；统一 `run_rd_smoke.py`/`run_api33_capabilities.py` 增加 `--expected-page-size`；capability runner 明确记录 native load、native media 与 expected hostile limitation。没有 `pageSize==16384` 散落特判、没有 sleep、没有吞 native load 异常。
- `FALSE_PASS_CHECK=PASS`：16KB image、runtime `PAGE_SIZE=16384`、native media `pageSize=16384`、真实 nativeloader/JNI/dlopen/hook/first-frame/recovery 证据相互一致；4KB 对照使用另一 AVD，未复用或切换同一 AVD。
- `API37_STATUS=DEFERRED_ENVIRONMENT`。
- `ARM64_DYNAMIC=DEFERRED`、`COMPANION32_DYNAMIC/CROSS_BITNESS=DEFERRED`、`ARM32_SCOPE=DEFERRED`；不能从本报告外推 ARM64 16KB PASS。

## 11. Evidence / Git hygiene

完整 logcat、native loader trace、native enforcement result、APK/ELF/zipalign/readelf evidence、环境采集和截图均在被忽略的 `out/verification/` 下；本报告只引用 compact result，不把大日志内联到 Git。最终 gate 检查 `ref/` 无差异、没有 APK/.so/logcat/AVD snapshot/readelf extraction 被跟踪，`git diff --check` 通过，commit 后工作区 CLEAN 且本地 HEAD 与 remote HEAD 一致。

## 12. Authoritative receipt

```text
C6-T02B
RESULT=PASS
START_HEAD=5b0a9d09cea7c12c67fef6671e962032d8432047
FINAL_HEAD=HEAD (resolved by final single-commit Git gate)
SYSTEM_IMAGE=system-images;android-35;google_apis_ps16k;x86_64
SYSTEM_IMAGE_REVISION=5
AVD_NAME=C6_T02B_API35_16K_x86_64
AVD_MODE=HEADLESS
ANDROID_VERSION=15
API_LEVEL=35
ABI=x86_64
PAGE_SIZE=16384
BUILD_FINGERPRINT=google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys
NATIVE_16K_ENVIRONMENT=TRUE
PAGE_SIZE_COMPAT_MODE=NOT_ENABLED
ZIPALIGN_TOTAL=9
ZIPALIGN_PASS=9
ZIPALIGN_FAIL=0
STATIC_ELF_TOTAL=29
STATIC_ELF_PASS=29
STATIC_ELF_FAIL=0
BASELINE_CORE_TOTAL=10
BASELINE_CORE_PASS=10
BASELINE_CORE_FAIL=0
FINAL_CORE_TOTAL=10
FINAL_CORE_PASS=10
FINAL_CORE_FAIL=0
NATIVE_EXPECTED_LOAD_TOTAL=4
NATIVE_ACTUAL_LOAD_PASS=4
NATIVE_ACTUAL_LOAD_FAIL=0
NATIVE_LOADER=PASS
JNI=PASS
MMAP_MPROTECT_AUDIT=PASS (active path sysconf=16384; guarded fallback recorded)
NATIVE_HOOK_16K=PASS (patchFailures=0)
CLASSLOADER=PASS
SPLIT_APK=PASS
WEBVIEW=PASS
BINDER_IPC=PASS
PACKAGE_LIFECYCLE=PASS
PROCESS_RECOVERY=PASS
HARD_CODED_4K_FINDINGS=1 guarded native_loader.cpp fallback; no active 4KB page assumption
PRODUCT_DEFECT_16K_FOUND=NO
PRODUCT_FIXES=debug async-status contract + generic expected-page-size/capability evidence; no native product fix
API35_4K_REGRESSION_TOTAL=10
API35_4K_REGRESSION_PASS=10
API35_4K_REGRESSION_FAIL=0
PERFORMANCE_SANITY=PASS; no order-of-magnitude regression observed
ABI_ELF_VALIDATOR=PASS
HARNESS_TESTS=PASS (16/16)
GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
UNIT_TESTS=PASS
FALSE_PASS_CHECK=PASS
EVIDENCE_GIT_HYGIENE=PASS
API37_STATUS=DEFERRED_ENVIRONMENT
REF_STATUS=UNCHANGED
REPORT=reports/t57-r03/c6/C6_T02B_16KB_DYNAMIC_VALIDATION_REPORT.md
GIT_STATUS=CLEAN; local==remote after final push
NEXT_TASK=C6-T02C
```
