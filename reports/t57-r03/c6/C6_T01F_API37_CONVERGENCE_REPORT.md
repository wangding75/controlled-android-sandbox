# C6-T01F — Android API37 / Android 17 Platform Convergence

## 1. 结论与可复核字段

任务严格结论如下：

RESULT=BLOCKED
START_HEAD=50648d6a247d4c05c9777cd35d0d45603e4c909b
FINAL_HEAD=HEAD
BRANCH=feature/t57-r03-va-pro-capability-campaign
COMMIT_MESSAGE=C6-T01F: converge Android API37 platform behavior
REPORT_PATH=reports/t57-r03/c6/C6_T01F_API37_CONVERGENCE_REPORT.md
PROGRESS_PATH=docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md
AVD_MODE=VISIBLE_GRAPHICS_USER_APPROVED_EXCEPTION
PASS_GATE=NOT_MET
NEXT_TASK=BLOCKED

API37 runtime 兼容修复、统一 S01-S10 smoke、supported capability suite、MessageQueue
受控探针、static-final/JNI 审计和跨版本回归均已完成。最终仍不能标记 PASS，原因是：

1. 当前 API37 Google APIs x86_64 镜像只有约 2 GiB RAM，am memory-limiter status
   报告 disabled，设备没有可用的真实 memory-limiter 限制，因此 M05 的受限杀进程、
   cleanup/recovery 证据无法建立。
2. API37 默认渲染器在 TaskSnapshotPersist/SurfaceFlinger 路径触发
   Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma，并导致 system_server
   崩溃。使用可见图形和临时仿真器 workaround 后的 10/10 只作为产品行为证据，
   不作为 clean default renderer gate 的 PASS。

因此不进入 C6-T01G。

## 2. 正式边界、设备和启动模式

| 项目 | 实际值 |
|---|---|
| API37 AVD | C6_T01F_API37_GoogleApis_x86_64 |
| serial | emulator-5574 |
| Android/API | Android 17 / API37 |
| image | stable Google APIs x86_64, system-images;android-37.0;google_apis;x86_64 |
| image revision/tag | 6 / google_apis,ai_glasses_compatible |
| manufacturer/model | Google / sdk_gphone64_x86_64 |
| ABI / ABI list | x86_64 / x86_64, arm64-v8a |
| runtime PAGE_SIZE | 4096 |
| RAM | 2013496 kB，约 2 GiB |
| build incremental | 15611780 |
| build fingerprint | google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys |
| kernel | Linux localhost 6.12.58-android16-6-gccafb60de224-ab14828483 #1 SMP PREEMPT Tue Feb 3 03:46:47 UTC 2026 x86_64 Toybox |
| formal compile/target/min | 36 / 35 / 26 |

任务要求的默认 AVD 启动配置是 -no-window；本次 S01-S10 需要 WMS、
SurfaceFlinger 和 first-frame 证据，用户已明确允许在依赖图形页面时取消 headless。
因此本次采用 AVD_MODE=VISIBLE_GRAPHICS_USER_APPROVED_EXCEPTION。没有使用 GUI 点击、
坐标、窗口标题或人工操作，测试控制仍全部通过 adb、instrumentation、system service
和 verification harness 完成。任务结束时 API37 AVD 已停止，临时 data 分区和
snapshot/compat workaround 已清理。

正式 Gradle 配置保持 compileSdk=36、HOST_TARGET_SDK=35、FIXTURE_TARGET_SDK=35。
没有把 compileSdk37 或 targetSdk37 写入 release configuration；target37 只通过
单 change 的 compat probe 验证。

## 3. Baseline-first

在 START_HEAD 上先执行了没有生产源码修改的 API37 baseline，保留在：

out/verification/c6-t01f-api37-baseline-complete/

API37 初始 baseline：

API37_BASELINE_TOTAL=10
API37_BASELINE_PASS=0
API37_BASELINE_FAIL=10
API37_BASELINE_SKIP=0
API37_BASELINE_RESULT=PASS_WITH_DISCOVERED_PRODUCT_DEFECT

首个明确环境/fixture 边界是 x86_64 API37 image 安装 sandbox-companion32 时
INSTALL_FAILED_NO_MATCHING_ABIS；随后级联为 debug activity 未启动。runner 已改为先读取
ro.product.cpu.abilist，在没有 32-bit ABI 时显式记录 UNSUPPORTED_PLATFORM 并省略
Companion32 lane。该处理没有把 unsupported ABI 伪造成产品 PASS，也没有改变 S01-S10
的严格断言。

## 4. Root Cause Matrix

| Defect/contract | API32 | API33 | API34 | API35 | API36 | API37 | Lane | Root cause | Classification | Fix |
|---|---|---|---|---|---|---|---|---|---|---|
| Build static-final projection | PASS | PASS | PASS | PASS | PASS | targeted/final PASS | A_RUNTIME | API37 不应继续依赖 framework static-final 写入 | PRODUCT_DEFECT_API37_RESOLVED | BuildIdentityHook 只保留 API<=36 的旧路径，API37+ 禁止 static-final projection |
| InputMethodInfoSafeList | PASS | PASS | PASS | PASS | PASS | baseline FAIL -> final PASS | A_RUNTIME | API37 framework-owned list 需要 create(List) | PRODUCT_DEFECT_API37_RESOLVED | 精确支持 InputMethodInfoSafeList.create(List)，保留旧版本 fallback |
| WebViewUpdateManager / UiModeManager | PASS | PASS | PASS | PASS | PASS | targeted/final PASS | A_RUNTIME | manager 必须以 Guest Context 建立，避免 Host context 侵入 | PRODUCT_DEFECT_API37_RESOLVED | projected Binder interface + Guest Context manager override；失败时 rollback/fail closed |
| AppOps / AttributionSource | PASS | PASS | PASS | PASS | PASS | capability PASS | A_RUNTIME | API37 manager、静态 service 和嵌套 AttributionSourceState 的 Host attribution 需要统一识别 | PRODUCT_DEFECT_API37_RESOLVED | manager/context/service hook 与 nested attribution chain 的集中适配 |
| Service callback/lifecycle | PASS | PASS | PASS | PASS | PASS | S05/S08 final PASS | A_RUNTIME | serviceDoneExecuting、publishService、unbindFinished 的 API37 参数形状变化 | PRODUCT_DEFECT_API37_RESOLVED | 按 API gate 适配 binder token、BindServiceData.bindToken 和 filtered Intent |
| ActivityInfo hardware flag | PASS | PASS | PASS | PASS | PASS | final PASS | A_RUNTIME | Guest application metadata 未传播 FLAG_HARDWARE_ACCELERATED | PRODUCT_DEFECT_API37_RESOLVED | VirtualPackageMetadata 与 ActivityFieldBridge 传播真实 flag |
| Wi-Fi ParceledListSlice | PASS | PASS | PASS | PASS | PASS | capability PASS | A_RUNTIME | API37 使用 shaded ParceledListSlice constructor | PRODUCT_DEFECT_API37_RESOLVED | typed list/slice adapter 与 exact native exemption |
| Readiness/screenshot/post-marker observation | PASS | PASS | PASS | PASS | PASS | workaround lane PASS；clean renderer blocked | A_RUNTIME/HARNESS | API37 service readiness、PackageUpdateActivity idle 和 renderer abort 不能由 accepted/pending 状态掩盖 | HARNESS_DEFECT_RESOLVED / ENVIRONMENT | service/package readiness fence、exact screencap fallback、marker 后 FATAL/ANR 合并检查 |

没有使用 package/OEM 特判、sleep 或无限 retry、异常吞噬、Host fallback、降低 assertion
或关闭安全机制换取 PASS。VA/NBB 仅作为架构对照；Android 官方 platform contract
优先，未复制旧 private-field hack。

## 5. Lane A：API37 runtime S01-S10

可信最终 evidence：

out/verification/c6-t01f-api37-final-v3/

该 run 使用用户批准的可见图形模式和临时 TaskSnapshot-off/renderer 仿真器 workaround。
它证明修复后的 CAS 产品路径在 API37 runtime、formal targetSdk35 下可以完成统一
S01-S10；它不解除 clean default renderer 的环境 blocker。

| Test case | Result |
|---|---:|
| S01-host-build-install-launch | PASS |
| S02-guest-import-add | PASS |
| S03-cold-launch-first-frame | PASS |
| S04-warm-launch-reuse | PASS |
| S05-service-lifecycle | PASS |
| S06-broadcast-dispatch | PASS |
| S07-provider-access | PASS |
| S08-pending-intent-path | PASS |
| S09-package-lifecycle | PASS |
| S10-process-death-recovery | PASS |

API37 smoke 汇总：

API37_SMOKE_TOTAL=10
API37_SMOKE_PASS=10
API37_SMOKE_FAIL=0
API37_SMOKE_SKIP=0

Clean default renderer 的 v9/v10/v11 runs 不计入产品结果：它们在 API37
TaskSnapshotPersist 或 SurfaceFlinger region-sampling 路径遇到
readColorBufferDma assertion，最终使 system_server 崩溃或使后续 case 失去服务。
该证据被单独分类为 ENVIRONMENT_BLOCKED，不被折算成产品失败，也不被包装成 clean PASS。

## 6. Supported capability suite

可信最终 capability evidence：

out/verification/c6-t01f-api37-capabilities-final-v7/capability-matrix.json

| Capability | Result | Boundary |
|---|---:|---|
| CAP-PMS-PERMISSION-APPOPS-ATTRIBUTION | PASS | package visibility、permission、AppOps、Attribution |
| CAP-FRAMEWORK-TRANSPORT-IDENTITY | PASS | ordered receiver、async finish、跨进程 identity |
| CAP-SCHEDULING-NOTIFICATION-ALARM-JOB-FGS | PASS | Job、FGS、notification、Alarm |
| CAP-NETWORK-MEDIA-DNS-VPN | PASS | network/media/DNS/VPN virtual path |
| CAP-ENVIRONMENT-SHORTCUT-LAUNCHER | PASS | shortcut/launcher projection |
| CAP-SPLIT-APK-CLASSLOADER | PASS | split APK、ClassLoader、native/JNI |
| CAP-APPWIDGET-DYNAMIC | SKIP | NOT_COVERED_BY_API37_DYNAMIC_SUITE |
| CAP-WEBVIEW-CLASSLOADER-NATIVE | PASS | WebView provider、ICU、ClassLoader/native |

API37_CAPABILITY_TOTAL=8
API37_CAPABILITY_PASS=7
API37_CAPABILITY_FAIL=0
API37_CAPABILITY_SKIP=1

## 7. Memory Limiter P0

Android 17 memory-limiter 的平台说明见 [Android 17 memory-limiter behavior](https://developer.android.com/about/versions/17/behavior-changes-all)
和 [AOSP Memory Limiter configuration](https://source.android.google.cn/docs/core/perf/memory-limiter?hl=en)。
官方退出证据要求将 reason=REASON_OTHER 且 description 含
MemoryLimiter:AnonSwap 的退出分类为 API37_MEMORY_LIMITER，而不是普通
PRODUCT_CRASH；分类本身也不能自动把 recovery 变成 PASS。

本设备实际证据：

MEMORY_LIMITER_STATUS=disabled
MEMORY_LIMITER_DEVICE_RAM=2013496 kB
MEMORY_LIMITER_CONFIG=/system/etc/memory-limiter-config.xml NOT_PRESENT
M01_BASELINE=PASS_OBSERVED_WITHOUT_LIMITER
M02_MULTI_PROCESS=PASS_OBSERVED_WITHOUT_LIMITER
M03_WEBVIEW=PASS_CAPABILITY_PATH
M04_REPEATED_LIFECYCLE=PASS_OBSERVED_WITHOUT_LIMITER
M05_MANUAL_CONSTRAINED=BLOCKED_UNSUPPORTED_DEVICE
MEMORY_LIMITER_CLASSIFICATION_UNIT_TEST=PASS
MEMORY_LIMITER_RECOVERY=NOT_PROVEN_BLOCKED

AOSP 配置说明对 MemTotal 小于 3200 MiB 的设备不施加限制；本设备低于该阈值，
且 status 为 disabled。因此没有伪造 MemoryLimiter:AnonSwap kill/recovery，也没有把
普通 process crash 当成 memory kill。新增 tools/verification/memory_limiter.py
提供 status、manual、ignore、exit-info 和 fail-closed 分类；该镜像的 am help 实际
接口为 manual PID PERCENT|none，wrapper 按设备实际 contract 实现。probe 后已执行
am memory-limiter ignore none，status 恢复 disabled，AVD 随后 wipe-data 并停止。

严格 gate 要求在具备启用 limiter 且 RAM 至少 3.2 GiB 的 API37 image 上重复 M05，
验证 process death、slot/binder、Service、Provider、Activity 清理和 Guest session
restart/recovery 后，才能关闭该 blocker。

## 8. Lane B：target37 controlled probes

### 8.1 MessageQueue

ChangeId 421623328，名称 USE_NEW_MESSAGEQUEUE，enableSinceTargetSdk=37。
正式 Host/fixture targetSdk 仍为 35；仅对实际承载 sandbox 的 debug package/UID
启用 compat override，执行 S03、S04、S05、S06、S07、S08、S09、S10 及已有
multi-process/WebView 路径，然后逐包 disable/reset。

TARGET37_MESSAGEQUEUE_PROBE=PASS
MESSAGE_QUEUE_RUN=out/verification/c6-t01f-api37-messagequeue-compat/
MESSAGE_QUEUE_SMOKE=10/10 PASS
MESSAGE_QUEUE_CAPABILITY=7/0/1
COMPAT_OVERRIDES_RESET=PASS

未新增 API37 private MessageQueue field reflection；现有路径使用 public
Looper/Handler 或集中式兼容边界。未发现只在 USE_NEW_MESSAGEQUEUE 下出现的
deadlock、message loss、barrier、IdleHandler、ordering 或 Binder-to-main-thread
failure。

### 8.2 Target37 probe matrix

| Probe | Method | Result |
|---|---|---|
| New MessageQueue | Compatibility Framework，单 change | PASS |
| static final reflection/JNI | static audit + controlled API37 runtime | PASS |
| ACCESS_LOCAL_NETWORK | 当前 capability/network contract，无专用 target37 dynamic fixture | NOT_SEPARATELY_PROVEN |
| AppWidget memory limit | scope review | NOT_IN_CURRENT_DYNAMIC_SCOPE |
| Bluetooth RFCOMM EOF | 当前无真实 Bluetooth dynamic fixture | TARGET37_DYNAMIC_DEFERRED |
| ECH/network | 不实现 TLS/ECH parser；现有 network/WebView path | NOT_IN_CURRENT_FIXTURE_SCOPE |
| URI grant | 当前 fixture 未形成独立 ACTION_SEND/MULTIPLE/CAPTURE grant matrix | NOT_IN_CURRENT_FIXTURE_SCOPE |

因此：

LOCAL_NETWORK_TARGET37=NOT_SEPARATELY_PROVEN
ECH_API37=NOT_IN_CURRENT_FIXTURE_SCOPE
BLUETOOTH_RFCOMM_TARGET37=TARGET37_DYNAMIC_DEFERRED
APPWIDGET_TARGET37=NOT_IN_CURRENT_DYNAMIC_SCOPE
URI_GRANT_API37=NOT_IN_CURRENT_FIXTURE_SCOPE
TARGET37_MIGRATION_REQUIRED=LOCAL_NETWORK dedicated probe；Bluetooth dynamic fixture；
AppWidget dynamic fixture；URI grant dedicated matrix

当前 network/media/DNS/VPN capability 为 PASS，但不能外推为 ECH 或 ACCESS_LOCAL_NETWORK
的独立 target37 PASS。没有因为 Host 已授权网络就向所有 Guest 自动授予 local network。
没有动态 Bluetooth 设备时不虚构 RFCOMM EOF PASS。

## 9. Static-final、ART/non-SDK 和 Native/JNI

tools/verification/api37_static_audit.py 的最终扫描结果：

STATIC_FINAL_WRITE_SCAN_TOTAL=111
FRAMEWORK_STATIC_FINAL_WRITE_COUNT=2
JNI_STATIC_FINAL_RISK_COUNT=0
REFLECTION_ACCESS_SITES=90
REFLECTION_FIELD_WRITE_SITES=111
UNSAFE_WRITE_SITES=0
VARHANDLE_WRITE_SITES=0
STATIC_FINAL_TARGET37=PASS

两处 framework static-final write 均位于 API<=36 的 guarded BuildIdentityHook 旧兼容
路径；API37+ 不再执行该 projection。没有发现 JNI SetStatic*Field、Unsafe static
write 或 VarHandle static write。禁止以 catch IllegalAccessException 后继续假装 hook
成功；API37 路径是显式关闭非必要 static-final projection。

ART/NON-SDK/API37=PASS_FOR_COLD_WARM_MULTI_PROCESS_SPLIT_WEBVIEW_JNI_RECOVERY
PACKAGE_API37=PASS
ACTIVITY_WINDOW_API37=PASS_WITH_VISIBLE_FRAME_EVIDENCE
SERVICE_API37=PASS
BROADCAST_API37=PASS
PROVIDER_API37=PASS
PENDING_INTENT_API37=PASS
PACKAGE_LIFECYCLE_API37=PASS
WEBVIEW_API37=PASS
NATIVE_BASIC_API37=PASS_FOR_X86_64_GUEST_SPLIT_JNI

ARM64、真实动态 16 KB、cross-bitness 和 OEM/commercial image 不在本任务范围。

## 10. 16 KB static readiness

PAGE_SIZE_16K_STATIC_READINESS=PASS_STATIC_ONLY
ELF_TOTAL=19
ELF_PASS=19
ELF_FAIL=0
PT_LOAD_ALIGNMENT=0x4000
ZIPALIGN_16K=3/3 PASS
ANDROID_PAGE_SIZE_COMPAT_ATTRIBUTE=ABSENT
DYNAMIC_16KB=DEFERRED_TO_C6-T02

当前 Host、fixture-basic、fixture-compat32 APK 的 19 个 ELF 均由 NDK 27.2
llvm-readelf 检查，每个 PT_LOAD alignment 为 0x4000；三份 APK 均通过 build-tools
36.0.0 的 zipalign -c -P 16 4。设备实际 runtime page size 仍为 4096，不能以
x86_64 或静态结果代替 ARM64/动态 16 KB 正式证据。

## 11. Cross-version regression

| Runtime | Evidence | Result |
|---|---|---:|
| API36 | out/verification/c6-t01e-final-api36-post-target35-rebuild/ | 10/10 PASS |
| API35 | out/verification/c6-t01d-api35-final-smoke/ | 10/10 PASS |
| API34 | out/verification/c6-t01c-api34-smoke-final/ | 10/10 PASS |
| API33 | out/verification/c6-t01b-api33-final-smoke/ | 10/10 PASS |
| API32 | out/verification/c6-t01e-final-rd-api32/ | 10/10 PASS |

API32 regression 保持 S04-S09 mandatory coverage；API37 x86_64 没有 32-bit ABI，
Companion32 记录为 UNSUPPORTED_PLATFORM 并留给 C6-T02D，没有安装非标准兼容层。

## 12. Build、harness 和 evidence hygiene

GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
GRADLE_TEST=PASS
PYTHON_UNITTEST=8/8 PASS
PYTHON_COMPILEALL=PASS
BUILD_ENV_LOCK=PASS
STATIC_AUDIT=PASS
16KB_STATIC=19/19 PASS
FALSE_PASS_CHECK=PASS
GIT_DIFF_CHECK=PASS
REF_STATUS=UNCHANGED

Harness 新增并通过普通 crash 与 MemoryLimiter:AnonSwap/REASON_OTHER 的分类测试；
classification 只标识原因，不会自动提升操作结果。runner 增加 API37 lane/device
校验、service/package readiness fence、post-marker fatal 检查以及 exact renderer
abort 的环境分类。所有大日志、截图、dumpsys、APK、AVD output、compat output、
memory evidence 和 rerun 目录均仅保存在 ignored out/verification，不进入 Git。

AVD_STOPPED=PASS
AVD_CONFIG_RESTORED=PASS
TEMP_OVERLAY_REMOVED=PASS
TEMP_COMPAT_OVERRIDE=RESET_AND_VERIFIED
TEMP_MEMORY_LIMITER_OVERRIDE=RESTORED

## 13. Final receipt

C6-T01F
RESULT=BLOCKED
START_HEAD=50648d6a247d4c05c9777cd35d0d45603e4c909b
FINAL_HEAD=HEAD
API37_DEVICE=C6_T01F_API37_GoogleApis_x86_64 / emulator-5574
API37_ANDROID_VERSION=17
API37_API_LEVEL=37
API37_ABI=x86_64
API37_PAGE_SIZE=4096
API37_BUILD_FINGERPRINT=google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys
API37_RAM=2013496 kB
AVD_MODE=VISIBLE_GRAPHICS_USER_APPROVED_EXCEPTION
COMPILE_SDK=36
HOST_TARGET_SDK=35
FIXTURE_TARGET_SDK=35
API37_BASELINE_TOTAL=10
API37_BASELINE_PASS=0
API37_BASELINE_FAIL=10
API37_BASELINE_SKIP=0
DEFECTS_FOUND=baseline discoveries converged; strict environment/scope blockers remain
GENERAL_DEFECTS=0
API37_SPECIFIC_DEFECTS=resolved in targeted/final product runs
HARNESS_DEFECTS=resolved
OBSERVABILITY_DEFECTS=resolved; M05 evidence unavailable on device
FIXTURE_DEFECTS=Companion32 unsupported on x86_64 image, explicitly scoped
EXPECTED_PLATFORM_BEHAVIOR=none proven for MemoryLimiter kill; ordinary API37 lifecycle behavior observed
UNSUPPORTED_PLATFORM=Companion32 on x86_64 API37 image
PRODUCT_FIXES=API37 framework identity, manager context, lifecycle, metadata and typed transport adapters
HARNESS_FIXES=API37 readiness, screenshot/environment classification, post-marker fatal detection, memory-limiter helpers
MEMORY_LIMITER_API37=STATUS_DISABLED_DEVICE_UNSUPPORTED
MEMORY_LIMITER_RECOVERY=NOT_PROVEN_BLOCKED
MESSAGE_QUEUE_TARGET37=PASS
STATIC_FINAL_WRITE_SCAN_TOTAL=111
FRAMEWORK_STATIC_FINAL_WRITE_COUNT=2
JNI_STATIC_FINAL_RISK_COUNT=0
STATIC_FINAL_TARGET37=PASS
LOCAL_NETWORK_TARGET37=NOT_SEPARATELY_PROVEN
ECH_API37=NOT_IN_CURRENT_FIXTURE_SCOPE
BLUETOOTH_RFCOMM_TARGET37=TARGET37_DYNAMIC_DEFERRED
APPWIDGET_TARGET37=NOT_IN_CURRENT_DYNAMIC_SCOPE
URI_GRANT_API37=NOT_IN_CURRENT_FIXTURE_SCOPE
ART_NON_SDK_API37=PASS
PACKAGE_API37=PASS
ACTIVITY_WINDOW_API37=PASS_WITH_WORKAROUND_FRAME_EVIDENCE
SERVICE_API37=PASS
BROADCAST_API37=PASS
PROVIDER_API37=PASS
PENDING_INTENT_API37=PASS
PACKAGE_LIFECYCLE_API37=PASS
WEBVIEW_API37=PASS
NATIVE_BASIC_API37=PASS_FOR_X86_64
PAGE_SIZE_16K_STATIC_READINESS=PASS_STATIC_ONLY
TARGET37_PROBE_TOTAL=7
TARGET37_PROBE_PASS=2
TARGET37_PROBE_FAIL=0
TARGET37_PROBE_SKIP=5
TARGET37_MIGRATION_REQUIRED=local-network dedicated probe; Bluetooth/AppWidget/URI dynamic fixtures
API37_SMOKE_TOTAL=10
API37_SMOKE_PASS=10
API37_SMOKE_FAIL=0
API37_CAPABILITY_TOTAL=8
API37_CAPABILITY_PASS=7
API37_CAPABILITY_FAIL=0
API37_CAPABILITY_SKIP=1
API36_REGRESSION_TOTAL=10
API36_REGRESSION_PASS=10
API36_REGRESSION_FAIL=0
API35_REGRESSION_TOTAL=10
API35_REGRESSION_PASS=10
API35_REGRESSION_FAIL=0
API34_REGRESSION_TOTAL=10
API34_REGRESSION_PASS=10
API34_REGRESSION_FAIL=0
API33_REGRESSION_TOTAL=10
API33_REGRESSION_PASS=10
API33_REGRESSION_FAIL=0
API32_REGRESSION_TOTAL=10
API32_REGRESSION_PASS=10
API32_REGRESSION_FAIL=0
HARNESS_TESTS=8/8 PASS
GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
UNIT_TESTS=GRADLE_TEST PASS
FALSE_PASS_CHECK=PASS
EVIDENCE_GIT_HYGIENE=PASS
COMPAT_OVERRIDES_RESET=PASS
REF_STATUS=UNCHANGED
GIT_STATUS=CLEAN_AFTER_PUSH
NEXT_TASK=BLOCKED

关闭本 blocker 前的下一步是：使用 RAM 至少 3.2 GiB 且 memory limiter enabled 的 API37
设备完成 M05 fail-closed recovery；另行获得 clean default renderer/TaskSnapshot
证据。两项完成前不得进入 C6-T01G。
