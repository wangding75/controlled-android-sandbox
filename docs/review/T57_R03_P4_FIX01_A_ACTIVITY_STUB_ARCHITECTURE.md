# T57-R03-P4-FIX01-A — Bounded physical Activity stub architecture

RESULT: `ARCHITECTURE_REFACTORED`

START_HEAD: `576c0b4e3dbe091f94f0e5e4adea3d6b8f87ed93`

## Root defect

Physical Host Activity identity was:

```
process slot × Guest Activity declaration index
```

That produced 4159 `<activity>` + 4032 `<activity-alias>` entries, exhausted
at Guest index 63, and made API35 PackageParser reject the Host APK
(`child activity-alias elements exceeded the max allowed`).

64 remains the ordinary **process-slot** contract. It is not a Guest
Activity count.

## Target architecture implemented

```
guest Activity identity
  → ActivityTaskLedger virtual task / Activity record
  → one-time route token + virtual Activity token
  → bounded physical StubActivity family
  → Android Framework
  → token correlation (StubActivityHostRegistry)
  → guest Activity lifecycle
```

Physical Host Activity count is now a constant of:

| Dimension | Count | Why Android must distinguish it |
| --- | ---: | --- |
| ordinary process slot | 64 | `android:process=":guestN"` |
| window family | 2 | opaque vs translucent window is created before `onCreate` |

`64 × 2 = 128` Host Activities. **0 aliases.**

LaunchMode, taskAffinity, document mode, CLEAR_TOP, SINGLE_TOP and
ActivityResult stay in `ActivityTaskLedger`. They do not multiply Host
classes.

## Production changes

- `RuntimeStubComponents.activityComponentFor` selects
  `StubActivity{slot}` or `StubActivityTranslucent{slot}` from the Guest
  theme. Guest declaration index is not a Host class.
- Missing Guest Activity still fails closed
  (`GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE`). There is no alias-pool cap.
- `ActivityTaskLedger` records the virtual tokens removed by a launch
  (CLEAR_TOP / singleTask / reset).
- `RuntimeActivityHostDecisionApplicator` applies
  `DELIVERED_NEW_INTENT` / `CLEARED_TOP` / `REORDERED_TO_FRONT` to the
  live trampoline by token via `APPLY_ACTIVITY_HOST_DECISION`. Android
  `SINGLE_TOP` / `CLEAR_TOP` is no longer used to tell two Guest
  Activities in the same slot apart.
- If the target trampoline is not live (process death / replacement
  Activity), the coordinator starts a new bounded stub with the existing
  route token.

Deleted:

- `StubActivitySlotVariants0..7`
- `StubActivityVariants`
- every Host `activity-alias`

Added:

- `StubActivityTranslucent0..63`
- `PhysicalActivityWindowFamily`
- `StubActivityHostRegistry`
- `ControlledSandbox.Stub.Translucent`
- fixture module `fixture-activity-scale` (128 Guest Activities plus a
  result caller)
- static gate `host_activity_stub_bounds()` (limit 128 / 0)

## Manifest census

| | Before (START_HEAD) | After |
| --- | ---: | ---: |
| `<activity>` | 4159 | 128 |
| `<activity-alias>` | 4032 | 0 |
| file lines | 8407 | 345 |
| file bytes | 2,273,271 | 74,565 |

Adding 100 Guest Activities to a fixture cannot change Host component
count. The scale fixture has 128 Guest Activities and the Host Manifest
stays at 128 / 0.

## Static

`python tools/static_android_compile.py` PASS, including:

- `RuntimeStubComponentsSelfTest` — index 127 reuses `StubActivity3`;
  translucent theme selects `StubActivityTranslucent3`; missing class
  still fail-closed
- `ActivityTaskLedgerSelfTest` — CLEAR_TOP exposes removed tokens
- existing Activity launch / route / checkpoint tests

Ordinary slots remain 64. Isolated slots remain 16.

## Dynamic

### API32 / `RD测试`

Evidence: `artifacts/capability-audit/fix01a/20260818T023751Z` (slot 63 /
basic) and `20260818T024453Z` (scale 0/63/64/95) plus a follow-up
`ScaleActivity127` `LAUNCH_PASS`.

| Probe | Result |
| --- | --- |
| Host install | PASS |
| prepare basic fixture | PASS, slot 54 |
| ordinary slot 63 | PASS (`processSlot: 63`) |
| launch `fixture.MainActivity` | PASS `LAUNCH_PASS` |
| scale import-prepare | PASS |
| ScaleActivity000 / 063 / 064 / 095 / 127 | PASS `LAUNCH_PASS` |

064 is `singleTop`, 095 is `singleTask`, 127 is declaration index 127.

### API35 (`T57_R03_API35_x86_64`, `emulator-5554`)

Host APK `app-debug.apk` streamed install **Success**. This is the
PackageParser failure that blocked P3 (`activity-alias` overflow). The
bounded 128-Activity / 0-alias manifest is installable on API35.

Scale fixture APK install Success. `import-prepare` PASS. Guest
create/resume/window observation on this SwiftShader emulator was
`LAUNCH_GATE_FAILED` for ScaleActivity000; later launches timed out.
API35 **install + import** are proven. Live window observation on this
AVD is remainder for FIX01-I, not an alias-pool failure.

Frozen architecture that was not rolled back:

- Framework-owned Activity lifecycle
- `ActivityTaskLedger` / `ActivityLaunchCoordinator` / one-time routes
- GuestLoadedPackageRuntime / GuestDefiningLoader
- process-slot 64 / isolated 16
- generation / revision / session fencing

## Remainder

- Custom Guest themes that are translucent but are not a known
  framework dialog/translucent style default to the opaque family. The
  Guest theme is still projected after attach.
- Isolated processes still do not declare Activities.
- Full singleTask / CLEAR_TOP / result / death-relaunch matrix on a
  device is the RD runner, not this static report.

NEXT: `FIX01-B`
