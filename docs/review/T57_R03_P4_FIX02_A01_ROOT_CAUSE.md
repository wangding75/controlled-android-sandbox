# T57-R03-P4-FIX02-A01 Android 15/16 Activity Runtime Compatibility Root Cause Report

- **Taskbook / Campaign**: `T57-R03-P4-FIX02-A01`
- **Scope**: Android 15 (API 35) / Android 16 (API 36) CAS Guest Activity Runtime Compatibility
- **Status**: `RESOLVED / ACCEPTED_AND_CLOSED`
- **Primary Source File**: [`sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityFieldBridge.java`](file:///D:/github/controlled-android-sandbox/sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityFieldBridge.java)
- **Unit Test Harness**: [`sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityFieldBridgeSelfTest.java`](file:///D:/github/controlled-android-sandbox/sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityFieldBridgeSelfTest.java)

---

## 1. Problem Description & Dynamic Failure Mode

In Android 15 (API 35) and Android 16 (API 36), launching any Guest Activity in CAS resulted in an immediate failure during the pre-launch transaction projection stage.

### Dynamic Failure Signature
On API 35/36, `import-launch` and `launch-component` aborted before Guest `Activity.onCreate()` could be reached. The framework logs indicated that `ActivityThread` failed to initialize pre-launch records, or `ActivityFieldBridge` threw `ACTIVITY_CLIENT_RECORD_LAUNCHING_NOT_FOUND` / `FIELD_UNAVAILABLE`.

---

## 2. Framework Root Cause Analysis

### 2.1 AOSP Framework Evolution (Android 9–14 vs. Android 15–16)
1. **Android 9 (API 28) through Android 14 (API 34)**:
   - `android.app.ActivityThread` maintained an internal `ArrayMap<IBinder, ActivityClientRecord> mLaunchingActivities`.
   - When a launch transaction was scheduled, `ActivityThread` inserted a placeholder `ActivityClientRecord` into `mLaunchingActivities` indexed by the binder activity token before invoking `handleLaunchActivity()`.
   - CAS's `ActivityFieldBridge.projectLaunchingRecord()` relied on reflectively looking up `mLaunchingActivities` to overwrite the host `ActivityInfo` and `Intent` with the Guest's virtual package metadata.

2. **Android 15 (API 35) & Android 16 (API 36)**:
   - AOSP refactored `ActivityThread` and client transaction execution pipelines (window manager client refactoring).
   - The field `mLaunchingActivities` and method `getLaunchingActivity(IBinder)` were removed from `ActivityThread`.
   - Instead, the client transaction item (`LaunchActivityItem`) delivers the `ActivityClientRecord` directly to `performLaunchActivity(ActivityClientRecord, Intent)`.
   - Reflective lookups for `mLaunchingActivities` return `null`.

### 2.2 CAS Vulnerability
The legacy `ActivityFieldBridge.projectLaunchingRecord()` assumed that the absence of a record in `mLaunchingActivities` meant the launch transaction had failed or was unregistered. On Android 15+, this caused `projectLaunchingRecord()` to either fail closed with `ACTIVITY_CLIENT_RECORD_LAUNCHING_NOT_FOUND` or attempt invalid reflection access, breaking the Guest Activity lifecycle.

---

## 3. Implementation Fix

The fix in `ActivityFieldBridge.java` restores full dual-mode lifecycle compatibility across Android versions:

```java
// sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityFieldBridge.java
if (launchingField == null) {
    // Android 15/16: mLaunchingActivities does not exist on ActivityThread.
    // Pre-launch staging is handled directly via LaunchActivityItem transaction projection.
    RuntimeEventLog.event("PRELAUNCH_RECORD_SKIPPED", evidence);
    return;
}
```

### Architectural Principles of Fix:
1. **Authoritative `LaunchActivityItem` Projection on API 35+**:
   On Android 15/16, the virtual `ActivityInfo` and `Intent` are projected directly into `LaunchActivityItem` fields (`mIntent`, `mInfo`), which `ActivityThread` consumes during instantiation.
2. **Fail-Closed Legacy Platform Protection (API 28–34)**:
   On legacy Android versions where `mLaunchingActivities` exists, `ActivityFieldBridge` continues to enforce strict token presence and fail-closed security. If the token is missing from the active map, an explicit `IllegalStateException("ACTIVITY_CLIENT_RECORD_LAUNCHING_NOT_FOUND")` is thrown.
3. **No Unsafe State Corruption**:
   The fix avoids modifying unrelated runtime fields or breaking host app lifecycle callbacks.

---

## 4. Verification & Validation Evidence

### 4.1 Deterministic Unit Test Verification
`ActivityFieldBridgeSelfTest.java` was expanded with complete test coverage:
1. `testLegacyPreLaunchRecordSuccess()`: Verifies pre-launch record projection on platforms with `mLaunchingActivities`.
2. `testLegacyPreLaunchRecordMissingFailsClosed()`: Verifies fail-closed integrity when `mLaunchingActivities` exists but token is absent.
3. `testAndroid15DirectLaunchActivityItemAuthoritative()`: Verifies that on Android 15+ (no `mLaunchingActivities` / `getLaunchingActivity`), `PRELAUNCH_RECORD_SKIPPED` occurs gracefully and `LaunchActivityItem` carries the authoritative Guest contract.
- Static compile & harness run: `python tools/static_android_compile.py` $\rightarrow$ **PASS** (42 harness tests pass, exit code 0).

### 4.2 Dynamic Acceptance Matrix Verification
The fix was validated dynamically across active physical/virtual hardware targets:
- **API 32 (MuMu `RD测试` / `127.0.0.1:16416`)**:
  - Basic Activity launch: **PASS** (`LAUNCH_PASS`)
  - Scale boundary indices (0, 63, 64, 95, 127): **PASS** (`LAUNCH_PASS`)
  - `FrameworkActivityResultParentActivity` $\rightarrow$ `ChildActivity`: **PASS** (`LAUNCH_PASS`)
  - `TaskSemanticsProbeActivity` (`singleTask` + `onNewIntent`): **PASS** (`LAUNCH_PASS`)
  - Real Process Death & Clean Recovery (PID kill $\rightarrow$ relaunch $\rightarrow$ generation increment): **PASS** (`pass: true`)
- **API 35 (AVD `T57_R03_API35_x86_64` / `emulator-5554`)**:
  - Basic Activity launch: **PASS** (`LAUNCH_PASS`)
  - Scale boundary indices (0, 63, 64, 95, 127): **PASS** (`LAUNCH_PASS`)
  - ActivityResult: **PASS** (`LAUNCH_PASS`)
  - TaskSemantics: **PASS** (`LAUNCH_PASS`)
  - Real Process Death & Clean Recovery: **PASS** (`pass: true`)
  - Neighbor Smoke (Service & PendingIntent): **PASS**
- **API 36 (AVD `T57_R03_API36_x86_64` / `emulator-5556`)**:
  - Scale boundary indices (0, 63, 64, 95, 127): **PASS** (`LAUNCH_PASS`)
  - Basic Activity launch: **PASS** (`LAUNCH_PASS`)
  - Real Process Death & Clean Recovery: **PASS** (`pass: true`)

---

## 5. Conclusion

The Android 15/16 Guest Activity compatibility issue has been completely root-caused, repaired at source level, deterministically unit-tested, and dynamically verified across API 32, API 35, and API 36 with full evidence preservation.
