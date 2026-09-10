# P1-13 package / split / dynamic-load lifecycle report

**Status:** `DEVICE_CORE_PASS` — 2026-09-09. This closes the P1-13 AVD scope. It does not claim ARM commercial-app, Xiaomi, or native-bearing-split coverage; those remain respectively with P2-04/05, P2-01 onward, and P1-15.

## Reference decision and change boundary

`P1-13_REFERENCE_DECISION.md` records the NBB/VA methods inspected before this work: NBB `PackageManagerCompat.generateApplicationInfo`/`getResources` and `IOCore.enableRedirect`, and VA `PackageParserEx.initApplicationInfoBase` and `NativeEngine.startDexOverride`/`enableIORedirect`. The conclusion was to retain CAS's stronger full artifact-revision check: `PackageRevisionSetVerifier` verifies base plus all split digests and aggregate revision before `GuestRuntimeEnvironment`, `GuestClassLoader`, and `GuestResourceLoader` receive the paths. No production loader, native-route, trust policy, or timeout was changed.

The only product-adjacent additions are debug-only API-34 DCL evidence and a fixture resource coordinate. `p1-13-dcl-probe` copies an installed local fixture APK into the debug Host's private code cache: a file made read-only before writing loads with `PathClassLoader`; the same writable file is rejected by the platform. It cannot accept arbitrary external/network code. The split fixture adds a language-qualified base resource, so bundletool emits a real `split_config.en.apk`; the feature logs the value resolved from that installed configuration split.

## AVD lifecycle matrix

| API / ABI / page size | Installed set | DCL positive and negative | Three lifecycle rounds, users 0/1 | Result |
| --- | --- | --- | --- | --- |
| 34 / x86_64 + arm64 translation / 4 KiB | base + `split_config.en` + `fixtureSplitFeature` | PASS | PASS | `out/verification/p1-13-api34-config-final-20260909/run.json` |
| 35 / x86_64 + arm64 translation / 4 KiB | same | PASS | PASS | `out/verification/p1-13-api35-config-final-20260909/run.json` |
| 36 / x86_64 + arm64 translation / 4 KiB | same | PASS | PASS | `out/verification/p1-13-api36-config-final-20260909/run.json` |

Each receipt has `attempt=1`, `retryBudget=0`, and `automatic_retry_performed=false`. The debug-Host force-stop preceding an independent request is an endpoint-retirement fence only: the one-shot diagnostic Activity shuts down its own worker after publishing a receipt. It does not retry, replay, or hide a Guest/package operation. Every install is checked by `pm path` for `base.apk`, `split_config.en.apk`, and `split_fixtureSplitFeature.apk`; imports record `splitCount=2`, `nativeLibCount=0`, and the feature log records `baseConfigMarker=base-en-config`. Every round imports, starts base and feature, clears, and deletes each virtual user.

## Revision and rejection evidence

* `out/verification/p1-13-api36-revision-20260909/run.json`: signed v1 base/config/feature installs and imports; signed v2 installs and imports; CAS catalog rollback returns `ROLLED_BACK`; signed v1 then installs with the explicit Android downgrade flag and re-imports. The recorded package versions are 1 → 2 → 1 and all three split paths are retained.
* `out/verification/p1-13-api36-version-rejection-20260909.txt`: the same v1 set without the downgrade flag is rejected with `INSTALL_FAILED_VERSION_DOWNGRADE`; the explicit `-d` path is separately recorded above.
* `out/verification/p1-13-api34-negative-install-20260909.txt`: a feature APK re-signed with a distinct test certificate is rejected with `INSTALL_FAILED_INVALID_APK` / `signatures are inconsistent`. The original and alternate SHA-256 signer digests differ.
* `PackageRevisionSetVerifierSelfTest`, run by `tools/static_android_compile.py`, passes the changed-split digest rejection and missing-split-dependency rejection. This is the appropriate CAS-level missing/tampered-set authority; a host PackageManager may legitimately install a standalone base if the feature is not declared required, so no base-only install is relabelled as a rejection.

## Native boundary

This is a Java-only split set. The observed `nativeLibCount=0` and `nativeBytesExtracted=0` are the correct ABI result for every base/config/feature revision, not a simulated native success. No archive entry was classified as ELF and no packer was inferred. Native-bearing split ABI, JNI, FD, and 16 KiB behavior are explicitly transferred to P1-15; ARM protected-app behavior remains P2-04/05.

## Final focused verification

* `tools/static_android_compile.py`: PASS (module-scoped Host compilation plus immutable multi-APK revision-set verifier and the repository self-test suite).
* `scripts/check-split-install-sessions.py`: PASS.
* `scripts/check-package-lifecycle-transaction.py`: PASS; this checker was aligned with the existing `deleteInstanceWithOperation` stop barrier and `importInstalledApplicationAndEnsure` entrypoint, without changing lifecycle production code.
* `git diff --check`: PASS.

Large regression and the eight-hour stability run were not started and remain final-stage work.

## 2026-09-09 packaging-regression closure

The P1-15 capability invocation preserved a real installation failure: the base fixture declared version code 1 while the dynamic-feature APK still declared version code 2, so Android rejected the set as inconsistent. Before changing it, `P1-13_SPLIT_REGRESSION_DECISION.md` rechecked NBB `PackageManagerCompat` and VA `PackageParserEx`/`NativeEngine` paths and retained CAS's requirement for one complete, internally coherent split revision set. `fixture-split-feature/build.gradle` now consumes the same `p113VersionCode` and `p113VersionName` properties/defaults as the base; CAS runtime code and split-session validation were not loosened.

After rebuilding, `apkanalyzer` reported version 1 for both base and feature, direct `adb install-multiple` accepted the set, and the exclusive rerun `out/verification/p1-13-api36-version-coherent-20260909/run.json` passed on its first attempt with no automatic retry. It covers base + configuration split + feature, three lifecycle rounds, and virtual users 0 and 1.
