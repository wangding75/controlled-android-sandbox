# C6-T02C — ARM64 Physical Device Dynamic Validation

日期：2026-09-07（Asia/Shanghai）
分支：feature/t57-r03-va-pro-capability-campaign

## 1. 最终结论

C6-T02C=PASS。

设备解锁并保持可交互后，按 ARM64 physical 专用 lane 完成最终动态复核：

- 核心 S01-S10：10/10 PASS，0 FAIL；
- capability extension：9 PASS、2 EXPECTED_LIMITATION、1 NOT_IN_CURRENT_SCOPE、0 FAIL；
- ARM64 native load contract：4/4 PASS；
- static/build/Harness gates：全部 PASS；
- shared native 改动后的 API35 x86_64 回归：S01-S10 10/10 PASS。

此前因 HyperOS 安装授权导致的 BLOCKED_ENV 是中间环境状态，已被最终解锁设备上的成功安装和最终动态证据覆盖；它不是产品缺陷。没有执行 Companion32、cross-bitness、ARM32 或 API37，也没有自动执行 C6-T02D。

## 2. 设备真实性与任务边界

最终设备由 adb devices -l 动态发现，serial 不含 emulator，且设备在线：

~~~text
START_HEAD=f92a9c0c9c5d9c9c7c27094e1cf22e4b8c613f29
BRANCH=feature/t57-r03-va-pro-capability-campaign
DEVICE_SERIAL=192.168.137.210:33259
MANUFACTURER=Xiaomi
MODEL=25019PNF3C
PRODUCT_DEVICE=xuanyuan
ANDROID_VERSION=16
API_LEVEL=36
PRIMARY_ABI=arm64-v8a
ABI_LIST=arm64-v8a
BUILD_FINGERPRINT=Xiaomi/xuanyuan/xuanyuan:16/BP2A.250605.031.A3/OS3.0.306.0.WOACNXM:user/release-keys
KERNEL=Linux localhost 6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k #1 SMP PREEMPT Mon Sep 1 06:06:55 UTC 2025 aarch64 Toybox
PAGE_SIZE=4096
ARM64_GATE=PASS
PHYSICAL_DEVICE_GATE=PASS
DEVICE_UNLOCKED_FOR_FINAL_RUN=YES
~~~

PAGE_SIZE=4096 仅作为本设备运行时记录；本轮不宣称 ARM64+16 KB 动态覆盖。16 KB 动态结论来自 C6-T02B 的独立 x86_64 环境，不外推为本轮 ARM64 证据。

设备原始 metadata 位于：

out/verification/c6-t02c-arm64-core-final-3-20260907/run.json

## 3. Baseline-first 与环境阻断的正确分类

未修改 source 的 START_HEAD 先通过：

| Gate | Result |
|---|---|
| gradlew projects | PASS |
| gradlew assembleDebug | PASS |
| gradlew test | PASS |

最初的默认 runner 越界尝试安装 sandbox-companion32，HyperOS 返回 INSTALL_FAILED_USER_RESTRICTED。该历史结果保留在 out/verification/t57-r03/c6-t02c-arm64/，并按 OEM_ENVIRONMENT / HARNESS_SCOPE 重新分类；它没有作为最终 ARM64 product failure 计入。

随后使用 arm64-physical lane：

- 只安装 Host 和 ARM64 basic fixture；
- 排除 Companion32、fixture32、cross-bitness；
- S08 只使用同 Guest 的 PendingIntent/Binder 路径；
- 安装环境失败和依赖级联均 fail-closed，不生成伪造 product defect。

最终设备解锁后 Host 与 basic fixture 均安装成功，完成了完整 Guest 动态路径。

## 4. ARM64 核心 S01-S10

权威运行：

out/verification/c6-t02c-arm64-core-final-3-20260907/run.json

| Case | Result | Duration |
|---|---|---:|
| S01 Host build/install/launch | PASS | 8462 ms |
| S02 Guest import/add | PASS | 6676 ms |
| S03 Cold launch/first frame | PASS | 8824 ms |
| S04 Warm launch/reuse | PASS | 5832 ms |
| S05 Service lifecycle | PASS | 19195 ms |
| S06 Broadcast dispatch | PASS | 9291 ms |
| S07 Provider access | PASS | 9363 ms |
| S08 PendingIntent path | PASS | 13935 ms |
| S09 Package lifecycle | PASS | 54011 ms |
| S10 Process death/recovery | PASS | 13614 ms |

~~~text
FINAL_CORE_TOTAL=10
FINAL_CORE_PASS=10
FINAL_CORE_FAIL=0
FINAL_CORE_BLOCKED=0
~~~

关键动态证据包括 Host/Guest 可见 first frame、Guest process 与 bindApplication、Service/Broadcast/Provider 生命周期、同 Guest PendingIntent/Binder creator identity、package clear/delete/re-add，以及 S10 中旧 PID 退出、新 PID 建立并出现 recovery first frame。

## 5. Native loader、JNI、hook、syscall 与指针宽度

ARM64 native expected-load set 共四个独立 runtime contract，最终 4/4 PASS：

| Library / contract | Result | Evidence |
|---|---|---|
| libcontrolled_sandbox_native.so | PASS | ARM64 nativeloader 成功，路径为 lib/arm64-v8a |
| libcontrolled_sandbox_fixture.so | PASS | Guest NATIVE_LOAD JNI_LOADED，dlopen resolved 到 lib/arm64-v8a |
| libfixture_adv_payload.so | PASS | lateDlopen、dlopen 与 android_dlopen_ext 均 resolved |
| libcas_native_enf.so | PASS | 独立 debug-only isolated POC，abi=arm64-v8a、jniAvailable=true、NATIVE_ENFORCEMENT_RAN |

最终 Guest log 中观察到：

~~~text
CS_NATIVE_BIND: PROBE nativeLoadWrap installed=1
CS_NATIVE_BIND: PROBE nativeLoadDiagnostic=true ... translatedAbi=false
CS_NATIVE_BIND: LOADER site=guest.base ... guestLoader=true
CS_NATIVE_BIND: LOADER site=guest.dex ... nativeDirs=[.../lib/arm64-v8a]
CS_NATIVE_BIND: LOADER site=guest.process ...
CS_NATIVE_BIND: CLASS site=application ...
CS_NATIVE_BIND: SO api=dlopen requested=libcontrolled_sandbox_fixture.so resolved=.../lib/arm64-v8a/...
~~~

ARM64 hook refresh 记录 patched entries 且 patchFailures=0；未出现 NATIVE_FILE_HOOK_INSTALL_FAILED、NATIVE_FILE_HOOK_REFRESH_FAILED 或 NATIVE_PROCESS_LIFETIME_HOOK_*_FAILED。Guest loader/classloader、JNI native load、pointer-width/runtime ABI 和 process death/recovery 均由动态证据覆盖。

native-enforcement 证据目录：

out/verification/c6-t02c-arm64-core-final-3-20260907/native-enforcement-2/

该 POC 明确 production=false；它证明 ARM64 isolated native load、broker capability、direct filesystem/network denial 与 seccomp feasibility，不宣称已经把 debug-only POC 当作生产 enforcement。

~~~text
NATIVE_EXPECTED_LOAD_TOTAL=4
NATIVE_ACTUAL_LOAD_PASS=4
NATIVE_ACTUAL_LOAD_FAIL=0
NATIVE_LOADER_ARM64=PASS
JNI_ARM64=PASS
NATIVE_HOOK_ARM64=PASS
POINTER_WIDTH_DYNAMIC=PASS
CLASSLOADER_ARM64=PASS
RAW_SYSCALL_ARM64=PASS_WITH_EXPECTED_BOUNDARY_LIMITATION
~~~

raw/seccomp adversarial boundary 的 BYPASS_CONFIRMED 观察仍保留为 EXPECTED_LIMITATION；它没有被转换为 PASS，也未形成 ARM64-specific defect。

## 6. Capability extension

权威矩阵：

out/verification/c6-t02c-arm64-capabilities-final-20260907/capability-matrix.json

| Capability | Result |
|---|---|
| PMS permission/AppOps/attribution | PASS |
| Framework transport/identity | PASS |
| Scheduling notification/alarm/job/FGS | PASS |
| Network/media/DNS/VPN | PASS |
| Environment shortcut/launcher | PASS |
| Split APK/ClassLoader | PASS |
| AppWidget dynamic | NOT_IN_CURRENT_SCOPE |
| WebView/ClassLoader/native | PASS |
| Native loader/JNI/hooks | PASS |
| Native proc/fd | PASS |
| Native media 16K | EXPECTED_LIMITATION |
| Native adversarial boundary | EXPECTED_LIMITATION |

~~~text
CAPABILITY_TOTAL=12
CAPABILITY_PASS=9
CAPABILITY_FAIL=0
CAPABILITY_SKIP=0
CAPABILITY_EXPECTED_LIMITATION=2
CAPABILITY_NOT_IN_CURRENT_SCOPE=1
~~~

Split evidence 同时包含 base.apk 和 split_fixtureSplitFeature.apk 路径，且 BASE_CREATE featureClassLoaded=true、FEATURE_CREATE classLoaded=true。WebView/native path 观察到 JNI_LOADED。Network/proc/fd/framework required markers 均通过，DNS 的 EAI_NODATA 被识别为该设备运行时的兼容性限制而非失败。

CAP-NATIVE-MEDIA-16K 保留真实的末尾 status=FAIL：该设备 PAGE_SIZE=4096，lateDlopen、native surface 与 image path PASS，但 codec 为 ENVIRONMENT_NOT_AVAILABLE，因此矩阵状态为 EXPECTED_LIMITATION，未作 false PASS。CAP-NATIVE-ADVERSARIAL-BOUNDARY 同样保留 raw/seccomp boundary limitation；必要 service/case markers 通过。

## 7. Static、build 与跨架构回归

当前输出的 ABI/ELF audit：

~~~text
ABI_AUDIT_RESULT=PASS
APK_AAR_TOTAL=13
NATIVE_LIBRARY_ELF_TOTAL=29
ELF_PASS=29
ELF_FAIL=0
PAGE_SIZE_16K_STATIC_PASS=29
APK_NATIVE_PACKAGING_PASS=25
APK_NATIVE_PACKAGING_FAIL=0
~~~

当前 source 状态下的 Gradle assembleDebug、unit tests 和 unified Harness self-tests 均通过，Harness 为 16/16。shared native 调整后重新启动并验证 API35 x86_64 4 KB AVD：

~~~text
X86_REGRESSION_INSTANCE=C6_T01D_API35_GoogleApis_x86_64
X86_REGRESSION=PASS
X86_CORE_TOTAL=10
X86_CORE_PASS=10
X86_CORE_FAIL=0
~~~

x86_64 AVD 已通过 adb emu kill 正常停止。该回归用于证明共享 procfs/DNS 变更没有破坏既有 x86 lane，不改变本轮 ARM64 physical 结论。

## 8. 实现变更与缺陷分类

本轮实际保留的 source 变更为通用、受边界约束的运行时/验证修正：

- procfs/maps 与 proc snapshot 的 materialization cap 从 2 MiB 提升为 8 MiB，仍有明确上限，以容纳真实 ARM64 设备较大的 smaps snapshot；
- DNS probe 接受 EAI_NODATA 作为设备运行时的等价限制；
- capability runner 让真实 errors 优先于 expected limitation，补充 physical framework marker，并要求 ARM64 media 的 late-dlopen/surface/image 子路径通过后才把 codec 环境限制归类为 expected limitation。

没有 OEM-specific patch，没有修改系统设置、root、SELinux、system partition 或真实用户应用数据。

~~~text
ARM64_SPECIFIC_DEFECTS=NONE
GENERAL_DEFECTS=NONE
OEM_SPECIFIC_FINDINGS=codec ENVIRONMENT_NOT_AVAILABLE; no Xiaomi OEM convergence claim
PRODUCT_FIXES=GENERAL_BOUNDED_NATIVE_PROBE_AND_TRUTHFUL_VERIFICATION_CONTRACTS
PERFORMANCE_SANITY=PASS (smoke execution completed without timeout/ANR; not a benchmark)
ARM64_16KB_STATUS=NOT_PROVEN
X86_64_16KB_STATUS=PASS (C6-T02B)
API37_STATUS=DEFERRED_ENVIRONMENT
COMPANION32_STATUS=NOT_EXECUTED
CROSS_BITNESS_STATUS=NOT_EXECUTED
ARM32_STATUS=NOT_EXECUTED
~~~

## 9. Evidence hygiene、Git gate 与下一任务

原始设备 metadata、runner JSON、capability matrix、logcat 和 static audit 均留在被忽略的 out/verification/ 下；没有 APK、.so、dump、logcat 或 screenshot 被加入 Git。ref/ 未修改。

最终交付必须满足并已在收口阶段复核：

~~~text
REPORT=reports/t57-r03/c6/C6_T02C_ARM64_PHYSICAL_DYNAMIC_VALIDATION_REPORT.md
COMMIT_SUBJECT=C6-T02C: validate ARM64 runtime on physical device
COMMITS_SINCE_START_HEAD=1
REF_STATUS=UNCHANGED
EVIDENCE_GIT_HYGIENE=PASS
GIT_STATUS=CLEAN
LOCAL_REMOTE_HEAD=一致
NEXT_TASK=C6-T02D
T02D_AUTO_EXECUTED=NO
~~~

## 10. Authoritative receipt

~~~text
C6-T02C
RESULT=PASS
START_HEAD=f92a9c0c9c5d9c9c7c27094e1cf22e4b8c613f29
FINAL_HEAD=HEAD (exact SHA recorded by final Git gate)

DEVICE_SERIAL=192.168.137.210:33259
MANUFACTURER=Xiaomi
MODEL=25019PNF3C
ANDROID_VERSION=16
API_LEVEL=36
PRIMARY_ABI=arm64-v8a
ABI_LIST=arm64-v8a
PAGE_SIZE=4096

FINAL_CORE_TOTAL=10
FINAL_CORE_PASS=10
FINAL_CORE_FAIL=0
FINAL_CORE_BLOCKED=0

CAPABILITY_TOTAL=12
CAPABILITY_PASS=9
CAPABILITY_FAIL=0
CAPABILITY_EXPECTED_LIMITATION=2
CAPABILITY_NOT_IN_CURRENT_SCOPE=1

NATIVE_EXPECTED_LOAD_TOTAL=4
NATIVE_ACTUAL_LOAD_PASS=4
NATIVE_ACTUAL_LOAD_FAIL=0

ABI_ELF_VALIDATOR=PASS
ELF_PASS=29/29
APK_NATIVE_PACKAGING=PASS (25/25)
HARNESS_TESTS=PASS (16/16)
GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
UNIT_TESTS=PASS
X86_REGRESSION=PASS (10/10)
FALSE_PASS_CHECK=PASS
REF_STATUS=UNCHANGED
EVIDENCE_GIT_HYGIENE=PASS
GIT_STATUS=CLEAN
NEXT_TASK=C6-T02D
~~~

本轮到此停止，不自动执行 C6-T02D。
