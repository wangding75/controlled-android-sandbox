# T57-R02 P0 Fix Matrix

| ID | Defect | Status | Source evidence | Device gate |
|---|---|---|---|---|
| P0-01 | Activity Framework ownership | `CONFIRMED` | `StubActivityBase` still creates and drives `GuestActivityController`; this is a host trampoline/manual lifecycle, not an ATMS-created Guest Activity record. | `NEEDS_RD_DEVICE_PROOF` |
| P0-02 | Manifest Activity contract | `RESOLVED_BY_T57` | `ManifestModel`, binary XML parser, `VirtualComponentSnapshot`, state builder and `VirtualPackageMetadata.projectActivityContract` carry task/launch/window fields. | `DEVICE_REGRESSION_PENDING` |
| P0-03 | PendingIntent real Binder/IntentSender transport | `NEEDS_RD_DEVICE_PROOF` | Sender now has a descriptor and `onTransact` path with positional send permission parsing; no live system-server cross-process trace is available. | `RD_TEST_REQUIRED` |
| P0-04 | clearData lifecycle force-stop barrier | `RESOLVED_BY_T57` | `PackageManagementSession` is the single destructive-operation authority; it stops the selected generation before clear/delete and reports partial cleanup explicitly. | `RD_FULL_REGRESSION_PASS` |
| P0-05 | Service Framework ownership | `CONFIRMED` | Service instantiation is factory-aware, but attach/onCreate/onBind/onDestroy remain manually owned by `GuestComponentRuntime`. | `NEEDS_RD_DEVICE_PROOF` |
| P0-06 | APK revision upgrade over a live Guest | `RESOLVED_BY_T57_SOURCE` | Every production import/install entry point invokes the revision stop barrier before catalog switch; failed stop removes the newly published revision and leaves the old catalog authoritative. | `UPGRADE_SPECIFIC_RD_PROBE_PENDING` |

No row above is promoted to device `PASS` without RD evidence.
