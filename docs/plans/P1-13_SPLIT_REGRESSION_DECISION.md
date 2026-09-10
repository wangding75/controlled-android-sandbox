# P1-13 regression decision — keep dynamic-feature build artifacts version-coherent

Date: 2026-09-09. Recorded before the corrective build-configuration change.

## Evidence and reference-path check

The current API 36 capability receipt
`out/verification/p1-15-api36-native-20260909/cases/CAP-SPLIT-APK-CLASSLOADER/capability.json`
preserves the first failure: Android rejects the set with
`INSTALL_FAILED_INVALID_APK ... version code 2 inconsistent with 1`.
`apkanalyzer manifest print` identifies the selected base APK as versionCode `1` / versionName
`1.0-split` while its dynamic feature APK is a stale versionCode `2` / versionName `2.0-split`.

The NBB/VA analysis recorded in `P1-13_REFERENCE_DECISION.md` remains applicable before this
change: NBB `PackageManagerCompat.generateApplicationInfo` and `getResources` consume the
installed package projection rather than independently-versioned artifacts; VA
`PackageParserEx.initApplicationInfoBase` and `NativeEngine.startDexOverride` only receive the
already selected package paths. Neither is a reason to tolerate an invalid Android split session.
CAS's `PackageRevisionSetVerifier` likewise requires a complete verified artifact revision before
constructing `GuestPackageSpec`/`GuestClassLoader`.

## Chosen fix and boundary

The P1-13 fixture already makes its base module's `versionCode` and `versionName` derive from
`p113VersionCode` and `p113VersionName`, which the lifecycle/revision runner supplies for each
revision. The dynamic-feature module did not declare the same inputs, permitting its prior
manifest output to remain at v2 when the base was rebuilt at v1. Make the feature module derive
the same two values, with the same v1 defaults. This is a fixture packaging correction only.

Do not change CAS import, revision validation, split routing, signature handling, package-manager
projection, retries, downgrade policy, or Android install flags. A mismatched split set remains a
real rejected input. After the rebuild, inspect both manifests and rerun the focused P1-13 split
lifecycle fixture exactly once; then rerun only the native P1-15 cases whose APK provenance was
affected by the prior capability setup.
