# T57-R02 RD Test Acceptance

## Gate

The only dynamic gate is the MuMu simulator instance named `RD测试`, API 32. The
instance name is resolved from MuMu configuration and manager metadata, then the
current ADB endpoint is verified with `get-state`, model/device/API/release, and a
boot ID. The T57 PowerShell resolver additionally links the ADB TCP owner to the
MuMu VM index and desktop window title. No ADB serial is embedded in the scripts.

Scripts:

- `tools/device/t57_rd_activity_real_path.ps1`
- `tools/device/t57_rd_pending_intent_real_path.ps1`
- `tools/device/t57_rd_service_real_path.ps1`
- `tools/device/t57_rd_provider_real_path.ps1`
- `tools/device/t57_rd_clear_lifecycle.ps1`
- `tools/device/t57_rd_framework_transport_probe.ps1`
- `tools/device/t57_rd_full_regression.ps1`

## Build and dynamic resolution evidence

- `:app:assembleDebug` and `:fixture-basic:assembleDebug`: PASS.
- `:fixture-compat32:assembleDebug` and `:sandbox-companion32:assembleDebug`: PASS.
- Locked four-APK device-lab artifact verification: PASS.
- Runtime target: `RD测试`, API 32 / Android 12, model `22041211A`, device `rubens`.
- The resolved serial, boot ID, manager record, APK hashes, and Git HEAD are stored
  in the local diagnostic evidence under
  `artifacts/m5-device-lab-rd-diagnostic-slot-check/`; the serial is session data,
  not a script constant.

## Current run

The bounded API32 diagnostic run is `DIAGNOSTIC_PASS`:

- 18 command results passed across fixture64 and fixture32, users 0 and 1.
- Activity launch/resume, Service start/stop, Provider preparation, and teardown passed.
- Companion32 probes passed with `bitness=32; abi=x86`.
- Simultaneous Guest evidence passed with user 0/user 1 on slots 6/7.
- A 30-second stability window passed with no target fatal/ANR finding.
- Direct lifecycle replay passed: `clear → launch`, `launch → delete → launch`.

The overall RD acceptance remains:

`RESULT: BLOCKED`

This is intentional. The real PendingIntent `IIntentSender` path has not yet been
exercised by a dedicated device fixture; the run is diagnostic rather than the
required 1200-second formal stability gate; and API 33–36 were not tested. Those
items remain `DEVICE_REGRESSION_PENDING`.

## T57 execution update

The first real Framework transport probe now passes on the resolved RD API32
device:

- `ContentResolver.applyBatch()` enters one `IContentProvider` batch transport,
  executes the Guest provider batch, and returns two typed results.
- `PendingIntent.getActivity()` creates a mutable virtual sender; `send()` reaches
  the host `IIntentSender` route and launches the Guest `DetailActivity`.
- `bindService()` returns a Guest `ServiceConnection` callback with a live Binder.
- The API32 virtual PMS carries installed Guest package projections. With
  `fixture32` imported as a peer, Guest `PackageManager` passed
  `getApplicationInfo`, `getPackageInfo`, launcher intent resolution, and
  `resolveContentProvider()` under the declaring package's package/provider/intent
  `<queries>` rules (`FRAMEWORK_PROBE_PACKAGE_UNIVERSE_PASS`).
- `RemoteActivity` and `RemoteFixtureService` are resolved to the declared
  `com.warden.controlledsandbox.fixture:remote` process and emit process-scoped
  Guest lifecycle evidence; the remote Service then emitted `SERVICE_DESTROY` after
  `stopService()`.
- The Guest-side probe emits `FRAMEWORK_PROBE_PASS`; the command result remains
  `LAUNCH_PASS` and the wrapper emits
  `RESULT: PASS case=RD-06-framework-transport-probe`.

Evidence is captured under `build/t57-rd-evidence/` with the dynamically resolved
serial and boot ID. This closes the first transport and multi-package PMS batch;
clear/delete/reinstall recovery, broader process-death/recovery, isolated-slot
pressure, and the formal stability window remain open before the API32 baseline
can be promoted to an overall `PASS`. API 33–36 and OEM variants remain
`DEVICE_REGRESSION_PENDING` by scope.

## Acceptance record required for a final PASS

Record `instanceName`, dynamically resolved serial, model, device, API, Android
release, boot ID, APK SHA-256, sandbox package/version, Git HEAD/tree, case result,
diagnostics JSONL, Java/native crash artifacts, and ANR traces. A case is accepted
only when framework-side and Guest-side evidence share the correlation fields in
`T57_R02_OBSERVABILITY_ARCHITECTURE.md`.
