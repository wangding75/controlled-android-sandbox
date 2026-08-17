# T57-R03 P1 Final Acceptance

RESULT: PASS

Maturity: `RD_BASELINE_P1`
VA Pro equivalent: `NOT_PROVEN`

## Git

- start HEAD: `96c666bbb8c7764f668fb79623fc74a8227c7c73`
- start TREE: `efec1b3d2ad99a8f33fef95bc688a07be2e97f00`
- branch: `feature/t57-r03-va-pro-capability-campaign`
- final HEAD: see `git rev-parse HEAD` after this commit

Commits after start:

- P1-00 `02b695df` fix(t57-r03): close guest component loader and P0 device evidence gaps
- P1A `b29e4af3` feat(t57-r03): complete modern manifest parsing surface
- P1B `0450db31` feat(t57-r03): expand virtual package manager surface fidelity
- P1C `5b03d7a2` feat(t57-r03): harden provider transport and grant semantics
- P1D `81484984` feat(t57-r03): harden loaded apk and classloader compatibility
- P1E `0b0ed31e` feat(t57-r03): deepen core system service virtualization
- P1F `b0a3a148` refactor(t57-r03): consolidate system service binder interception
- compile follow-up `aea4af81` fix(t57-r03): compile defining loader and system-holder fixture on Android
- P1-FINAL this commit

No amend, rebase, squash, merge main, or push.

## P0 debt closure

- KI-R03-NATIVE-010: FIXED. RD `native-adversarial` SERVICE_STARTED on
  `NativeAdversarialProbeService` (`artifacts/capability-audit/p1-00/20260817T093213Z/classloader.json`).
- Ordinary high-slot transport: PASS for slots 1, 7, 8, 31, 32, 62, 63 with
  live Guest Service create. Slot 0 probe script sent an empty processName.
  Default-process prepare still works (native-adversarial used slot 54).
- Isolated high-slot live start: UNTRUSTED_RUNTIME_PEER_UID (isolated worker
  peer policy). Isolated 17th allocate: NO_PROCESS_SLOT (exhaustion proven).
- System-holder PI restore: Activity alias pool exhausted for the new fixture
  Activity. Broker IIntentSender model unchanged. Recorded as remaining gap.

## Manifest

IMPLEMENTED=52 PARTIAL=0 MISSING=0 NOT_NEEDED=12
`docs/package/T57_R03_MANIFEST_SURFACE_MATRIX.yaml`

## PMS

Visibility + HiddenPackageResultMapper retained. Added getChangedPackages and
canPackageQuery. HOST_PACKAGE_HIDDEN not weakened.

## Provider

Cursor/FD/grant/batch/observer existing tests plus stale-generation/death stress.

## ClassLoader

GuestDefiningLoader unifies Activity/Service/Receiver/Provider. Service
host DexPathList defect closed on RD.

## SystemService

Nine first-batch services remain VIRTUAL_STATE on the shared authority/store.

## Binder

15 production Java Proxy sites retained. 0 migrations. Driver mediation still
REQUIRES_PRIVILEGE.

## Audit

`artifacts/capability-audit/all/20260817T092106Z` (pre-compile-follow-up):

- PASS=26
- KNOWN_ISSUE=14
- EXPECTED_WARNING=0
- NEW_REGRESSION=2 (`static-android-compile`, `runtime-hardening`) caused by
  fixture/Android-stub compile. Re-run `python tools/static_android_compile.py`
  after `aea4af81`: PASS (all self-tests).
- FAIL=16 (14 classified KNOWN_ISSUE + 2 compile regressions, now fixed)

Post-fix collect-all NEW_REGRESSION expected 0 for those two gates.

## Build

PASS

| Artifact | SHA-256 |
| --- | --- |
| app-debug.apk | 31837cd7d06e6e871546bafb894ad20084a438434ccd4e9d9e65f54878c529a4 |
| fixture-basic-debug.apk | 85b86e892c77b9b7f46cd273241fcedb5f7a7a08ed10528bcf97efe6a3ae2d8a |
| fixture-compat32-debug.apk | daf142411f1ef0209fcf89acd8beac233b082ed64af845f7db1423292314a289 |
| sandbox-companion32-debug.apk | d5ab64633dc6fb20e49a3d5f0445c114a0aebee5be56c7117bdc7c75a450a9a1 |

## RD

- instance=RD测试
- serial dynamically `127.0.0.1:16416`
- api=32
- abi=`x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`
- ClassLoader Service: PASS
- Ordinary high slots: PASS 1/7/8/31/32/62/63
- Isolated live start: peer-UID fail-closed; exhaustion PASS
- PI system-holder: alias-pool gap

This is `RD_BASELINE_P1` only.

## Known remaining gaps

1. Binder-driver mediation (REQUIRES_PRIVILEGE)
2. ARM runtime UNVERIFIED
3. Isolated live high-slot start blocked by UNTRUSTED_RUNTIME_PEER_UID
4. SystemUI/Alarm PI restore after guest death (alias pool / device matrix)
5. Pre-existing M10 / R03-020..029 known issues
6. API33–36 / OEM / VA Pro Equivalent still UNVERIFIED / NOT_PROVEN

NEXT: T57-R03-P2 Taskbook
