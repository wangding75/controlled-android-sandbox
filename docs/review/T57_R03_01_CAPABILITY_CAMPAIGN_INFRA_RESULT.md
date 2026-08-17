# T57-R03-01 Capability Campaign Infrastructure Result

RESULT: PASS

RD测试 PASS 仅代表 RD_BASELINE 最低门槛。

This report does not claim VA Pro Equivalent PASS.

## Git

- branch: `feature/t57-r03-va-pro-capability-campaign`
- parent / baseline HEAD: `9e5d3e73628d80872c21776897898493925c7a97`
- parent tree: `881930a8dee8781e702bc057e5f44018568f4475`
- origin/main at start: `9e5d3e73628d80872c21776897898493925c7a97`
- working tree at start: clean tracked tree (historical ignored evidence left in place)
- campaign commit / tree: the single commit created on this branch after these files; see `git log -1 --format="%H %T"`

This task created the feature branch from `main` and did not amend, rebase, squash, force-push, merge, or push.

## Capability Registry

File: `docs/capability/CAPABILITY_REGISTRY.yaml`

- count: 14
- implementation_status: PARTIAL=13, GAP=1
- static_status: PASS=9, PARTIAL=4, UNVERIFIED=1
- rd_api32_status: PASS=11, PARTIAL=2, NOT_APPLICABLE=1
- api33-36: UNVERIFIED for all 14
- oem_status: UNVERIFIED for all 14
- commercial_app_status: UNVERIFIED for all 14
- va_pro_equivalent: NOT_PROVEN=14

`android_oem_compatibility.implementation_status` is GAP. Native hostile/direct-syscall is not PASS.

## Known Issues

File: `docs/review/KNOWN_ISSUES.yaml`

- total: 30
- M10 recorded issues: 7/7 (`KI-M10-001` … `KI-M10-007`)
- EXPECTED_BEHAVIOR / EXPECTED_WARNING: 1 (`KI-M10-004` CXX5202)
- classification: KNOWN_DEBT=4, EXPECTED_BEHAVIOR=1, NEEDS_REPRODUCTION_AND_CLASSIFICATION=12, NEEDS_GATE_AND_CODE_REVIEW=1, TEST_EVIDENCE_GAP=9, ARCHITECTURE_GAP=1, COMPATIBILITY_GAP=2
- no M10 item is labeled CURRENT_DEFECT
- CXX5202 remains EXPECTED_BEHAVIOR; 32-bit ABI was not removed

Collect-all also recorded ten pre-existing gate/token mismatches (`KI-R03-020` … `KI-R03-029`) and one RD launch-gate finding (`KI-R03-030`). Runtime/framework/native business code was not changed to clear them.

## VA Pro Corpus

File: `docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml`

- total entries: 83
- unique ids: 83
- by category: Service=12, Native=14, GMS=7, PMS=6, SystemService=6, Binder=5, PendingIntent=5, AndroidVersion=5, ClassLoader=4, Activity=3, package lifecycle=3, process=2, process death=2, split APK=2, OEM=2, Provider=2, WebView=2, Manifest=1
- cas_status: CAS_ALREADY_COVERS=1, NEEDS_TEST=27, GAP=52, UNVERIFIED=3
- `VA-709`, `VA-712`, `VA-713` are required ids absent from the locked VirtualApp README snapshot (changelog ends at 708 / 2026-07-16). They are recorded as UNVERIFIED, not invented.

Commercial changelog text is a compatibility signal only. It is not CAS implementation evidence.

## Local Audit

Command: `python tools/capability/run_local_capability_audit.py --all`

Latest classified run: `artifacts/capability-audit/all/20260817T061859Z`

- total gates: 39
- PASS: 25
- KNOWN_ISSUE: 14
- EXPECTED_WARNING: 0 (CXX5202 is a Gradle/NDK warning; seen in the later APK build)
- NEW_REGRESSION: 0
- FAIL: 14 (all classified as KNOWN_ISSUE)
- UNVERIFIED: 0
- process exit: non-zero because required gates still FAIL
- source/tests/gates/git index: not modified by the auditor

## Build

Command:

```text
gradlew.bat :app:assembleDebug :sandbox-companion32:assembleDebug :fixture-basic:assembleDebug :fixture-compat32:assembleDebug --console=plain
```

- result: BUILD SUCCESSFUL
- CXX5202: EXPECTED_WARNING on `:fixture-compat32` and `:sandbox-companion32`

| APK | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `07f266f139aa090c75a9d03246fbd099e8461e8f1020781cd2d1205d4311fda7` |
| `sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk` | `91110eb1d9c24d3d8a8607c3e39cf089fda24a0cceb232e72fd4d73aedbe7a2c` |
| `fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk` | `03b0beb03bd6ea819ef5db6b37706708458a1fa191184aa43daf6780465c7fc9` |
| `fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk` | `736cd3146ba2ddee11c929ee233adf61997cf1cbca456cbfa73a58b0928c96a0` |

## RD测试

- MuMu instance name: `RD测试`
- dynamic ADB serial: `127.0.0.1:16416` (resolved from MuMu instance mapping + `adb devices -l`; not a script constant)
- API: 32
- ABI: `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`
- boot_id: `2cb21625-099a-42d9-aa93-f6ddc52793f7`
- android_id: `398eea33120cd887`
- model: `22041211A`
- first-version smoke: existing DebugCommand `import-prepare` for `com.warden.controlledsandbox.fixture`
- smoke result: PASS
- evidence: `artifacts/capability-audit/T57-R03-01/20260817T062518Z/` (gitignored)

The existing full transport probe `t57_rd_framework_transport_probe.ps1` still failed at `launch-component` with `LAUNCH_GATE_FAILED` / guest Activity create-resume-window not confirmed. That is recorded as `KI-R03-030`. It was not treated as a reason to patch runtime in this infrastructure campaign.

RD测试 PASS only updates `rd_api32_status` in the capability ledger. It is not ANDROID_MATRIX, OEM, commercial-app, or VA Pro equivalent.

## Targeted verification

- YAML/JSON parse: PASS
- capability fields complete: PASS
- known-issue references legal: PASS
- VA Pro corpus ids unique: PASS
- evidence schema: PASS
- collect-all `--dry-run` and `--all`: PASS (tooling)
- RD runner `--help` / `--dry-run`: PASS
- `python -m compileall tools/capability`: PASS
- `python tools/capability/test_campaign_infra.py`: PASS
- `git diff --check`: PASS

## Document fact convergence

Historical wording was marked Historical / Superseded, not deleted:

- `docs/ARCHITECTURE.md` component-bridge section
- `docs/runtime/T57_R02_FRAMEWORK_OWNERSHIP.md`
- `docs/runtime/T57_R02_RD_TEST_ACCEPTANCE.md` IIntentSender clause
- `docs/runtime/T57_R02_ARCHITECTURE_HANDOFF.md` historical session serial

Current T57 status is framework-owned Activity/Service normal path, brokered PendingIntent, `<queries>`, 64+16 slots, generation/revision clear-delete-reinstall.

## Maturity

- maturity: RD_BASELINE
- VA Pro equivalent: NOT_PROVEN

## Next recommended campaign

T57-R03-P0A Native Boundary Architecture & Adversarial Fixture
