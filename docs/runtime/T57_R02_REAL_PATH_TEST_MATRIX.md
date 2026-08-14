# T57-R02 Real-Path Test Matrix

| Case | Required real path | Expected evidence | Current status |
|---|---|---|---|
| RD-01 | ATMS → Stub transport → Guest Activity | ActivityClientRecord, task, window token, launch ID | `DEVICE_REGRESSION_PENDING` |
| RD-02 | Manifest task/launch semantics | launch mode, affinity, document mode, config/window fields | `DEVICE_REGRESSION_PENDING` |
| RD-03 | system-server `IIntentSender` Binder | descriptor, sender token, cross-process send, notification click | `DEVICE_REGRESSION_PENDING` |
| RD-04 | framework ServiceManager/AMS service path | binding record, per-intent identity, death/unbind/rebind | `DEVICE_REGRESSION_PENDING` |
| RD-05 | ContentResolver → broker → provider | real `applyBatch` operation chain and result count | `DEVICE_REGRESSION_PENDING` |
| RD-06 | running Guest → clearData | force-stop before data deletion, generation retirement | `DEVICE_REGRESSION_PENDING` |
| RD-07 | running Guest → delete instance | teardown, tombstone/record removal, no stale generation | `DEVICE_REGRESSION_PENDING` |
| RD-08 | concurrent Guest processes | slot allocation/capacity and process identity | `DEVICE_REGRESSION_PENDING` |

API33–36 are explicitly `DEVICE_REGRESSION_PENDING`; they were not tested in this task.
