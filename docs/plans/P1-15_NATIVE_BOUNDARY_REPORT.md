# P1-15 Native normal-path and boundary report

**Status:** `DEVICE_CORE_PASS` — 2026-09-09. This closes the P1-15 AVD scope. It is not an ARM64, Xiaomi, hostile-native-process, or VA PRO equivalence claim.

## Decision and scope

`P1-15_REFERENCE_DECISION.md` was written before any possible P1-15 change. It compares NBB `IOCore.enableRedirect` and `UnixFileSystemHook.init` with VA OSS `NativeEngine` and `IOUniformer`, then maps the relevant CAS methods: revision-scoped path policy, guest PLT/GOT replacement, loader-source/ELF/FD validation, and runtime-page-size alignment. NBB's per-JNI-hook continuation and VA's historical private-linker implementation are not used as proof of a complete native boundary.

Current fixture source already emits `CASE NATIVE-ADV-010 done` after it collects the case result. The historical missing-marker problem did not reproduce, so no native production or fixture change was justified. `NativeHookRuntime`, loader policy, ABI planner, and trust policy are unchanged by P1-15.

## Current AVD normal-path receipt

`out/verification/p1-15-api36-native-20260909/` was run on the attached official API 36 AVD:

| Coordinate | Observed result | Evidence |
| --- | --- | --- |
| Device | `google/sdk_gphone64_x86_64/emu64xa`, API 36, `x86_64`, actual page size `4096` | `device-metadata.json` |
| JNI and controlled loader | PASS; JNI return/probe plus guest `dlopen` source evidence | `capability-matrix.json`, `CAP-NATIVE-LOADER-JNI-HOOKS/` |
| File/proc/FD | PASS; file/proc records and FD lifecycle/recycle markers are present | `CAP-NATIVE-PROC-FD/` |
| Media / late loader | PASS; late `dlopen` and `android_dlopen_ext` completed with `abi=x86_64 pageSize=4096` | `CAP-NATIVE-MEDIA-16K/` |
| Adversarial completion markers | `NATIVE-ADV-001` through `NATIVE-ADV-010` each emitted `done` | `CAP-NATIVE-ADVERSARIAL-BOUNDARY/` |
| Raw-SVC/seccomp experiment | `EXPECTED_LIMITATION`, not a product PASS or an isolation claim | `CAP-NATIVE-ADVERSARIAL-BOUNDARY/` |

The combined capability invocation also exposed an unrelated stale split-fixture version mismatch (`INSTALL_FAILED_INVALID_APK`, base version 1 versus feature version 2). Its first-failure receipt is retained in the P1-15 run. It was repaired as P1-13 packaging metadata only, after the NBB/VA package/split decision recorded in `P1-13_SPLIT_REGRESSION_DECISION.md`; an exclusive rerun passed in `out/verification/p1-13-api36-version-coherent-20260909/run.json`. The P1-15 app and native fixture APKs were unchanged, and this split fixture contains no native libraries, so the native receipts above remain applicable.

## ELF / ZIP / 16 KB evidence

`tools/verification/abi_audit.py --root .` produced `out/verification/p1-15-abi-elf-static-20260909/audit_summary.json` with `result=PASS`: 29/29 native libraries pass ELF class/machine checks, all 25 APK native packaging checks pass, no ZIP/ELF findings exist, and all 29 static 16 KB checks pass. This is a static packaging/alignment audit, not runtime page-size evidence.

The already-recorded runtime short test in `reports/t57-r03/c6/C6_T02B_16KB_DYNAMIC_VALIDATION_REPORT.md` is an Android 15 / API 35 official Google APIs PS16K `x86_64` AVD with actual page size `16384`. Its normal native loader/JNI/proc-FD/media matrix passes and records late `dlopen` plus `android_dlopen_ext`. That coordinate is retained as x86_64/16 KB evidence only: it is not generalized to ARM64, MuMu translation, or a Xiaomi device. ARM64 16 KB remains conditional P2-11 work.

## Explicit residual boundaries

Normal-path evidence supports the recorded `EXPLICITLY_TRUSTED` / `BEST_EFFORT` compatibility contract only. Raw SVC, handwritten/custom loaders, foreign linker namespaces, unmodelled FD inheritance, and debug seccomp proof-of-concept behavior are not production isolation. No result here is relabelled as a security guarantee, and no VA PRO behavior is inferred from VA OSS history.

## Focused verification

* `:app:assembleDebug :fixture-basic:assembleDebug`: PASS.
* `tools/static_android_compile.py`: PASS (the repository's existing four compiler warnings remain warnings only).
* API 36 native capability receipt: normal native cases PASS; raw-SVC boundary remains `EXPECTED_LIMITATION` as above.
* `tools/verification/abi_audit.py`: PASS, 29/29 ELF and static 16 KB checks, 25/25 APK native ZIP-packaging checks.
* The host-only `scripts/test-native.sh` was not counted: this Windows environment has neither a working Unix `bash` nor a host C++ compiler. Android build, static audit, and device evidence above are the acceptance evidence.

Large regression and the eight-hour stability run were not started; they remain final-stage tasks. **Next task: P1-16.**
