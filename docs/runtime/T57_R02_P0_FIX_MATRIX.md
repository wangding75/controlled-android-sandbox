# T57-R02 P0 Fix Matrix

| ID | Defect | Status | Source evidence | Device gate |
|---|---|---|---|---|
| P0-01 | Activity Framework ownership | `CONFIRMED` | `StubActivityBase` still creates and drives `GuestActivityController`; this is a host trampoline/manual lifecycle, not an ATMS-created Guest Activity record. | `NEEDS_RD_DEVICE_PROOF` |
| P0-02 | Manifest Activity contract | `RESOLVED_BY_T57` | `ManifestModel`, binary XML parser, `VirtualComponentSnapshot`, state builder and `VirtualPackageMetadata.projectActivityContract` carry task/launch/window fields. | `DEVICE_REGRESSION_PENDING` |
| P0-03 | PendingIntent real Binder/IntentSender transport | `NEEDS_RD_DEVICE_PROOF` | Sender now has a descriptor and `onTransact` path with positional send permission parsing; no live system-server cross-process trace is available. | `RD_TEST_REQUIRED` |
| P0-04 | clearData lifecycle force-stop barrier | `RESOLVED_BY_T57` | `PackageManagementSession` calls `RuntimeClient.stop` before clear/delete; cleanup warnings produce `CLEAR_PARTIAL_CLEANUP` or `DELETE_PARTIAL_CLEANUP`. | `DEVICE_REGRESSION_PENDING` |
| P0-05 | Service Framework ownership | `CONFIRMED` | Service instantiation is factory-aware, but attach/onCreate/onBind/onDestroy remain manually owned by `GuestComponentRuntime`. | `NEEDS_RD_DEVICE_PROOF` |

No row above is promoted to device `PASS` without RD evidence.
