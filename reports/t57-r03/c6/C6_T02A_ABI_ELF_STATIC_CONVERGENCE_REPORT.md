# C6-T02A — ABI / ELF / Native Packaging Static Convergence

日期：2026-09-07（Asia/Shanghai）
分支：`feature/t57-r03-va-pro-capability-campaign`
范围：当前 HEAD 的正式 Debug APK/AAR、最终包内 native library、Gradle/CMake 配置和中间 native 输出。

## 结论

`C6-T02A=PASS`。当前声明的四个 ABI 都在 x86_64 开发机上真实交叉编译并产出对应 `.so`；13 个正式 APK/AAR 中扫描到 29 个 native package records，全部 ELF class/machine 匹配，全部 LOAD segment `p_align >= 0x4000`，没有缺失依赖、开发机 RPATH/RUNPATH 或 JNI export contract 差异。

可安装 APK 的 25 个 native 条目全部 STORED，并通过 `zipalign -c -v -P 16 4`；`sandbox-native` AAR 的 4 个 `jni/` 条目保留为 AAR 消费形态的 DEFLATED。AAR 本身不可安装，消费 APK 会按 app 的 JNI packaging 规则重新打包；对应的 `app`、fixture 和 `sandbox-companion32` APK 已全部通过 STORED + 16KB ZIP data-offset 检查。

本任务未执行 16KB 动态、ARM64 真机、Companion32 动态、ARM32 scope 决策或 API37 专项。API37 已按环境延期从 C6-T02 主线解除阻塞。

## 1. 基线与配置事实源

```text
START_HEAD=345924455d30432dbd88c17985c9d499d6630f2c
FINAL_HEAD=HEAD (resolved by the final single-commit Git gate)
BRANCH=feature/t57-r03-va-pro-capability-campaign
```

读取了 `settings.gradle`、root/module `build.gradle`、`gradle.properties`、所有 CMakeLists，以及 Android.mk/Application.mk/version catalog（本项目均不存在后两类文件）。root 工具链为 AGP `8.11.1`、compileSdk `36`、targetSdk `35`、minSdk `26`、Build Tools `35.0.0`、NDK `27.2.12479018`、CMake `3.22.1`。

| Module | `abiFilters` | Native build / output | 16KB link option |
|---|---|---|---|
| `app` | `arm64-v8a`, `x86_64` | debug CMake → APK | `cas_native_enf` |
| `sandbox-native` | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` | main CMake → AAR | `controlled_sandbox_native` |
| `sandbox-companion32` | `armeabi-v7a`, `x86` | main CMake → 32-bit APK | `controlled_sandbox_native32` |
| `fixture-basic` | `arm64-v8a`, `x86_64` | main CMake → APK | 3 targets |
| `fixture-compat32` | `armeabi-v7a`, `x86`, `x86_64` | fixture-basic CMake → test APK | 3 targets |
| `fixture-activity-scale` | `arm64-v8a`, `x86_64` | main CMake → APK | `controlled_sandbox_fixture_scale` |

没有 `splits.abi`、source `src/*/jniLibs`、`pickFirst`、`exclude` 或 `doNotStrip` 规则。`app`、`fixture-compat32` 和 `sandbox-companion32` 的最终 APK 显式使用 `packagingOptions.jniLibs.useLegacyPackaging=false`；此前会造成压缩 native 的 source `extractNativeLibs` 属性已移除。其余 native APK 的默认 AGP packaging 也实际产出 STORED 条目。

完整配置和工具原始证据保存在本地忽略目录：`out/verification/t57-r03/c6-t02a-abi-elf-static-convergence/`。

## 2. ABI Build Matrix

状态只反映当前项目配置和实际 artifact，不预设产品支持范围。无 native 声明的 lifecycle/split/Java-only module 不在此表重复列出。

| Module | `armeabi-v7a` | `arm64-v8a` | `x86` | `x86_64` |
|---|---|---|---|---|
| `app` | NOT_DECLARED | BUILT | NOT_DECLARED | BUILT |
| `sandbox-native` | BUILT | BUILT | BUILT | BUILT |
| `sandbox-companion32` | COMPANION_ONLY | NOT_DECLARED | COMPANION_ONLY | NOT_DECLARED |
| `fixture-basic` | NOT_DECLARED | TEST_ONLY | NOT_DECLARED | TEST_ONLY |
| `fixture-compat32` | TEST_ONLY | NOT_DECLARED | TEST_ONLY | TEST_ONLY |
| `fixture-activity-scale` | NOT_DECLARED | TEST_ONLY | NOT_DECLARED | TEST_ONLY |

ABI matrix 共 15 个 module/ABI cells：`BUILT=6`、`TEST_ONLY=7`、`COMPANION_ONLY=2`、`DECLARED_NOT_BUILT=0`。四个 ABI 均有真实最终记录，且 x86_64 开发机上生成了 ARM64 和 ARM32 ELF；没有以“缺少 ARM 设备”为构建豁免。

## 3. APK/AAR Inventory

下表是 13 个最终 Debug APK/AAR 的 ZIP inventory；native 内容按 ABI 汇总。SHA-256 是实际输出文件 hash。

| Artifact | Module / kind | Size | SHA-256 | Native contents |
|---|---|---:|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | app / APK | 7143576 | `4c49556bebeaf25a8771b2202a8ee4fd2ee8076c3061c84e159a6f377b5bb5f2` | arm64: cas + native; x86_64: cas + native |
| `fixture-activity-scale/build/outputs/apk/debug/fixture-activity-scale-debug.apk` | fixture-activity-scale / APK | 44958 | `27b95960cc4b3bf1ab1ae93dc0500b7c2a61fd24c003b0b3ce1f16daf1153ee6` | arm64/x86_64: fixture_scale |
| `fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk` | fixture-basic / APK | 2013670 | `8a53fef1c4c4185a554f6b17716a469dc30a10f6be167ca2ede755eb6a6c6ef4` | arm64/x86_64: cas + fixture + adv_payload |
| `fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk` | fixture-compat32 / APK | 2584535 | `78a57f5aa15133b3b46465e8a30ae6947035f8049f0912b86b98e567494557b9` | arm32/x86/x86_64: cas + fixture + adv_payload |
| `fixture-lifecycle/build/outputs/apk/v1/debug/fixture-lifecycle-v1-debug.apk` | fixture-lifecycle / APK | 6789 | `3b1c7aad9d0c62134292d09ed96dc82932c35a62e69e605867783cf36811ac63` | no native |
| `fixture-lifecycle/build/outputs/apk/v2/debug/fixture-lifecycle-v2-debug.apk` | fixture-lifecycle / APK | 6905 | `cd5a5d868eb41b661da6a03e7993d6ce2efd12f7b19d4a69be6676bd85e51a31` | no native |
| `fixture-split-base/build/outputs/apk/debug/fixture-split-base-debug.apk` | fixture-split-base / APK | 6973 | `0894ff252e16213a92132d02630d721d5190d8b7cf9f0d3b2868ea2bf5ab9c5a` | no native |
| `fixture-split-feature/build/outputs/apk/debug/fixtureSplitFeature-debug.apk` | fixtureSplitFeature / APK | 7550 | `67947cd7e514cdbd5228f90295c0a403d25d814bed8912993fe847d055e3a598` | no native |
| `sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk` | sandbox-companion32 / APK | 8454382 | `d10a5409b9252c346e727f3d968d9c9a35ff385067ca5de167c94e6e6abb7a41` | arm32/x86: native + native32 |
| `sandbox-contract/build/outputs/aar/sandbox-contract-debug.aar` | sandbox-contract / AAR | 580350 | `7d1dd1ec20de6aed30275ffdf49a79925840ef28b9a9e931843e3ccb4105aaa0` | no native |
| `sandbox-framework/build/outputs/aar/sandbox-framework-debug.aar` | sandbox-framework / AAR | 888743 | `c8a961b8cb0fd8520e933a71ca0b8cb551f0ee2eaa5337595f8acb2a38b2d15f` | no native |
| `sandbox-native/build/outputs/aar/sandbox-native-debug.aar` | sandbox-native / AAR | 2428514 | `aa54866a1d1c3cd0489ae7fa4efaa5ddb57dd4e9b90802b23340d1d8fdb3bf32` | one native per arm32/arm64/x86/x86_64 |
| `sandbox-runtime/build/outputs/aar/sandbox-runtime-debug.aar` | sandbox-runtime / AAR | 1468185 | `68414ba169ee5562a44410a60b2e6c5e7b26ec2c74f880932e0b69c866dcd46a` | no native |

## 4. Native Library Inventory

29 个最终 package records，全部 first-party；没有 source `jniLibs`、外部 native dependency、extracted third-party AAR 或最终包内第三方 `.so`。完整记录也保存在 `native_inventory.json`，下表保留 artifact/module/ABI/path/size/SHA-256。

| Artifact | Module | ABI | `.so` path | Size | SHA-256 |
|---|---|---|---|---:|---|
| `app-debug.apk` | app | arm64-v8a | `lib/arm64-v8a/libcas_native_enf.so` | 412808 | `81c2657bae9fad79205994f4e880170e28a06333a8362bb6a4c4d7061e8bdcde` |
| `app-debug.apk` | app | arm64-v8a | `lib/arm64-v8a/libcontrolled_sandbox_native.so` | 2059688 | `d0e461c042e23b4955e2391665584f8ebb0db2cd7e441647ad5cf247e775dfd1` |
| `app-debug.apk` | app | x86_64 | `lib/x86_64/libcas_native_enf.so` | 395616 | `4ebf31ccfebe056da4bd7d652beae855690e1d59683076e65862525c31a3f928` |
| `app-debug.apk` | app | x86_64 | `lib/x86_64/libcontrolled_sandbox_native.so` | 2049840 | `118dd14f4a1fb54bf65859460bfaefac1cd44b449aceb6ab0e1d72ef71494fdc` |
| `fixture-activity-scale-debug.apk` | fixture-activity-scale | arm64-v8a | `lib/arm64-v8a/libcontrolled_sandbox_fixture_scale.so` | 4112 | `88232cecbc22e6b21213f8088bc69a838545c3389d20b4db22cb1185dc6d19ca` |
| `fixture-activity-scale-debug.apk` | fixture-activity-scale | x86_64 | `lib/x86_64/libcontrolled_sandbox_fixture_scale.so` | 4128 | `b55b4f83275634f6e01bb579794a461f7abec70be269d451ae6581046abf2833` |
| `fixture-basic-debug.apk` | fixture-basic | arm64-v8a | `lib/arm64-v8a/libcas_native_enf.so` | 412808 | `8e16c9fa4aa2b13444db21633369cfa591ea8354657eaad653a906e551fca773` |
| `fixture-basic-debug.apk` | fixture-basic | arm64-v8a | `lib/arm64-v8a/libcontrolled_sandbox_fixture.so` | 497368 | `0263c63858761a55fa8e24623a77e146936ebc0e11040950c77e4b88f867cc07` |
| `fixture-basic-debug.apk` | fixture-basic | arm64-v8a | `lib/arm64-v8a/libfixture_adv_payload.so` | 8000 | `1d76ad5114f3b65b32f59694af84b30854424ba0bf2d7b6bcfed328478af3910` |
| `fixture-basic-debug.apk` | fixture-basic | x86_64 | `lib/x86_64/libcas_native_enf.so` | 395616 | `3266f6553c6395f2dccc47631145ab533c51e3d2af32ed9d6981368dd308def0` |
| `fixture-basic-debug.apk` | fixture-basic | x86_64 | `lib/x86_64/libcontrolled_sandbox_fixture.so` | 491752 | `dd7cad472b5fa1cd88882e795ccb3edc8ac102aec80eda21ff798b71f7c8dabf` |
| `fixture-basic-debug.apk` | fixture-basic | x86_64 | `lib/x86_64/libfixture_adv_payload.so` | 5088 | `b93d068c09f09dc39aef24e44e4ede0966b61711f9c0e5e4b46d17cbcca76653` |
| `fixture-compat32-debug.apk` | fixture-compat32 | armeabi-v7a | `lib/armeabi-v7a/libcas_native_enf.so` | 247032 | `84d5adb045e41bc84d2da555dd4c63d37c4be9371d75c2c3c63f765a3d8b9df7` |
| `fixture-compat32-debug.apk` | fixture-compat32 | armeabi-v7a | `lib/armeabi-v7a/libcontrolled_sandbox_fixture.so` | 300092 | `e5dfa237a216b3931d1473e2e6a3913d8b17032517833b9cd4916ed13e22a73e` |
| `fixture-compat32-debug.apk` | fixture-compat32 | armeabi-v7a | `lib/armeabi-v7a/libfixture_adv_payload.so` | 3624 | `e19632e243c9b881ff19187213849d39fed27f6dc7b91f4d7fc01869ce572f66` |
| `fixture-compat32-debug.apk` | fixture-compat32 | x86 | `lib/x86/libcas_native_enf.so` | 394084 | `c49875729cfccb924b19bf6d8652f7a3d99977c48c50dbc20f48717c28c87ded` |
| `fixture-compat32-debug.apk` | fixture-compat32 | x86 | `lib/x86/libcontrolled_sandbox_fixture.so` | 505752 | `1d706d6199401a81d5a2722b542024faea0427cc78c25cfab1a4009979d435ee` |
| `fixture-compat32-debug.apk` | fixture-compat32 | x86 | `lib/x86/libfixture_adv_payload.so` | 4200 | `5552bf9c0d523dbb14807e2ff9947686b7022b0d2cbe59c0c2c0d0192fd9fe98` |
| `fixture-compat32-debug.apk` | fixture-compat32 | x86_64 | `lib/x86_64/libcas_native_enf.so` | 395616 | `90e6d479a34358d8dc2a0398fbfdcf5b813376ecedc32f5a5fa14132adb20a29` |
| `fixture-compat32-debug.apk` | fixture-compat32 | x86_64 | `lib/x86_64/libcontrolled_sandbox_fixture.so` | 491752 | `7f776e332cdd397d89743a3fb7373244c9adf908a620fdce643a963ddd010205` |
| `fixture-compat32-debug.apk` | fixture-compat32 | x86_64 | `lib/x86_64/libfixture_adv_payload.so` | 5088 | `42aeb9c1e9dc66e518642bfec98190e47bf8a6d267a7bba7f9c4efd41853b059` |
| `sandbox-companion32-debug.apk` | sandbox-companion32 | armeabi-v7a | `lib/armeabi-v7a/libcontrolled_sandbox_native.so` | 1239304 | `3456432c6f9e399746b59044bd72dce7eb7c4bcca69947b883b274abbe5d5e09` |
| `sandbox-companion32-debug.apk` | sandbox-companion32 | armeabi-v7a | `lib/armeabi-v7a/libcontrolled_sandbox_native32.so` | 1239976 | `5863a2df8584db70194f760919a5a1d21ab7534012d91dd75613957821d24da3` |
| `sandbox-companion32-debug.apk` | sandbox-companion32 | x86 | `lib/x86/libcontrolled_sandbox_native.so` | 2112144 | `96e83379000abc3dd4a81175fa788401de93b277eb6fe29678c7cb7e8ca239c4` |
| `sandbox-companion32-debug.apk` | sandbox-companion32 | x86 | `lib/x86/libcontrolled_sandbox_native32.so` | 2113264 | `8b68f2006c0ea3fa40380bf44fd8d4e08c5f2167daa4ed4acca36049a355c40b` |
| `sandbox-native-debug.aar` | sandbox-native | arm64-v8a | `jni/arm64-v8a/libcontrolled_sandbox_native.so` | 2059688 | `d0e461c042e23b4955e2391665584f8ebb0db2cd7e441647ad5cf247e775dfd1` |
| `sandbox-native-debug.aar` | sandbox-native | armeabi-v7a | `jni/armeabi-v7a/libcontrolled_sandbox_native.so` | 1239304 | `3456432c6f9e399746b59044bd72dce7eb7c4bcca69947b883b274abbe5d5e09` |
| `sandbox-native-debug.aar` | sandbox-native | x86 | `jni/x86/libcontrolled_sandbox_native.so` | 2112144 | `96e83379000abc3dd4a81175fa788401de93b277eb6fe29678c7cb7e8ca239c4` |
| `sandbox-native-debug.aar` | sandbox-native | x86_64 | `jni/x86_64/libcontrolled_sandbox_native.so` | 2049840 | `118dd14f4a1fb54bf65859460bfaefac1cd44b449aceb6ab0e1d72ef71494fdc` |

每个 artifact 内同名 library 的 ABI coverage 完整；没有 Gradle duplicate ZIP entry 或需要静默 `pickFirst` 的重复记录。中间目录允许存在 transitive ABI copy：例如 app 的 merged intermediates 有 32-bit common library，companion 的 merged intermediates 有 64-bit common library；最终 `abiFilters` 正确将其排除，最终包没有 Host/Companion 错误混装。

## 5. Native Library Matrix

| Library | 32-bit ARM | 64-bit ARM | x86 | x86_64 |
|---|---|---|---|---|
| `libcas_native_enf.so` | fixture-compat32 | app/fixture | fixture-compat32 | app/fixture/compat32 |
| `libcontrolled_sandbox_native.so` | companion/AAR | app/AAR | companion/AAR | app/AAR |
| `libcontrolled_sandbox_native32.so` | companion | — | companion | — |
| `libcontrolled_sandbox_fixture.so` | fixture-compat32 | fixture-basic | fixture-compat32 | fixture-basic/compat32 |
| `libfixture_adv_payload.so` | fixture-compat32 | fixture-basic | fixture-compat32 | fixture-basic/compat32 |
| `libcontrolled_sandbox_fixture_scale.so` | — | activity-scale | — | activity-scale |

`FIRST_PARTY_NATIVE_COUNT=29`（按 artifact/ABI/library occurrence），`THIRD_PARTY_NATIVE_COUNT=0`。因此不存在“第三方库只覆盖 x86_64 而正式 arm64 声明缺失”的 blocker。

## 6. ELF Matrix

下表按 unique library/ABI contract 展开；同一 contract 在多个 APK/AAR 中的 package occurrences 均已重新审计。所有 29 条的 `OS/ABI=UNIX - System V`、`Type=DYN (Shared object file)`、entry 为 `0x0`，并且 `Result=PASS`。

| Library | ABI | ELF Class | Machine | LOAD `p_align` | Result |
|---|---|---|---|---|---|
| `libcas_native_enf.so` | arm64-v8a | ELF64 | AArch64 | 0x4000 ×3 | PASS |
| `libcas_native_enf.so` | armeabi-v7a | ELF32 | ARM | 0x4000 ×3 | PASS |
| `libcas_native_enf.so` | x86 | ELF32 | Intel 80386 | 0x4000 ×3 | PASS |
| `libcas_native_enf.so` | x86_64 | ELF64 | Advanced Micro Devices X86-64 | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_fixture.so` | arm64-v8a | ELF64 | AArch64 | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_fixture.so` | armeabi-v7a | ELF32 | ARM | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_fixture.so` | x86 | ELF32 | Intel 80386 | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_fixture.so` | x86_64 | ELF64 | Advanced Micro Devices X86-64 | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_fixture_scale.so` | arm64-v8a | ELF64 | AArch64 | 0x4000 ×2 | PASS |
| `libcontrolled_sandbox_fixture_scale.so` | x86_64 | ELF64 | Advanced Micro Devices X86-64 | 0x4000 ×2 | PASS |
| `libcontrolled_sandbox_native.so` | arm64-v8a | ELF64 | AArch64 | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_native.so` | armeabi-v7a | ELF32 | ARM | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_native.so` | x86 | ELF32 | Intel 80386 | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_native.so` | x86_64 | ELF64 | Advanced Micro Devices X86-64 | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_native32.so` | armeabi-v7a | ELF32 | ARM | 0x4000 ×3 | PASS |
| `libcontrolled_sandbox_native32.so` | x86 | ELF32 | Intel 80386 | 0x4000 ×3 | PASS |
| `libfixture_adv_payload.so` | arm64-v8a | ELF64 | AArch64 | 0x4000 ×3 | PASS |
| `libfixture_adv_payload.so` | armeabi-v7a | ELF32 | ARM | 0x4000 ×3 | PASS |
| `libfixture_adv_payload.so` | x86 | ELF32 | Intel 80386 | 0x4000 ×3 | PASS |
| `libfixture_adv_payload.so` | x86_64 | ELF64 | Advanced Micro Devices X86-64 | 0x4000 ×3 | PASS |

最终 package ELF：`ELF_TOTAL=29`、`ELF_PASS=29`、`ELF_FAIL=0`、`ELF_CLASS_MACHINE_MISMATCH=0`。另外扫描了 33 个 intermediate `.so`，其 ABI path、ELF identity 和 LOAD alignment 也全部 PASS（`33/33`）。

## 7. 16KB Static Packaging Gate

| Gate | Total | Pass | Fail | Evidence |
|---|---:|---:|---:|---|
| ELF LOAD alignment | 29 | 29 | 0 | `llvm-readelf -l`，每个 LOAD `0x4000` |
| APK native packaging | 25 | 25 | 0 | native entries STORED，local data offset `% 0x4000 = 0` |
| APK ZIP alignment | 9 APK | 9 | 0 | `zipalign -c -v -P 16 4` |
| AAR native entries | 4 | 4 | 0 | ELF PASS；AAR DEFLATED 标为 `NOT_INSTALLABLE_REPACKAGED` |
| Third-party native | 0 | 0 | 0 | 没有第三方 `.so`，故无遗漏 |
| CMake max-page-size | 5 CMake files | 5 | 0 | 每个 native target link `-z,max-page-size=16384` |

```text
PAGE_SIZE_16K_STATIC_TOTAL=29
PAGE_SIZE_16K_STATIC_PASS=29
PAGE_SIZE_16K_STATIC_FAIL=0
```

这是静态 readiness；16KB 设备上的 loader/runtime 行为仍留给 C6-T02B。

## 8. Dynamic Dependency / RPATH / Export Audit

| Library | ABI coverage | DT_NEEDED | Missing | Result |
|---|---|---|---:|---|
| `libcas_native_enf.so` | all four | `liblog.so, libm.so, libdl.so, libc.so` | 0 | PASS |
| `libcontrolled_sandbox_native.so` | all four | `libandroid.so, libdl.so, liblog.so, libm.so, libc.so` | 0 | PASS |
| `libcontrolled_sandbox_native32.so` | arm32, x86 | `libandroid.so, liblog.so, libdl.so, libm.so, libc.so` | 0 | PASS |
| `libcontrolled_sandbox_fixture.so` | all four | `liblog.so, libdl.so, libandroid.so, libmediandk.so, libm.so, libc.so` | 0 | PASS |
| `libfixture_adv_payload.so` | all four | `libm.so, libdl.so, libc.so` | 0 | PASS |
| `libcontrolled_sandbox_fixture_scale.so` | arm64, x86_64 | `liblog.so, libm.so, libdl.so, libc.so` | 0 | PASS |

所有 `DT_NEEDED` 均为 Android/NDK 系统库或同包库；没有 CAS 内部缺失库、host `.dll`、x86-only dependency 或 build-machine path。

```text
MISSING_NATIVE_DEPENDENCIES=0
RPATH_RUNPATH_ISSUES=0
```

动态 symbol 允许正常的 libc/Android unresolved imports，并以 DT_NEEDED 解析；29 个 package records 的 undefined dynamic symbol 总数为 3417。JNI export contract 按 library/ABI 比较：

| Library | JNI exports per ABI | Contract |
|---|---:|---|
| `libcas_native_enf.so` | 1 | `JNI_OnLoad`, all four equal |
| `libcontrolled_sandbox_native.so` | 42 | NativePolicy entrypoints, all four equal |
| `libcontrolled_sandbox_native32.so` | 44 | NativePolicy + NativeCompanionBridge, both 32-bit equal |
| `libcontrolled_sandbox_fixture.so` | 9 | all four equal |
| `libfixture_adv_payload.so` | 1 | all four equal |
| `libcontrolled_sandbox_fixture_scale.so` | 1 | arm64/x86_64 equal |

```text
EXPORT_CONTRACT_ISSUES=0
```

## 9. Architecture-specific source / syscall / width audit

`tools/verification/abi_audit.py` 对全仓 native source（54 个 C/C++/assembly/header 文件，排除 `ref/`、`build/`、`out/`）完成扫描：

| Concern | x86_64 | x86 | arm64 | arm32 | Result |
|---|---:|---:|---:|---:|---|
| Architecture macro coverage in core native paths | 7 files | 8 files | 7 files | 8 files | PASS |
| Raw syscall/inline-asm dispatch files | 6 | 6 | 6 | 6 | PASS |
| Numeric syscall literals bypassing arch dispatch | 0 | 0 | 0 | 0 | PASS |
| Unresolved architecture TODO/stub candidates | 0 | 0 | 0 | 0 | PASS |

`native_boundary.cpp`、`native_hook.cpp`、`hostile_seccomp.cpp`、`verification/native-enforcement/enforcement_native.cpp` 以及 fixture 的 adversarial/C3 native 文件均有 `__x86_64__`、`__i386__`、`__aarch64__`、`__arm__` 分支；`hostile_seccomp` 的 `AUDIT_ARCH_*` 与 `SYS_*` 选择按架构条件编译。`sandbox-companion32/native_companion_jni.cpp` 只要求 `__arm__`/`__i386__`，因为它是明确的 32-bit companion entrypoint，不是漏掉 64-bit 分支。

```text
ARCH_SPECIFIC_SOURCE_AUDIT=PASS
POINTER_WIDTH_AUDIT=PASS
JNI_TYPE_AUDIT=PASS
```

Pointer/JNI review 覆盖 `NativePolicy.java`/`native_policy_jni.cpp`、hook trampoline、function pointer、FD metadata 和 companion Parcelable/AIDL contract：未发现 pointer→`int`/`jint` 截断；`uintptr_t`/`uint64_t`/`size_t`/`jlong` 使用与目标语义一致。跨进程 request/result 只传 protocol、package/session/user/revision/ABI/operation、generation、nonce、FD/artifact size/SHA 等稳定 Binder/Parcel 字段，没有 native pointer、process-local handle 或地址。没有产品代码用 `System.getProperty("os.arch")` 决定 Guest ABI；Guest loader 使用 `Build.SUPPORTED_ABIS` 与 Guest package `lib/<abi>` 的交集，并保留 `nativeLibraryDir`、`splitSourceDirs`、`splitNames` 投影。

## 10. Companion32 / Host Separation

```text
COMPANION32_STATIC_STATUS=PASS
```

`sandbox-companion32` 是独立 `applicationId=com.warden.controlledsandbox.companion32` 的 32-bit application，最终只含 `armeabi-v7a`/`x86`。其 `:sandbox_server32` / `:native32` process contract、签名 permission、AIDL/Parcelable fields 与现有 companion guard 均通过；`libcontrolled_sandbox_native32.so` 与 `NativeCompanionBridge` 的 32-bit identity/export/ELF 结果正确。Host `app` 最终只含 `arm64-v8a`/`x86_64`，没有 `native32`。

中间 merged/stripped 目录中的 transitive common `libcontrolled_sandbox_native.so` 可能暂时出现对侧 ABI，这是依赖图的中间状态；最终 packaging filter 已验证不泄漏到 Host/Companion 最终 APK。cross-bitness dynamic path 留 C6-T02D。

## 11. Fixes applied

1. 在 `fixture-activity-scale/src/main/cpp/CMakeLists.txt` 为 `controlled_sandbox_fixture_scale` 增加 `-Wl,-z,max-page-size=16384`；修复了之前该库 arm64/x86_64 `p_align=0x1000` 的静态缺陷。
2. 在 `app`、`fixture-compat32`、`sandbox-companion32` 设置 `packagingOptions.jniLibs.useLegacyPackaging=false`，并移除 source manifest 中会促使 native 压缩/触发 legacy packaging 的 `android:extractNativeLibs` 属性；最终 25/25 APK native entries STORED 且对齐。
3. 扩展 `tools/verification/matrix_validator.py`：ABI cell 去重/未知 ABI/declared-but-missing artifact/未知 status/artifact ABI mismatch；native inventory 去重、ELF machine mismatch、unclassified library、16KB status missing/failure 和 dependency failure 均 fail-closed。
4. 新增 `tools/verification/abi_audit.py`：从实际 Gradle outputs 扫描 APK/AAR、解包 hash、调用 `llvm-readelf`/`llvm-nm`/`zipalign`，审计 intermediate `.so`、依赖、RPATH/RUNPATH、导出契约和架构源码；verbose evidence 只写 `out/verification`。
5. 在 `tools/verification/test_harness.py` 增加 ABI/native validator 回归用例，覆盖 declared missing、unknown ABI、duplicate record、ELF mismatch、unclassified library 和 missing 16KB status。
6. 更新唯一进度账本：`C6-T01G=PASS_WITH_ENVIRONMENT_DEFERRED`、`C6-T01=DONE_WITH_ENVIRONMENT_DEFERRED`，建立 `C6-D01`/`C6-D02=DEFERRED_ENVIRONMENT`；API37 仍不标 PASS。

## 12. C6-T02 follow-up matrix

| Task | Static-ready cells / paths | Deferred dynamic boundary |
|---|---|---|
| C6-T02B | `app-debug.apk` arm64-v8a + x86_64；`fixture-basic` arm64-v8a + x86_64；`fixture-activity-scale` arm64-v8a + x86_64；验证 ELF/ZIP 已 PASS 的同包 native load | 真实 16KB device/AVD loader、relocation、first load、late dlopen；不在 T02A 执行 |
| C6-T02C | Xiaomi ARM64 真机使用 `app` arm64-v8a、`fixture-basic` arm64-v8a、`fixture-activity-scale` arm64-v8a；AAR consumer integration 取 `sandbox-native` arm64-v8a | ARM64 dynamic/smoke/capability matrix；开发机 x86_64 不作为 ARM64 runtime 替代 |
| C6-T02D | `sandbox-companion32` armeabi-v7a/x86；Host→Companion Binder request/result、process bitness、artifact FD/size/SHA、NativeAbiRoutePlanner、Guest split/native path | Companion32 动态和 cross-bitness lifecycle；不在 T02A 执行 |
| C6-T02E | — | ARM32 product scope decision；本任务不删除或裁剪现有声明 |

## 13. Build / validator / hygiene gates

```text
GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS (clean assembleDebug)
UNIT_TESTS=PASS (./gradlew test; Python harness 16/16)
ABI_MATRIX_VALIDATOR=PASS
NATIVE_INVENTORY_VALIDATOR=PASS
EVIDENCE_GIT_HYGIENE=PASS (out/verification ignored; no generated APK/.so/readelf dump tracked; pre-existing ref/upstream .so unchanged)
REF_STATUS=UNCHANGED
```

本地证据目录：`out/verification/t57-r03/c6-t02a-abi-elf-static-convergence/`。其中 `config.json`、`artifact_inventory.json`、`native_inventory.json`、`elf_matrix.json`、`source_audit.json`、`audit_summary.json` 和 `raw/` 仅用于本地复核，不进入 Git。

## 14. Final receipt

```text
C6-T02A
RESULT=PASS
START_HEAD=345924455d30432dbd88c17985c9d499d6630f2c
FINAL_HEAD=HEAD (resolved by the final single-commit Git gate)

C6_T01_STATUS=DONE_WITH_ENVIRONMENT_DEFERRED
API37_DEFERRED=C6-D02
MEMORY_LIMITER_DEFERRED=C6-D01

DECLARED_ABIS=armeabi-v7a,arm64-v8a,x86,x86_64
BUILT_ABIS=armeabi-v7a,arm64-v8a,x86,x86_64

APK_AAR_TOTAL=13
NATIVE_LIBRARY_TOTAL=29
FIRST_PARTY_NATIVE_TOTAL=29
THIRD_PARTY_NATIVE_TOTAL=0

ARMEABI_V7A_ARTIFACTS=3
ARM64_V8A_ARTIFACTS=4
X86_ARTIFACTS=3
X86_64_ARTIFACTS=5

ELF_TOTAL=29
ELF_PASS=29
ELF_FAIL=0

ELF_CLASS_MACHINE_MISMATCH=0
MISSING_NATIVE_DEPENDENCIES=0
RPATH_RUNPATH_ISSUES=0
EXPORT_CONTRACT_ISSUES=0

ARCH_SPECIFIC_SOURCE_AUDIT=PASS
POINTER_WIDTH_AUDIT=PASS
JNI_TYPE_AUDIT=PASS

COMPANION32_STATIC_STATUS=PASS

PAGE_SIZE_16K_STATIC_TOTAL=29
PAGE_SIZE_16K_STATIC_PASS=29
PAGE_SIZE_16K_STATIC_FAIL=0

ARM64_BUILD_STATIC=PASS
ARM32_BUILD_STATIC=PASS
X86_BUILD_STATIC=PASS
X86_64_BUILD_STATIC=PASS

ABI_MATRIX_VALIDATOR=PASS

GRADLE_PROJECTS=PASS
ASSEMBLE_DEBUG=PASS
UNIT_TESTS=PASS
EVIDENCE_GIT_HYGIENE=PASS

REF_STATUS=UNCHANGED
REPORT=reports/t57-r03/c6/C6_T02A_ABI_ELF_STATIC_CONVERGENCE_REPORT.md
GIT_STATUS=CLEAN
NEXT_TASK=C6-T02B
```
