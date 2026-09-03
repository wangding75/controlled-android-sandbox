# C6-T01E — Android API36 Platform Convergence

## 1. 结论与可复核字段

```text
C6-T01E
RESULT=PASS
START_HEAD=3e8a8fa3953b09313994d43eff1ef2dd1901e009
FINAL_HEAD=HEAD
BRANCH=feature/t57-r03-va-pro-capability-campaign
COMMIT_MESSAGE=C6-T01E: converge Android API36 platform behavior
REPORT_PATH=reports/t57-r03/c6/C6_T01E_API36_CONVERGENCE_REPORT.md
PROGRESS_PATH=docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md
NO_API37_SCOPE=PASS
```

本任务在 API36 实际运行时完成 Lane A，并以 targetSdk36 的单 flag、可 reset 的方式完成 Lane B。最终正式产物恢复为 `compileSdk=36`、`targetSdk=35`；未把 targetSdk36 探针或临时诊断代码留在正式实现中。唯一动态 skip 是当前 capability suite 未覆盖的 AppWidget 场景；没有未关闭的 API36 product defect。

## 2. 正式基线、设备和构建边界

| 项目 | 实际值 |
|---|---|
| START_HEAD | `3e8a8fa3953b09313994d43eff1ef2dd1901e009` |
| branch | `feature/t57-r03-va-pro-capability-campaign` |
| API36 AVD | `T57_R03_API36_x86_64`，Pixel 6，Google APIs，2G RAM |
| API36 serial | `emulator-5564` |
| API36 build | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` |
| Android/API | Android 16 / API36 |
| ABI | `x86_64`；ABI list `x86_64, arm64-v8a` |
| kernel/page size | x86_64；4096-byte runtime page size |
| API36 launch | `emulator.exe -avd T57_R03_API36_x86_64 -port 5564 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader_indirect` |
| compile/target/min | `compileSdk=36` / `targetSdk=35` / `minSdk=26` |
| build tools/NDK | Build Tools `35.0.0` / NDK `27.2.12479018` |
| fixture target SDKs | formal Host/Guest/fixtures targetSdk `35`；targetSdk36 仅作 Lane B 受控探针 |

API32–35 对照设备也实际执行：API32 MuMu `RD测试`（Android 12 / API32，x86_64，`127.0.0.1:16416`），以及 Google APIs x86_64 AVD `C6_T01B_API33_GoogleApis_x86_64`、`C6_T01C_API34_GoogleApis_x86_64`、`C6_T01D_API35_GoogleApis_x86_64`。API33/34/35 runtime page size 均为 4096；API32 运行时也记录为 4096。API36 Companion32 因设备 ABI list 不含 32-bit ABI，按 `UNSUPPORTED_PLATFORM` 显式排除，未伪造 PASS。

## 3. Baseline-first 与缺陷收敛

### 3.1 API36 baseline

无生产修复的 corrected API36 Lane A baseline：

```text
RUN_ID=c6-t01e-api36-baseline-lane-a
API36_BASELINE_TOTAL=10
API36_BASELINE_PASS=5
API36_BASELINE_FAIL=5
API36_BASELINE_SKIP=0
API36_BASELINE_RESULT=BLOCKED
```

S01、S02、S05、S06、S07 通过；S03、S08、S09、S10 为可归因的产品 launch-gate failure，S04 为当轮环境 timeout。原始证据保存在 ignored local evidence 目录 `out/verification/c6-t01e-api36-baseline-lane-a/`，最终 closure 不依赖弱化断言或重试掩盖。

API36 capability baseline 为 `8 total / 4 PASS / 3 FAIL / 1 SKIP`（`c6-t01e-api36-capability-baseline`）：PMS、scheduling、network、split 通过；Launcher projection、framework transport、WebView ICU 为修复前失败；动态 AppWidget 为 `NOT_COVERED_BY_API36_DYNAMIC_SUITE`。

### 3.2 Defect matrix

| Defect | API32 | API33 | API34 | API35 | API36 | Lane | Layer | Root Cause | Classification | Fix |
|---|---|---|---|---|---|---|---|---|---|---|
| `Runtime.nativeLoad(String, ClassLoader, Class)` registration | PASS | PASS | PASS | PASS | baseline FAIL → PASS | A | native/JNI | API36 对 `java.lang.Runtime` 的 exact hidden class registration contract 未被 exemption 覆盖 | `PRODUCT_DEFECT_API36` | 只增加 exact exemption `Ljava/lang/Runtime;` |
| WebView ICU/provider launch | PASS | PASS | PASS | PASS | baseline FAIL → PASS | A | Guest resources/WebView | provider 共享 APK 路径未进入 Guest-owned `AssetManager`，导致 ICU fd 为 -1 | `PRODUCT_DEFECT_API36` | 校验 provider allowlist，收集 source/public/split/shared-library/all APK paths，并用 shared-library asset path 加入 Guest AssetManager |
| `LauncherActivityInfoInternal` projection | PASS | PASS | PASS | PASS | baseline FAIL → PASS | A | framework PM/launcher | API36 actual constructor 为 `(ActivityInfo, IncrementalStatesInfo, UserHandle, boolean)`，旧投影没有传 live-virtual-package 参数 | `PRODUCT_DEFECT_API36` | API36 exact four-arg adapter，保留 older two/three-arg fallback |
| Service unbind/completion | PASS | PASS | PASS | PASS | baseline FAIL → PASS | A | Guest ActivityThread/service lifecycle | API36 改为 `unbindFinished(IBinder, Intent)`、unbind done code `4`，且 BindServiceData 不再提供旧 bind token；CREATE_SERVICE 可有 null Intent | `PRODUCT_DEFECT_API36` | 按 API gate 适配 `serviceDone`/`unbindFinished` transaction shape 与 filtered Intent key |
| S04 initial Activity reuse observation | PASS | PASS | PASS | PASS | first run 9/10 → targeted/final PASS | A | observation/harness timing | 当轮启动/状态时序导致 reuse marker 未在观察窗口内出现；后续 targeted S03→S04 复现显示真实 reuse/new-intent chain | `ENVIRONMENT` | targeted repro + no-retry final rerun；未放宽 assertion |
| Capability post-marker crash visibility | N/A | N/A | N/A | N/A | harness risk → PASS | A | verification harness | 仅等待前缀 marker 可能漏掉 marker 之后出现的 FATAL/ANR | `HARNESS_OBSERVABILITY` | marker 前清 logcat，等待后追加 post-marker logcat，并合并 required/forbidden evidence；新增 regression unittest |

所有 API36 product defects 均有 targeted evidence 和 final closure；没有 package-name/OEM 特判、sleep/retry 掩盖 race、吞异常、Host fallback 或降低 assertion。

## 4. Lane A：最终结果

### 4.1 S01–S10 smoke

正式 targetSdk35 的最终 API36 smoke：

```text
RUN_ID=c6-t01e-final-api36-post-target35-rebuild
API36_SMOKE_TOTAL=10
API36_SMOKE_PASS=10
API36_SMOKE_FAIL=0
API36_SMOKE_SKIP=0
RESULT=PASS
```

证据目录：`out/verification/c6-t01e-final-api36-post-target35-rebuild/`。此前的 `c6-t01e-api36-s03-s04-repro` 也以 `2/2 PASS` 复核了 Activity reuse/new-intent 链；正式最终 rerun 没有依赖 diagnostic retry。

覆盖范围包括 package visibility、permission/AppOps/AttributionSource、task/window 与 Activity reuse、Service/FGS、broadcast/PendingResult、Provider、PendingIntent、notification、Alarm、Job、network/media/DNS/VPN、shortcut/launcher、WebView、ClassLoader/native/JNI。

### 4.2 capability suite

正式 targetSdk35、清理 Host/Guest/fixture、reset compat override 后的最终 capability：

```text
RUN_ID=c6-t01e-final-api36-capabilities-post-target35-rebuild-clean
CAPABILITY_TOTAL=8
CAPABILITY_PASS=7
CAPABILITY_FAIL=0
CAPABILITY_SKIP=1
RESULT=PASS
```

| Capability | Result | 关键证据/边界 |
|---|---|---|
| PMS / package visibility | PASS | package/visibility/projection contract |
| framework transport identity | PASS | ordered receiver、async finish、cross-process/identity markers |
| scheduling / notification / alarm / Job / FGS | PASS | Job callback、FGS return/stop/campaign markers；无 quota bypass |
| network/media/DNS/VPN | PASS | final clean run |
| launcher projection | PASS | API36 four-argument projection |
| split / ClassLoader | PASS | split load and native/JNI path |
| AppWidget dynamic | SKIP | `NOT_COVERED_BY_API36_DYNAMIC_SUITE` |
| WebView provider/ICU | PASS | provider asset paths added to Guest AssetManager |

### 4.3 必查 API36 contract

- ART / non-SDK：`PASS_EXACT_CLASS_ADAPTER`。修复只覆盖 exact `Runtime` class；framework projection 也按 actual API36 constructor 精确适配，没有 wildcard exemption 或 broad `Throwable` catch。
- Intent redirection / filters：`PASS`。没有发现 `removeLaunchSecurityProtection`、`setAllowUnsafeIntentLaunch`、`UnsafeIntent` 等全局 opt-out；Safer Intents 与 intent-filter matching 的 Lane B 结果均 PASS。
- Ordered broadcast / priority：`PASS`。capability framework case 记录 ordered receiver delivered、async finished/pass 与跨进程 identity markers。
- Job quota：`PASS_CURRENT_TARGET35_SEMANTICS_NO_BYPASS`。Job callback 通过；未启用 `374323858` 或 `341201311` quota override。
- FGS ownership：`PASS`。C2T05 记录 `C2_T05_JOB_CALLBACK_PASS`、FGS return/stop/campaign pass；ownership 仍由 virtual service/lifecycle contract 持有。出现过的 promotion timeout 是可复现的环境/fixture 时序波动，清理后 final capability 与独立 C2T05 均通过。
- Package/activity/service/provider/pending-intent/lifecycle：`PASS`，由 S01–S10 与 capability matrix 共同覆盖。
- WebView/native/JNI：`PASS`，分别由 WebView capability、nativeLoad exact registration 与 split/ClassLoader case 覆盖。

## 5. Lane B：targetSdk36 compatibility probes

每次只 enable 一个 change，完成对应 suite 后 reset，并验证 package override 清空；中间出现的 FGS timing failure 均以 clean retry 作为同一 flag 的 authoritative result，不改变断言。

| Change ID | Contract | Run ID | Result | Reset |
|---:|---|---|---|---|
| `288912692` | `STPE_SKIP_MULTIPLE_MISSED_PERIODIC_TASKS` | `c6-t01e-lane-b-stpe` | PASS (`7/0/1`) | PASS/verified |
| `377864165` | disable opt-out edge-to-edge | `c6-t01e-lane-b-edge-to-edge-retry` | PASS (`7/0/1`) | PASS/verified |
| `161252188` | enforce intents to match filters | `c6-t01e-lane-b-safer-intents` | PASS (`7/0/1`) | PASS/verified |
| `29623414` | prevent intent redirect take action | `c6-t01e-lane-b-intent-redirection` | PASS (`7/0/1`) | PASS/verified |
| `356174596` | remove hidden send-intent method | `c6-t01e-lane-b-hidden-send-intent-retry` | PASS (`7/0/1`) | PASS/verified |
| `349487600` | match non-thread-local networks | `c6-t01e-lane-b-network-final` | PASS (`7/0/1`) | PASS/verified |
| `343977174` | MediaStore version lockdown | `c6-t01e-media-store-toggle` | SKIP — no current dynamic fixture | PASS/verified |

受控 targetSdk36 APK 的全套 S01–S10 也通过 `10/10`（`c6-t01e-target36-predictive-back-probe`），并观察到 API36 CoreBackPreview / `OnBackInvokedCallbackInfo` window evidence。该探针结束后已恢复正式 targetSdk35 并重新 build/install 验证。

```text
TARGET36_PROBE_TOTAL=8
TARGET36_PROBE_PASS=7
TARGET36_PROBE_FAIL=0
TARGET36_PROBE_SKIP=1
PREDICTIVE_BACK_TARGET36=PASS_CONTROLLED_TARGET36_SMOKE
EDGE_TO_EDGE_TARGET36=PASS
FIXED_RATE_TARGET36=PASS_STATIC_AUDIT_AND_STPE_FLAG_SUITE
SAFER_INTENT_TARGET36=PASS
TARGET36_MIGRATION_REQUIRED=fixture-only TaskProbeEvidence direct onBackPressed; migrate to OnBackInvokedDispatcher before permanent targetSdk36
```

`TARGET36_MIGRATION_REQUIRED` 是明确的 fixture 迁移项：当前唯一旧 back API 使用位于 `fixture-basic` 的 `TaskProbeEvidence`，用于 task probe 的直接 helper call；CAS production core（`sandbox-runtime`、`sandbox-framework`、`app`）没有旧 back hook。当前正式 targetSdk35 不受此项阻塞，targetSdk36 永久切换前应把 fixture helper 迁移到 `OnBackInvokedDispatcher`/callback contract。

### 5.1 Fixed-rate / missed-period semantics

静态审计确认没有生产代码使用 `scheduleAtFixedRate` 或 `KEYCODE_BACK`/legacy back hook；现有 framework fixed-rate occurrences 属于受控 location/sensor/identity/service infrastructure。`288912692` 单 flag suite 通过。当前 capability scope 没有生命周期暂停后 missed-period replay 的独立动态 fixture，因此 replay 细节不宣称超出这两项证据。

## 6. 16 KB readiness

```text
PAGE_SIZE_16K_STATIC_READINESS=PASS_STATIC_ONLY
ELF_TOTAL=19
ELF_PASS=19
ELF_FAIL=0
PT_LOAD_ALIGNMENT=0x4000
ZIPALIGN_16K=3/3 PASS
ANDROID_PAGE_SIZE_COMPAT_ATTRIBUTE=ABSENT
PAGE_SIZE_COMPAT_MODE_STATUS=NOT_ENABLED_UNVERIFIED_DYNAMIC_16KB
API36_RUNTIME_PAGE_SIZE=4096
```

Host、fixture-basic、fixture-compat32 的当前 APK 均以 `llvm-readelf` 检查，19 个 ELF 的每个 `PT_LOAD` alignment 均为 `0x4000`；三份 APK 均通过 `zipalign -c -P 16 4`。APK manifest 未启用 `android:pageSizeCompat`。真实 16 KB runtime、ARM64/cross-bitness、OEM/commercial image 留给 C6-T02；本条不把静态 readiness 冒充动态 16 KB PASS。

## 7. Cross-version regression

| Runtime | Device | Smoke result |
|---|---|---:|
| API36 | `T57_R03_API36_x86_64` | `10/10 PASS` |
| API35 | `C6_T01D_API35_GoogleApis_x86_64` | `10/10 PASS` |
| API34 | `C6_T01C_API34_GoogleApis_x86_64` | `10/10 PASS` |
| API33 | `C6_T01B_API33_GoogleApis_x86_64` | `10/10 PASS` |
| API32 | MuMu `RD测试` | `10/10 PASS` |

API35/34/33/32 的最终 evidence 分别位于 `out/verification/c6-t01e-final-api35/`、`out/verification/c6-t01e-final-api34/`、`out/verification/c6-t01e-final-api33/`、`out/verification/c6-t01e-final-rd-api32/`。API32 包含 S04–S09 mandatory regression；各版本均通过现有 package/activity/service/provider/PendingIntent/lifecycle contract。

## 8. Quality gates、false-pass 与 evidence hygiene

```text
GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
GRADLE_TEST=PASS
PYTHON_UNITTEST=7/7 PASS
PYTHON_COMPILEALL=PASS
HARNESS_UNIT=7/7 PASS
GIT_DIFF_CHECK=PASS
REF_UNCHANGED=PASS
FALSE_PASS_POST_MARKER_CRASH_TEST=PASS
```

`run_rd_smoke.py` 和 `run_api33_capabilities.py` 均做真实设备/API/ABI 校验；API36 parser 与 capability runner 为互斥的实际 API36 lane。capability harness 在 marker 后额外读取并合并 logcat，新增 unittest 验证 marker 后的 FATAL 不能被误判为 PASS。无 broad exception swallowing、无 diagnostic-only final pass、无 fake device metadata。

所有 run 目录、截图、dumpsys、raw logcat、APK、build/AVD output 仅留在 ignored `out/verification`；未把大日志复制进报告。`ref/` 未修改；未新增 API37 lane/config；API36 和 MuMu 实例在交付前停止。

## 9. Known boundary / next task

- `TARGET36_MIGRATION_REQUIRED`：永久 targetSdk36 前迁移 fixture-only `TaskProbeEvidence` 的 direct `onBackPressed()` helper。
- `CAP-APPWIDGET-DYNAMIC`：当前 suite skip，不能代表 AppWidget dynamic PASS。
- 动态 16 KB、ARM64/cross-bitness、OEM/commercial image 与更深的 16 KB contract：转入 C6-T02；不在 C6-T01E 扩大范围。
- Android 37：本条明确不启动。

下一任务为 `C6-T02`；本报告完成后停止。
