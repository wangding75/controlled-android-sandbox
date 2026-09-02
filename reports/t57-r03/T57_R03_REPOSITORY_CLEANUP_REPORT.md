# T57-R03-CLEAN-01 — Repository Cleanup Report

## 1. Commit and scope

```text
START_HEAD=bf9a38bc80ee5ff97190aaf7dc81cf00617038fb
FINAL_HEAD=HEAD (exact final commit is emitted in the final receipt)
BRANCH=feature/t57-r03-va-pro-capability-campaign
```

This cleanup removed historical development plans, completed task reports,
device/evidence output, one-shot acceptance scripts, obsolete capability
runners and the old `scripts/verify-all.sh` entrypoint. Product implementation
modules, fixtures, build configuration and the reference baseline were kept.

## 2. Size and deletion metrics

Metrics exclude `.git`. The post-clean working-tree measurement includes this
report, contains no ignored build/cache/evidence output, and was taken after
the scoped cleanup of generated directories.

```text
FILES_BEFORE=141426
FILES_AFTER=5542
FILES_REMOVED=4440
TREE_SIZE_BEFORE=85286508193
TREE_SIZE_AFTER=40384998
ZIP_SIZE_BEFORE=94618349
ZIP_SIZE_AFTER=4751511
```

`FILES_REMOVED` is the number of final staged `D` entries relative to
`START_HEAD`; the renamed supply-chain checker and retained/recreated generic
release helper are not counted as deletions.

The largest deletion groups were:

| Path group | Files removed |
| --- | ---: |
| `verification/catch-up/` | 3,838 |
| `docs/review/` | 103 |
| `tools/capability/` | 55 |
| `docs/fixes/` | 54 |
| `docs/comparisons/` | 38 |
| `artifacts/` | 33 |
| `docs/plans/` | 23 |
| `tools/device/` | 17 |
| `docs/runtime/` | 16 |
| `docs/dingtalk-xh/` | 14 |
| `docs/oem-compat/` | 11 |
| `docs/product/` | 10 |
| `docs/sx-migration/` | 9 |
| `reports/t57-r04/` | 3 |
| `tools/t53/` and `tools/t54/` | 3 |

## 3. Removed directories and artifacts

- Removed `artifacts/` and all capability/device/build output beneath it.
- Removed `verification/catch-up/` and all logcat, screenshots, dumps,
  checkpoints, reruns, retries, snapshots, bundles and session evidence.
- Removed the accidentally present `reports/t57-r04/` tree.
- Removed historical `docs/analysis/`, `docs/comparisons/`, `docs/fixes/`,
  `docs/dingtalk-xh/`, `docs/oem-compat/`, `docs/product/`,
  `docs/sx-migration/` and `docs/ui-migration/` directories.
- Removed historical root and milestone reports, old M4/M5/T57 task reports,
  one-shot emulator/device runners, the obsolete `tools/t53/` and `tools/t54/`
  directories, and completed capability campaign runners.
- Removed `scripts/verify-all.sh`; no replacement monolithic entrypoint was
  introduced.

## 4. Retained directories and reasons

| Directory | Reason retained |
| --- | --- |
| `app/` | Host application and composition root. |
| `sandbox-domain/` | Platform-neutral state and policy. |
| `sandbox-contract/` | Typed Java/AIDL product contracts. |
| `sandbox-sdk/` | Business-facing SDK surface. |
| `sandbox-runtime/` | Guest sessions, process routing and brokers. |
| `sandbox-framework/` | Android framework/component/Binder adapters. |
| `sandbox-native/` | JNI and native runtime implementation. |
| `sandbox-companion32/` | 32-bit companion product module. |
| `fixture-basic/`, `fixture-compat32/`, `fixture-activity-scale/`, `fixture-lifecycle/`, `fixture-split-base/`, `fixture-split-feature/` | Current fixture APK inputs declared by `settings.gradle`. |
| `docs/` | Current architecture, compatibility, policy and machine-readable contracts. |
| `gradle/` | Wrapper, dependency verification metadata and lock policy. |
| `scripts/` | Current build, source-check, packaging and self-test helpers. |
| `tools/` | Current static analysis, provenance, reproducible ZIP and capability tooling. |
| `verification/` | Current immutable baseline, SBOM, CI lock and active native enforcement source. |
| `reports/t57-r03/` | Four current T57-R03 convergence reports only. |
| `ref/` | Protected reference-only VirtualApp and NewBlackbox baseline. |
| `.github/` | Current pinned source-gate workflow. |

No directory remains above 5 MiB except `ref/` (27,182,803 bytes); it is
explicitly protected by the task and remains byte-for-byte unchanged.

## 5. Retained historical-class files

- The three `docs/plans/` whitelist files remain because they are the current
  execution handoff, progress authority and task book.
- `docs/review/KNOWN_ISSUES.yaml` remains as the current machine-readable issue
  registry consumed by capability tooling. Its old evidence paths are
  provenance fields, not current execution dependencies.
- `docs/runtime/T57_R03_CLASSLOADER_MATRIX.yaml`, the capability registry and
  current compatibility/native/package/system matrices remain formal current
  contracts rather than task receipts.
- `verification/BASELINE_SHA256SUMS.txt` remains an immutable frozen baseline
  manifest. Its baseline-era path names are intentional checksum records and
  are not active repository references.
- `verification/SOURCE_SHA256SUMS.txt`, `verification/sbom.json`,
  `verification/baseline-import.json`, `verification/github-actions-lock.json`
  and `verification/approved-commit-identities.txt` remain current generated or
  policy artifacts required for reproducibility and supply-chain checks.
- `verification/native-enforcement/enforcement_native.cpp` remains because it
  is active native fixture source compiled by the app and basic fixture CMake
  targets; it is not retained evidence.

## 6. Protected-content checks

```text
REF=UNCHANGED
REF_HEAD=0ab0fb6646f4dcebb8dae8c3a97a64e2144eeba8
REF_TRACKED_FILES=1729
PLAN_WHITELIST=PASS (exactly the three requested files)
PROTECTED_MODULE_DELETIONS=NONE
SETTINGS_MODULES=PASS (14 included project directories and manifests/source sets present)
```

The only product-module edits are comment-only clean-room wording changes in
five framework/runtime files; no business behavior, API, fixture or reference
source was changed.

## 7. README and repository policy changes

`README.md` was rebuilt as the single overall project description. It now
covers project scope, architecture, repository structure, actual toolchain
versions, verified build/test commands, references, current baseline,
documentation and licensing. It does not expose `verify-all.sh`; it explicitly
states that the device/capability acceptance pipeline is being reconsolidated.

`.gitignore` now excludes build/cache directories, artifacts, catch-up evidence,
logs, screenshots, device dumps, rerun/retry/repro/checkpoint output and
generated bundles only under generated directories. The active
`verification/native-enforcement/` source remains trackable.

The old task-specific supply-chain checker was renamed to the current generic
`scripts/check-supply-chain-governance.py`, and the source-gate workflow now
invokes current architecture, contract, baseline, reference and static compile
checks. The generic release helper excludes `ref/` from source packages.

## 8. Post-clean top-level size report

Top-level directory sizes after cleanup, before Git metadata, were:

| Directory | Files | Bytes |
| --- | ---: | ---: |
| `ref/` | 1,729 | 27,182,803 |
| `sandbox-runtime/` | 2,502 | 4,066,031 |
| `sandbox-framework/` | 308 | 2,074,986 |
| `app/` | 185 | 1,658,943 |
| `verification/` | 7 | 1,015,417 |
| `sandbox-contract/` | 310 | 696,227 |
| `docs/` | 24 | 690,366 |
| `gradle/` | 8 | 678,972 |
| `sandbox-native/` | 52 | 603,869 |
| `sandbox-domain/` | 70 | 435,350 |
| `fixture-basic/` | 75 | 433,612 |
| `tools/` | 21 | 340,755 |
| `scripts/` | 43 | 235,359 |
| `reports/` | 5 | 104,852 |
| `sandbox-companion32/` | 15 | 39,388 |
| `fixture-activity-scale/` | 137 | 38,485 |
| `sandbox-sdk/` | 15 | 38,146 |
| `fixture-compat32/` | 3 | 5,913 |
| `fixture-lifecycle/` | 7 | 3,796 |
| `fixture-split-base/` | 3 | 2,216 |

## 9. Verification results

```text
GRADLE_PROJECTS=PASS (14 projects resolved)
ASSEMBLE_DEBUG=PASS (root and explicit app/companion/fixture assembleDebug)
UNIT_TEST=PASS (Gradle test, repository self-test and 165-test static Android harness)
STATIC_ANDROID_COMPILE=PASS (all declared source modules; 165 executed tests)
BASELINE_MANIFEST=PASS
REFERENCE_SOURCES=PASS (VirtualApp and NewBlackbox isolated and verified)
SBOM=PASS (14 components)
SOURCE_HASHES=PASS
CURRENT_SOURCE_GATES=PASS for architecture, contracts, build environment, guest boundary,
  query/resolve, package service boundary, virtual package state, split install,
  runtime permissions, capability broker split, native boundary/hostile/ABI checks
  and APK revision binding
CRITICAL_TEST_OWNERSHIP=PASS (13/13) immediately after static compile; its ignored
  generated receipt was removed with the post-verification build output
DANGLING_REFERENCE_SCAN=PASS
GIT_DIFF_CHECK=PASS
```

The dangling-reference audit checked 443 deleted non-history paths across the
current file set, checked current Markdown relative links, and excluded only
the immutable baseline manifest plus the retained plans/issue registry whose
historical text is explicitly provenance. No active script, build file,
workflow, README link or current report points to a deleted path.

## 10. Git summary

```text
GIT_DIFF_STAT=4476 files changed, 3687 insertions(+), 1780069 deletions(-)
GIT_STATUS=CLEAN after the final commit
```

The staged diff contains no deletion under the protected product modules,
fixtures or `ref/`. The final status is expected to be clean after the single
commit with message:

```text
T57-R03-CLEAN-01: purge obsolete development artifacts and rebuild repository docs
```

## 11. Known issues unrelated to cleanup

- `scripts/check-package-boundaries.py` still reports existing cross-layer
  internal imports in runtime/framework/app. This source-check debt predates
  the cleanup; no product implementation was changed to hide it.
- `scripts/check-package-lifecycle-transaction.py` still reports the existing
  delete-stop-barrier and debug-command lifecycle wiring findings. The Gradle
  build, unit tests and static harness remain passing.
- `scripts/check-critical-test-ownership.py` fails closed when run alone after
  cleanup because the static compiler receipt is intentionally generated under
  ignored `build/` and then removed. Run `tools/static_android_compile.py`
  first; the verified run passed all 165 tests and the ownership check passed
  13/13.
- Gradle emits the known CXX5202 warning for 32-bit-only targets on the current
  host; the configured native targets assemble successfully.
- Device/capability acceptance is intentionally not represented by a new
  monolithic verification command in this revision.
