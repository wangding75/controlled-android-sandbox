# REALAPP-COMPAT-01 — Chrome / 夸克真实商业 App 兼容收敛

## 结论

本任务结果为 BLOCKED。Chrome 的真实导入和包完整性已通过，但冷启动仍暴露通用 shared-library provider 投影缺口；夸克已完成进程创建、bindApplication、ClassLoader、NativeLoader、Provider 初始化、Activity resume 和 CAS 内部首帧，但首帧后的真实基础 smoke 触发通用服务/Provider 路由异常，不能判定为商业 App 兼容通过。

本报告保留 C6-T02C 的 Fixture/ARM64 结论，不把 Fixture/ARM64 PASS 改写为 Commercial App PASS。

| 字段 | 值 |
| --- | --- |
| START_HEAD | bd315b2758bfdf3d4df0d346aefd6d3bb3700c4b |
| FINAL_HEAD | HEAD（本报告所在的唯一最终提交；精确 SHA 见最终回执） |
| Branch | feature/t57-r03-va-pro-capability-campaign |
| Device | Xiaomi 25019PNF3C，serial 192.168.137.210:33259 |
| Android | 16 / API 36，ABI arm64-v8a，PAGE_SIZE 4096 |
| Result | BLOCKED |

原始证据均保存在 ignored 的 out/verification 下，没有纳入 Git。

## Frozen real-app target

### Chrome

| 项目 | 值 |
| --- | --- |
| Package | com.android.chrome |
| Version | 148.0.7778.180，versionCode 777818033 |
| Base APK | /data/app/~~O1kh_9v1xXjLkXmk8Vv4pw==/com.android.chrome-dWsDUqza_MFqjmBR2pMYvQ==/base.apk |
| Splits | split_chrome.apk、split_config.zh.apk、split_on_demand.apk；splitCount=3 |
| Native | 2 个 native library，nativeBytesExtracted=3,110,016 |
| Platform metadata | targetSdk=36，extractNativeLibs=false，primary ABI=arm64-v8a，secondary ABI=armeabi-v7a |
| Static dependency | com.google.android.trichromelibrary，version=777818033，provider=com.google.android.trichromelibrary |

### 夸克

| 项目 | 值 |
| --- | --- |
| Package | com.quark.browser |
| Version | 10.15.5.1130，versionCode 1130 |
| Base APK | /data/app/~~AVYdMq0r-V99HuXJvV_xnQ==/com.quark.browser-S_amhgD5RDsLtRrr_Grf3w==/base.apk |
| Splits | splitCount=0 |
| Native | 64 个 native library，nativeBytesExtracted=195,694,356 |
| Platform metadata | targetSdk=30，extractNativeLibs=true，primary ABI=arm64-v8a |
| Main component | com.ucpro.MainActivity；实际 Activity 链含 com.ucpro.BrowserActivity |
| App factory | androidx.core.app.CoreComponentFactory |

冻结目标证据：../../../out/verification/realapp-compat-01-final-20260907/target-freeze-final.txt。

## Initial failures

1. Chrome clean import 首次失败于 shared-library resolution：缺少 required STATIC:com.google.android.trichromelibrary。根因是 CAS 的虚拟可用库目录只包含固定 framework 列表，没有把宿主 PMS 已接受的 shared-library metadata/file projection 纳入 import resolver。
2. 夸克初始 import 能完成 package operation，但 debug 命令未接受 PREPARED_DEGRADED 状态，导致无法按真实 degraded import 结果继续做受控 launch/smoke。该问题属于测试入口状态语义，不是夸克私有 workaround。
3. 首轮 Chrome import 修复后，导入成功但冷启动暴露新的 SHARED_LIBRARY_PROVIDER_PROJECTION_MISSING:com.google.android.trichromelibrary。它是独立的 runtime defect，不能反向覆盖已经通过的 import gate。

## Implemented generic fixes

### Shared library/package projection

- VirtualPackageStateBuilder 通过宿主 PackageManager 的 getSharedLibraries(0) 读取可见 shared-library identity、kind、version、certificate/provider。
- 对 Android 设备未从 getSharedLibraries 返回的 static library，读取 importing package 的 ApplicationInfo.sharedLibraryFiles，并从宿主 PMS-owned APK path 恢复 provider/version；没有写 packageName == chrome 或任何 Quark 特判。
- SharedLibraryResolver 支持同名多版本、按 manifest version/certificate 精确选择，并保留 mismatch 诊断；合并宿主 concrete provider 时允许其替换 synthetic android fallback，真实 provider 优先。
- SandboxPackageLifecycle 使用 Context 参与 import-time shared-library validation，使 clean import 与 runtime resolver 使用同一类宿主事实。

### Truthful runtime/harness plumbing

- import-prepare 和 hold-prepare 接受 PREPARED_DEGRADED / ALREADY_PREPARED_DEGRADED，并继续记录该状态，而不是把 degraded 强行改成普通 PREPARED。
- 静态 Android stub 和 test harness 只补齐所需 API/checked-exception 编译面；没有改变生产隔离策略。

## Chrome staged import and startup

### Import stage ledger

| Stage | Result | Evidence/说明 |
| --- | --- | --- |
| SOURCE_DISCOVERY | PASS | base + 3 split artifacts discovered |
| HASH/COPY | PASS | apkBytesRead/written=28,701,797 |
| MANIFEST_PARSE / PACKAGE_INFO | PASS | package metadata parsed |
| SPLITS | PASS | splitCount=3，三个 split 均进入 import trace |
| ABI / NATIVE_DETECT | PASS | arm64-v8a native set detected |
| NATIVE_EXTRACT | PASS | 2 libs，3,110,016 bytes |
| SIGNATURE_METADATA | PASS | trusted manifest/signature metadata accepted |
| SHARED_LIBRARY | PASS | host file projection logged Trichrome STATIC version 777818033 |
| PUBLISH / CATALOG / POST_INSTALL_METADATA | PASS | operation stage DONE, status SUCCEEDED |

Final package operation elapsed 314 ms，anomalies=[]。因此：

- CHROME_IMPORT_INITIAL=FAIL
- CHROME_LAST_SUCCESSFUL_IMPORT_STAGE=POST_INSTALL_METADATA
- CHROME_FIRST_FAILED_IMPORT_STAGE=NONE（import 内无失败；首次失败发生在独立 launch）
- CHROME_IMPORT_FINAL=PASS
- CHROME_PACKAGE_COMPLETE=PASS

Chrome import 结果：../../../out/verification/realapp-compat-01-final-20260907/chrome-import-result.json。shared-library 证据：../../../out/verification/realapp-compat-01-final-20260907/chrome-import-logcat.txt。

### Chrome launch

冷启动已经尝试，但在进入 process/bindApplication/first-frame 前失败：

SHARED_LIBRARY_PROVIDER_PROJECTION_MISSING:com.google.android.trichromelibrary

分类为 GENERIC_SHARED_LIBRARY_DEFECT。当前 import 侧已经投影了 Trichrome 的 host file、kind、version 和 provider，但 RuntimeClient/packageUniverse 或 GuestSharedLibraryPathResolver 仍没有把该 provider identity/path contract 投影到启动侧。此处不能写成 Chrome-specific patch，也不能写成 split 或 native loader defect。

- CHROME_ROOT_CAUSE=GENERIC_SHARED_LIBRARY_DEFECT: runtime shared-library provider projection incomplete
- CHROME_FIX_CLASSIFICATION=GENERIC_SHARED_LIBRARY_DEFECT（import 已修复；runtime provider projection 仍待 REALAPP-COMPAT-02）
- CHROME_FIRST_FRAME_DRAWN=NOT_TESTED（启动在首帧前失败）

启动证据：../../../out/verification/realapp-compat-01-final-20260907/chrome-launch-result.json 和 chrome-launch-logcat.txt。

## 夸克 staged launch and smoke

### Import/package completeness

夸克最终 import trace 为 stage=DONE、status=SUCCEEDED、splitCount=0、nativeLibCount=64、nativeBytesExtracted=195,694,356、apkBytesRead/written=180,885,305、anomalies=[]。因此：

- QUARK_IMPORT=PASS
- QUARK_PACKAGE_COMPLETE=PASS

证据：../../../out/verification/realapp-compat-01-final-20260907/quark-import-prepare-result.json 和 quark-import-prepare-logcat.txt。

### Launch stage ledger

canonical CAS launch result 记录到 FIRST_FRAME_DRAWN：

| Stage | Result |
| --- | --- |
| launch request / resolveActivity / Stub selection | PASS |
| process slot allocation / Guest process create | PASS；processSlot=60，processName=com.quark.browser |
| bindApplication / LoadedApk / AppComponentFactory | PASS |
| ClassLoader | PASS；guest APK/native path 已进入 PathClassLoader |
| NativeLoader | PASS；libcycloneuc.so 等 arm64 library 有 linker load ok 证据 |
| Provider prepare/install | PASS；GUEST_PROVIDER_PREPARE=PROVIDER_READY |
| Application attach/onCreate | PASS |
| Activity attach/onCreate/onStart/onResume | PASS；实际链含 com.ucpro.BrowserActivity |
| Window attach / CAS first frame | PASS；activityCreated=true、activityResumed=true、windowEvidence=true、firstFrameDrawn=true |

因此：

- QUARK_PROCESS_CREATE=PASS
- QUARK_BIND_APPLICATION=PASS
- QUARK_CLASSLOADER=PASS
- QUARK_NATIVE_LOADER=PASS
- QUARK_PROVIDER_INIT=PASS
- QUARK_ACTIVITY_RESUME=PASS
- QUARK_FIRST_FRAME_DRAWN=PASS
- QUARK_LAST_SUCCESSFUL_LAUNCH_STAGE=FIRST_FRAME_DRAWN
- QUARK_FIRST_FAILED_LAUNCH_STAGE=POST_FIRST_FRAME_BACKGROUND_SERVICE_BIND / BASIC_SMOKE

证据：../../../out/verification/realapp-compat-01-final-20260907/quark-launch-result.json、quark-launch-logcat.txt、quark-top-activity-final.txt、quark-window-final.txt。

说明：canonical CAS launch timeline 的 firstFrameDrawn=true 是 Guest WindowManager/生命周期证据；同期物理屏幕截图的 top activity 已回到 MIUI Launcher。因此不能把这个内部首帧直接升级成真实交互 smoke PASS，二者在报告中分开计数。

### Basic smoke

按要求执行了 hold-prepare 60 秒观察，并采集 03s、10s、late 的 screenshot、top/window/process 和 logcat。夸克进程曾出现，WindowManager 也曾出现 Quark BrowserActivity window，但随后：

- FATAL EXCEPTION: Thread-27，Process: com.quark.browser
- java.lang.IllegalArgumentException: NO_GUEST_SERVICE_MATCH
- 栈落在 GuestIntentResolver.resolveOne -> GuestContextComponentRouter.bindService，调用方是夸克 DeviceInfoCapturerFull。
- 同时反复出现 CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED:com.android.bluetooth.ble.app.mihome.provider 和 com.miui.analytics.OneTrackProvider。
- top activity 在采样点仍为 com.miui.home/.launcher.Launcher，没有形成可安全执行的“打开基础页面/返回主页”路径。

因此 QUARK_BASIC_SMOKE=FAIL。这里的 first failed stage 是首帧后的通用 component/service/provider routing，不是 package import、ABI 或 native loader。

分类：

- GENERIC_PMS_DEFECT：Guest service intent/metadata resolution 对真实服务请求没有形成可用 Guest match。
- GENERIC_PROVIDER_DEFECT：外部/system provider authority 没有被正确 virtualize/project。
- APP_SPECIFIC_DEFECT=NONE：没有加入 Quark package/version 特判。

原始 smoke 证据：../../../out/verification/realapp-compat-01-final-20260907/quark-smoke-logcat.txt、quark-smoke-03s.png、quark-smoke-10s.png、quark-smoke-late.png、quark-smoke-late-top.txt。

## Generic vs app-specific classification

| 分类 | 当前结论 |
| --- | --- |
| GENERIC_PACKAGE_DEFECT | NONE after import fix |
| GENERIC_SPLIT_DEFECT | NONE；Chrome 3 splits 完整导入，夸克为 splitCount=0 |
| GENERIC_SHARED_LIBRARY_DEFECT | Chrome import 已闭合；runtime provider projection remains |
| GENERIC_ABI_DEFECT | NONE observed |
| GENERIC_NATIVE_LOADER_DEFECT | NONE；夸克 arm64 native loader reached first frame |
| GENERIC_MULTIPROCESS_DEFECT | NONE proven in this lane |
| GENERIC_ISOLATED_PROCESS_DEFECT | NONE proven；夸克 isolatedProcess=false |
| GENERIC_PROVIDER_DEFECT | Quark external/provider authority virtualization remains |
| GENERIC_PMS_DEFECT | Quark service/component resolution remains |
| API36_SPECIFIC_DEFECT | NONE isolated by current evidence |
| ARM64_SPECIFIC_DEFECT | NONE isolated by current evidence |
| OEM_SPECIFIC_DEFECT | NONE；设备 OEM 日志是观测环境，不是修复分类 |
| APP_SPECIFIC_DEFECT | NONE |
| HARNESS_DEFECT | PREPARED_DEGRADED command acceptance fixed; no remaining harness-only substitution for the two real-app failures |

## VA / NBB implementation comparison

### VirtualApp

Relevant reference paths:

- ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/server/pm/parser/PackageParserEx.java
- ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/server/pm/VPackageManagerService.java
- ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/server/pm/VAppManagerService.java

VA handles this class of problem in the package/parser and virtual PMS layers: PackageParserEx carries nativeLibraryDir and host sharedLibraryFiles into ApplicationInfo; VPackageManagerService exposes virtual package metadata; VAppManagerService owns install/parse/publish lifecycle. Its model confirms that host-resolved shared-library files are package metadata, not an app-specific launch hook. CAS previously lacked the equivalent host shared-library file/catalog projection at import and a complete runtime provider projection.

### NewBlackbox

Relevant reference paths:

- ref/upstream/NewBlackbox/Bcore/src/main/java/android/content/pm/PackageParser.java
- ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/BPackageManagerService.java
- ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java
- ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/core/IOCore.java

NBB carries splitNames/splitCodePaths/extractNativeLibs in its package model, exposes package info through BPackageManagerService, and handles bindApplication, classloader/application creation, provider installation and activity runtime in BActivityThread. IOCore maps package/runtime paths such as nativeLibraryDir. This separates the missing layers: Chrome needs package/shared-library metadata plus runtime provider projection; Quark smoke needs generic PMS/component routing and provider virtualization after first frame. Neither reference supports an app-name conditional patch.

## Regression and validation

Required build/test commands:

- ./gradlew projects --no-daemon: PASS
- ./gradlew assembleDebug --no-daemon: PASS
- ./gradlew test --no-daemon: PASS
- python tools/static_android_compile.py: PASS
- run_rd_smoke.py S01-S10: PASS, 10/10

Regression ledger:

- S01_S10_REGRESSION=10/10 PASS
- SPLIT_FIXTURE=PASS（S02/S09 and the complete S01-S10 run remain PASS）
- ARM64_NATIVE_REGRESSION=PASS（C6-T02C baseline plus current S01-S10 and Quark real native-loader evidence）
- ABI_ELF_VALIDATOR=PASS（C6-T02A static audit retained）
- HARNESS_TESTS=PASS
- UNIT_TESTS=PASS
- FALSE_PASS_CHECK=PASS：import requires package operation trace and package completeness; Quark first frame and post-frame smoke are reported separately, and the observed crash is not promoted to PASS
- REF_STATUS=UNCHANGED

The re-run capability extension is 12 total / 8 PASS / 2 FAIL / 1 EXPECTED_LIMITATION / 1 NOT_IN_CURRENT_SCOPE. The two failing records are CAP-SCHEDULING-NOTIFICATION-ALARM-JOB-FGS and CAP-NATIVE-ADVERSARIAL-BOUNDARY; they are retained as separate existing capability/environment-boundary evidence and are not used to claim Real App compatibility. Evidence: ../../../out/verification/realapp-compat-01-capability-final-20260907/capability-matrix.json.

## Real App Gate

| Gate | Result |
| --- | --- |
| CHROME_IMPORT | PASS |
| CHROME_PACKAGE_COMPLETE | PASS |
| QUARK_IMPORT | PASS |
| QUARK_PACKAGE_COMPLETE | PASS |
| QUARK_PROCESS_CREATE | PASS |
| QUARK_BIND_APPLICATION | PASS |
| QUARK_CLASSLOADER | PASS |
| QUARK_NATIVE_LOADER | PASS |
| QUARK_PROVIDER_INIT | PASS |
| QUARK_ACTIVITY_RESUME | PASS |
| QUARK_FIRST_FRAME_DRAWN | PASS |
| QUARK_BASIC_SMOKE | FAIL |
| REAL_APP_COMPAT_01 | BLOCKED |

Remaining defects:

1. Chrome runtime must project the host static Trichrome provider identity/path into the guest runtime shared-library contract.
2. Quark basic smoke needs generic Guest PMS service resolution and external Provider authority virtualization that survive after first frame.
3. Capability extension's two non-commercial-app failures remain separately tracked; do not silently convert them to PASS.

No C6-T02D is entered. The correct next task is REALAPP-COMPAT-02.

## Final receipt

REALAPP-COMPAT-01

RESULT=BLOCKED
START_HEAD=bd315b2758bfdf3d4df0d346aefd6d3bb3700c4b
FINAL_HEAD=HEAD (exact final commit SHA in handoff)

DEVICE=Xiaomi 25019PNF3C / 192.168.137.210:33259
ANDROID=16
API=36
ABI=arm64-v8a

CHROME_PACKAGE=com.android.chrome
CHROME_VERSION=148.0.7778.180 (777818033)
CHROME_BASE_APK=/data/app/~~O1kh_9v1xXjLkXmk8Vv4pw==/com.android.chrome-dWsDUqza_MFqjmBR2pMYvQ==/base.apk
CHROME_SPLIT_COUNT=3
CHROME_NATIVE_SPLIT_COUNT=2
CHROME_SYSTEM_APP_STATUS=NORMAL_DATA_APP_WITH_STATIC_TRICHROME_PROVIDER
CHROME_IMPORT_INITIAL=FAIL
CHROME_LAST_SUCCESSFUL_IMPORT_STAGE=POST_INSTALL_METADATA
CHROME_FIRST_FAILED_IMPORT_STAGE=NONE_IMPORT; launch failed separately
CHROME_ROOT_CAUSE=GENERIC_SHARED_LIBRARY_DEFECT: runtime provider projection missing for com.google.android.trichromelibrary
CHROME_FIX_CLASSIFICATION=GENERIC_SHARED_LIBRARY_DEFECT
CHROME_IMPORT_FINAL=PASS
CHROME_PACKAGE_COMPLETE=PASS
CHROME_FIRST_FRAME_DRAWN=NOT_TESTED

QUARK_PACKAGE=com.quark.browser
QUARK_VERSION=10.15.5.1130 (1130)
QUARK_SPLIT_COUNT=0
QUARK_NATIVE_LIBRARY_COUNT=64
QUARK_IMPORT=PASS
QUARK_PACKAGE_COMPLETE=PASS
QUARK_LAST_SUCCESSFUL_LAUNCH_STAGE=FIRST_FRAME_DRAWN
QUARK_FIRST_FAILED_LAUNCH_STAGE=POST_FIRST_FRAME_BACKGROUND_SERVICE_BIND / BASIC_SMOKE
QUARK_ROOT_CAUSE=GENERIC_PMS_DEFECT + GENERIC_PROVIDER_DEFECT: NO_GUEST_SERVICE_MATCH and unvirtualized external provider authorities
QUARK_FIX_CLASSIFICATION=GENERIC_PMS_DEFECT + GENERIC_PROVIDER_DEFECT
QUARK_PROCESS_CREATE=PASS
QUARK_BIND_APPLICATION=PASS
QUARK_CLASSLOADER=PASS
QUARK_NATIVE_LOADER=PASS
QUARK_PROVIDER_INIT=PASS
QUARK_ACTIVITY_RESUME=PASS
QUARK_FIRST_FRAME_DRAWN=PASS
QUARK_BASIC_SMOKE=FAIL

GENERIC_PACKAGE_DEFECTS=NONE after fix
GENERIC_SPLIT_DEFECTS=NONE
GENERIC_SHARED_LIBRARY_DEFECTS=Chrome runtime provider projection remains
GENERIC_NATIVE_LOADER_DEFECTS=NONE observed
GENERIC_MULTIPROCESS_DEFECTS=NONE proven
GENERIC_ISOLATED_PROCESS_DEFECTS=NONE proven; Quark isolatedProcess=false
GENERIC_PROVIDER_DEFECTS=Quark external/provider authority virtualization
GENERIC_PMS_DEFECTS=Quark service/component resolution
API36_SPECIFIC_DEFECTS=NONE isolated
ARM64_SPECIFIC_DEFECTS=NONE isolated
OEM_SPECIFIC_DEFECTS=NONE
APP_SPECIFIC_DEFECTS=NONE

VA_COMPARISON=PackageParserEx/VPackageManagerService/VAppManagerService handle package metadata, nativeLibraryDir and host sharedLibraryFiles; CAS needed equivalent projection
NBB_COMPARISON=PackageParser/BPackageManagerService/BActivityThread/IOCore cover split metadata, bindApplication, classloader, providers, activity runtime and native paths; CAS gaps are generic metadata/runtime routing

REAL_APP_GATE=BLOCKED
S01_S10_REGRESSION=10/10 PASS
SPLIT_FIXTURE=PASS
ARM64_NATIVE_REGRESSION=PASS
ABI_ELF_VALIDATOR=PASS
HARNESS_TESTS=PASS
UNIT_TESTS=PASS
FALSE_PASS_CHECK=PASS

REF_STATUS=UNCHANGED
REPORT=reports/t57-r03/c6/REALAPP_COMPAT_01_CHROME_QUARK_REPORT.md
GIT_STATUS=expected CLEAN after final commit
NEXT_TASK=REALAPP-COMPAT-02
