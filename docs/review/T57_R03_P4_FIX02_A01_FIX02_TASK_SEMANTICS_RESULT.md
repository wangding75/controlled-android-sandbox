# T57-R03-P4-FIX02-A01-FIX02 Activity Task Semantics Virtualization & Acceptance Gate Result

- **Campaign**: `T57-R03-P4-FIX02-A01-FIX02`
- **Scope**: Activity Task Semantics Virtualization (`singleTask`, `singleTop`, `standard`, `CLEAR_TOP`, `REORDER_TO_FRONT`), `onNewIntent` Delivery, Physical Stub Instantiation Prevention, and Acceptance Runner Semantic Gate Fix
- **Status**: `RESOLVED / ALL_VERIFICATIONS_PASSED`

---

## 1. Root Cause Summary

### 1.1 The Virtual Task Semantics Defect
In CAS virtual app virtualization, physical `StubActivity` instances run under `standard` launchMode in the host Android manifest. When a virtual target activity had `launchMode="singleTask"` or was launched with flags (`FLAG_ACTIVITY_CLEAR_TOP`, `FLAG_ACTIVITY_REORDER_TO_FRONT`, `FLAG_ACTIVITY_SINGLE_TOP`), the host Android OS / AMS did not know about the virtual launchMode.

Previously:
1. `ActivityTaskLedger` calculated a reuse decision (`DELIVERED_NEW_INTENT`, `CLEARED_TOP`, `REORDERED_TO_FRONT`), but `GuestActivityThreadInstrumentation.execStartActivity` unconditionally called `invokeDelegateExecStartActivity(...)`.
2. This forced the host AMS to allocate and launch a **new physical StubActivity instance**, resulting in a second `onCreate()` rather than reusing the existing Activity and calling `onNewIntent()`.
3. The raw logcat captured `FRAMEWORK_PROBE_TASK_REUSE_FAIL reason=SECOND_ON_CREATE`.

### 1.2 The Acceptance Runner Semantic Gate Flaw
`run_a01_acceptance.py` previously checked only `r.get("status") == "PASS"` (which merely confirmed the debug command IPC returned `LAUNCH_PASS`), but did not parse the fixture's internal logcat markers (`FRAMEWORK_PROBE_*_PASS` vs `FRAMEWORK_PROBE_*_FAIL`).

---

## 2. Technical Solution

### 2.1 Framework Virtual Task Reuse Interception
1. **`ActivityTaskLedger.java`**:
   - Enhanced `selectTargetTask` to prioritize `SINGLE_TASK` affinity matches.
   - Enqueued `onNewIntent` delivery during `REORDER_TO_FRONT` actions.
   - Standard `CLEAR_TOP` without singleTop/singleTask correctly recreates the target instance (`CREATED_ACTIVITY`) while clearing child activities above it.
2. **`GuestActivityThreadInstrumentation.java`**:
   - In `execStartActivity`, intercepted reuse actions (`DELIVERED_NEW_INTENT`, `CLEARED_TOP`, `REORDERED_TO_FRONT`).
   - For reuse actions, finishes activities marked in `REMOVED_ACTIVITY_TOKENS` via `mainHandler.post(activity::finish)`.
   - Posts `deliverNewIntent(targetActivity, routeToken)` to `Looper.getMainLooper()`.
   - Returns `null` immediately from `execStartActivity`, preventing AMS from launching a duplicate physical `StubActivity`.

### 2.2 Probe Matrix Fixtures
Created and registered dedicated deterministic probe activities in `fixture-basic`:
- `StandardTaskProbeActivity` -> verifies two distinct instances created (`FRAMEWORK_PROBE_TASK_STANDARD_PASS`).
- `SingleTopProbeActivity` -> verifies `onNewIntent` on top instance (`FRAMEWORK_PROBE_TASK_SINGLETOP_PASS`).
- `TaskSemanticsProbeActivity` (`singleTask`) -> verifies single instance reuse and `onNewIntent` (`FRAMEWORK_PROBE_TASK_REUSE_PASS`).
- `ClearTopProbeActivity` -> verifies top child activity finished and root reused (`FRAMEWORK_PROBE_TASK_CLEAR_TOP_PASS`).
- `ReorderToFrontProbeActivity` -> verifies middle activity brought to front with `onNewIntent` (`FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_PASS`).

### 2.3 Semantic Runner Gate Upgrade
`tools/capability/run_a01_acceptance.py`:
- Added `check_logcat_marker(serial, pass_marker, fail_marker, wait_sec)` to parse logcat output strictly.
- Added 5-mode Task Matrix verification.
- Added session fencing and PID death recovery assertions.
- Added deterministic runner gate test: `tools/capability/test_a01_semantic_runner_gate.py` (5/5 tests PASS).

---

## 3. Dynamic Multi-API Verification Matrix

| Device / API | Scale Indices (0, 63, 64, 95, 127) | Activity Result Roundtrip | Task Mode Matrix (5 modes) | Session Fencing & PID Death | Neighbor Smoke | Overall |
|---|---|---|---|---|---|---|
| **API 32 (MuMu `127.0.0.1:16416`)** | PASS (5/5) | PASS (`FIXTURE_SEMANTIC_PASS`) | PASS (`standard`, `singleTop`, `singleTask`, `CLEAR_TOP`, `REORDER_TO_FRONT`) | PASS (`PID_DEATH_RELAUNCH_PASS`, `STALE_SESSION_FENCING_PASS`) | PASS (Prepare, PI) | **100% PASS** |
| **API 35 (AVD `emulator-5554`)** | PASS (5/5) | PASS (`FIXTURE_SEMANTIC_PASS`) | PASS (`standard`, `singleTop`, `singleTask`, `CLEAR_TOP`, `REORDER_TO_FRONT`) | PASS (`PID_DEATH_RELAUNCH_PASS`, `STALE_SESSION_FENCING_PASS`) | PASS (Prepare, PI) | **100% PASS** |
| **API 36 (AVD `emulator-5556`)** | PASS (5/5) | PASS (`FIXTURE_SEMANTIC_PASS`) | PASS (`standard`, `singleTop`, `singleTask`, `CLEAR_TOP`, `REORDER_TO_FRONT`) | PASS (`PID_DEATH_RELAUNCH_PASS`, `STALE_SESSION_FENCING_PASS`) | PASS (Prepare, PI) | **100% PASS** |

---

## 4. Static and Capability Audit Results

1. `test_a01_semantic_runner_gate.py`: 5/5 PASSED.
2. `static_android_compile.py`: 100% PASSED (130+ framework unit & self tests).
3. `run_local_capability_audit.py --all`:
   - Total: 42
   - PASS: 29
   - KNOWN_ISSUE: 13
   - NEW_REGRESSION: 0
4. `git diff --check`: 0 whitespace or formatting issues.
