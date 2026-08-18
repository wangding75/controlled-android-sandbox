# T57-R03-P4-FIX01-00 — Independent Review Finding Reproduction

SOURCE OF TRUTH:
`T57-R03-P4-FIX01_Core_Parity_Corrections_Taskbook.md`
(derived from `T57-R03_P4_Independent_Source_Review_R01.md`; the R01
file is not present in this workspace).

START_HEAD: `576c0b4e3dbe091f94f0e5e4adea3d6b8f87ed93`
START_TREE: `82fe140ac1306d3ab8a6d4d79f69101ce0183c1f`

This task is read-only. Production source was not modified.

RESULT: `FINDINGS_REPRODUCED`

Independent Review R01 was not checked into the repository. The
reproductions below are taken from current HEAD source, P2/P3 reports,
and raw evidence already on disk.

---

## 1. Manifest cardinality

File: `sandbox-runtime/src/main/AndroidManifest.xml`

| Tag | Count | Breakdown |
| --- | ---: | --- |
| `<activity>` | 4159 | 64 slot-base `StubActivity0..63` + 4032 `StubActivitySlotVariants{g}$S{s}V{1..63}` + 63 `StubActivityVariants$V1..V63` |
| `<activity-alias>` | 4032 | `StubActivityAlias{slot}V{n}` targeting `StubActivityVariants$Vn` |
| `<service>` | 146 | 2 broker + 80 `GuestProcessService` (64 ordinary + 16 isolated) + 64 `StubService` |
| `<receiver>` | 64 | one per ordinary slot |
| `<provider>` | 1 | `RuntimeInitProvider` |

File size: 2,273,271 bytes / 8,407 lines.

This matches the independent-review statement `Activity > 4000` and
`aliases > 4000`.

P3 already recorded the API35 install failure:

```
INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION:
The number of child activity-alias elements exceeded the max allowed in application
```

See `docs/review/T57_R03_P3_API_MATRIX_RESULT.md`.

---

## 2. RuntimeStubComponents — guest index × process slot

`sandbox-runtime/src/main/java/.../RuntimeStubComponents.java`

Physical host Activity identity is:

```
process slot × guest Activity declaration index
```

- variant `0` → `StubActivity{slot}`
- variant `1..63` → `StubActivitySlotVariants{slot/8}$S{slot}V{variant}`
- variant `> 63` → `IllegalArgumentException("Guest Activity alias pool exhausted")`
- unknown guest class → `GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE`

`activityVariant()` walks `VirtualPackageStateSnapshot.components()` in
declaration order and returns the ACTIVITY index. That index is the
physical class identity.

The hash-based overload (`hashCode % 4` → `StubActivityAlias{slot}V{n}`)
is still present and is the older collision-prone path. Production
launch uses the package-state overload
(`RuntimeActivityLaunchCoordinator` line 85).

`RuntimeStubComponentsSelfTest` encodes the coupling: the second
declared Activity **must** resolve to `StubActivitySlotVariants0$S3V1`.
A later fixture Activity is expected to consume a new Host physical
class.

`ActivityTaskLedger` already computes virtual launchMode / CLEAR_TOP /
SINGLE_TOP / document / result decisions independently of the physical
class. The runtime then re-applies those decisions as Android flags
(`SINGLE_TOP`, `CLEAR_TOP`) against the unique physical component. The
unique class exists only so Android's task matcher can tell two Guest
Activities in the same slot apart. That is the architecture defect.

64 ordinary slots remain the **process-slot** contract, not a Guest
Activity count contract. Isolated process slots remain 16.

---

## 3. P2A runner classifier and kill

File: `tools/capability/run_p2a_rd.py`

### ANR

```python
if mode.startswith("anr"):
    if "ANR" in logcat or "not responding" in logcat.lower() or probe.get("status") in {
        "ERROR", "FAIL", "PASS",
    }:
        return "ANR_INDUCED" if recovered else "ANR_INDUCED_RECOVERY_FAIL"
```

Any probe status `PASS`/`FAIL`/`ERROR` is accepted as ANR. Fixture
`"ANR_*"` logs are therefore sufficient.

P2A report remainder: "system ANR-kill evidence is PARTIAL"; 25s
stalls completed and returned.

### Crash

```python
if any(token in logcat or token in detail for token in crash_tokens) or probe.get("status") in {
    "FAIL", "ERROR", "PASS",
}:
    return "FAULT_INDUCED" if recovered else "FAULT_INDUCED_RECOVERY_FAIL"
```

Same status-as-induced shortcut.

Isolated native SIGSEGV remainder (P2A report): isolated worker cannot
`dlopen` the fixture native library, so the path used process death
after JNI failure, not a proven `ISOLATED_HOSTILE` fatal-signal.

### Kill

```python
killed = run_adb(serial, ["shell", "am", "force-stop", GUEST_PACKAGE], check=False)
```

`GUEST_PACKAGE` is the physical fixture package
(`com.warden.controlledsandbox.fixture`), not the CAS guest stub PID /
`:guestN` process.

Evidence `artifacts/capability-audit/p2a/20260817T115104Z/kill.json`:
classification `KILL_RECOVERED` because recover `prepare` returned
`PASS`. No guest stub PID, no ApplicationExitInfo, no binder-death
token, no old/new session fencing evidence.

---

## 4. P2E repetition / soak evidence

`docs/review/T57_R03_P2E_LONG_RUN_STABILITY_RESULT.md`

- 100-iteration Activity/Service/Provider/PI/Alarm/Notification/Job
  matrix was **batched** into prepare + component-suite + launch/stop,
  not 100 isolated fixture calls.
- Clear+prepare smoke: **1 cycle**.
- Soak: 30 minutes, 35 cycles, 0 launch failures.
- FD / thread / Binder-recipient host dumps: **not collected**.
- Hostile capability issue/revoke ×100: **not executed**.

Evidence root: `artifacts/capability-audit/p2e/20260817T122841Z`.

---

## 5. Package lifecycle reset / rollback / all-user

`app/src/main/java/com/warden/controlledsandbox/SandboxPackageLifecycle.java`

### resetIdentity

`PackageLifecycleTransaction.resetIdentity` increments
`identityGeneration` and `dataRevision` and does not delete the APK or
instance row. `SandboxPackageLifecycle.resetIdentity` writes that
transaction and returns. It does **not** propagate to:

- durable PendingIntent creator/token
- Provider grants
- SystemService identity-bound records
- Native/Broker capability
- runtime session
- external-facing virtual identifiers

This is a metadata counter, not an identity reset of runtime
authorities.

### rollback

Rollback restores the previous `SandboxRecord` via
`withRestoredRevision` and requires the previous APK file to exist.
P2B RD recorded `ROLLED_BACK` then prepare PASS.

P2B remainder: no second signed APK lineage; replace used the same
fixture; split/native/resource change was not a real v1/v2 pair.

### all-user / generation authority

`resetIdentity` and the debug command take a package name only. There
is no catalog-wide virtual-user walk at the identity-reset boundary.
Clone exists (`createClone`) and instances are per `virtualUserId`,
but destructive/revision switch is not proven across user0 + clone
with a real two-version lineage.

---

## 6. VirtualSystemServiceInterceptor inventory

File:
`sandbox-framework/src/main/java/.../VirtualSystemServiceInterceptor.java`

`before()` dispatch:

| Service key | Handler | Default |
| --- | --- | --- |
| `clipboard` | `clipboard()` | — |
| `account` | `account()` | — |
| `alarm` | `alarm()` | — |
| `notification` | `notification()` | — |
| `jobscheduler` | `jobs()` | — |
| **any other name** | — | `Call.passThrough()` |

Settings, UsageStats, Shortcut and AppWidget are **not** in the
switch. They fall through to host pass-through with no rewrite.

### Generic query fallback (the independent-review defect)

```java
if (isQueryName(name)) return Call.handled(defaultValue(method.getReturnType()));
```

`isQueryName` matches `get` / `query` / `is` / `has` / `are` / `can`.
`defaultValue` returns `null` / `false` / `0` for the Java return
type. Present on **notification** and **jobscheduler**.

### Method surface (HEAD)

Classification uses the FIX01-E vocabulary. This is a reproduction
inventory, not a FIX01-E verdict.

#### clipboard — `VIRTUAL_STATE_IMPLEMENTED` (closed surface)

| Method prefix | Disposition |
| --- | --- |
| `setPrimaryClip` | VIRTUAL_STATE_IMPLEMENTED (`defaultValue` only because void) |
| `getPrimaryClip` / `Description` / `Source` | VIRTUAL_STATE_IMPLEMENTED |
| `hasPrimaryClip` / `hasClipboardText` | VIRTUAL_STATE_IMPLEMENTED |
| `clearPrimaryClip` | VIRTUAL_STATE_IMPLEMENTED |
| `add/removePrimaryClipChangedListener` | VIRTUAL_STATE_IMPLEMENTED (listener bookkeeping) |
| other | EXPLICIT_UNSUPPORTED (`VIRTUAL_CLIPBOARD_SIGNATURE_UNSUPPORTED`) |

#### account

| Method prefix | Disposition |
| --- | --- |
| `getAccounts` / `getAccountsByType` | VIRTUAL_STATE_IMPLEMENTED (typed array) |
| `addAccountExplicitly` / `WithVisibility` | VIRTUAL_STATE_IMPLEMENTED |
| `removeAccountExplicitly` / `removeAccountAsUser` | VIRTUAL_STATE_IMPLEMENTED |
| `set/clear/getPassword` | VIRTUAL_STATE_IMPLEMENTED |
| `set/peek/invalidateAuthToken` | VIRTUAL_STATE_IMPLEMENTED |
| `accountAuthenticated` | VIRTUAL_STATE_IMPLEMENTED |
| `get/setAccountVisibility` | **PARTIAL** — always `true` / `1`, not visibility state |
| `register/unregisterAccountListener` | **PARTIAL** — `defaultValue`, no listener delivery |
| other | EXPLICIT_UNSUPPORTED |

#### alarm

| Method prefix | Disposition |
| --- | --- |
| `set*` / `schedule*` | VIRTUAL_STATE_IMPLEMENTED |
| `remove*` / `cancel*` | VIRTUAL_STATE_IMPLEMENTED |
| `canScheduleExactAlarms` | VIRTUAL_STATE_IMPLEMENTED (permission) |
| `getNextAlarmClock` | VIRTUAL_STATE_IMPLEMENTED |
| name contains `time` / `timezone` | EXPLICIT_DENIED |
| other | EXPLICIT_UNSUPPORTED |

#### notification

| Method prefix | Disposition |
| --- | --- |
| `notify` / `enqueue` | HOST_DELEGATED_REWRITTEN + virtual record |
| `cancel` / `cancelAll` | HOST_DELEGATED_REWRITTEN |
| channel/group create/delete/query | HOST_DELEGATED_REWRITTEN |
| `getActiveNotifications` / `getAppActiveNotifications` | HOST_DELEGATED_REWRITTEN |
| `areNotificationsEnabled` | **PARTIAL** — hard-coded `Boolean.TRUE` |
| any other `isQueryName` | **generic `defaultValue` fake-compatible** |
| other | EXPLICIT_UNSUPPORTED |

#### jobscheduler

| Method prefix | Disposition |
| --- | --- |
| `schedule` / `enqueue` | HOST_DELEGATED_REWRITTEN |
| `cancel` / `cancelAll` | HOST_DELEGATED_REWRITTEN |
| `getPendingJob` / `getAllPendingJobs` | HOST_DELEGATED_REWRITTEN |
| any other `isQueryName` | **generic `defaultValue` fake-compatible** |
| other | EXPLICIT_UNSUPPORTED |

#### Settings / UsageStats / Shortcut / AppWidget

`NOT_APPLICABLE` as an intercepted virtual surface at HEAD:
`default -> Call.passThrough()`. Host state and host identity leak.

---

## 7. Frozen architecture still present (must not be rolled back)

Confirmed still in place at START_HEAD:

- Framework-owned Activity/Service/Receiver/Provider lifecycle
- `GuestLoadedPackageRuntime` / `GuestDefiningLoader`
- Virtual Package Universe / visibility fail-closed
- ordinary process slots = 64
- isolated process slots = 16
- Broker-owned durable `IIntentSender`
- `TRUSTED_COMPAT` / `ISOLATED_HOSTILE`
- hostile-only seccomp
- generation / revision / session fencing
- Provider lifetime authority
- SystemService state authority (for the five intercepted services)
- transactional package lifecycle direction (`PackageLifecycleTransaction`)
- `ActivityTaskLedger` + `ActivityLaunchCoordinator` + one-time routes

FIX01 must extend these, not replace them.

---

## 8. FIX01 work items implied by this freeze

| ID | Defect confirmed | Production change in 00 |
| --- | --- | --- |
| A | 4159 Activity + 4032 aliases; guest index × slot; alias-pool cap 63 | none |
| B | P2A classifier accepts PASS/FAIL/ERROR; kill is `am force-stop` guest package | none |
| C | resetIdentity is a counter; no real v1/v2 lineage; not all-user authority | none |
| D | depends on B's real guest PID/session identification | none |
| E | Settings/UsageStats/Shortcut/AppWidget pass-through; notification/job generic query `defaultValue` | none |
| F | repeat/clear/soak evidence is batched / n=1 / no FD-thread dumps | none |
| G–I | not reproduced here; deferred to those tasks | none |

NEXT: `FIX01-A`
