# T57-R02 Real-Path Test Matrix

| Case | Required real path | Current evidence/status |
|---|---|---|
| RD-01 | ATMS → Stub transport → Guest Activity | `PASS` bounded API32 launch/create/resume evidence |
| RD-02 | Manifest task/launch semantics | `PARTIAL_PASS`; static contract plus device launch evidence, dedicated task-field fixture still pending |
| RD-03 | system-server `IIntentSender` Binder | `DEVICE_REGRESSION_PENDING`; dedicated real PendingIntent fixture not run |
| RD-04 | framework ServiceManager/AMS service path | `PASS` bounded start/stop path; death/unbind/rebind stress still pending |
| RD-05 | ContentResolver → broker → provider | `PARTIAL_PASS`; provider path passed, dedicated `applyBatch` result-count fixture still pending |
| RD-06 | running Guest → clearData | `PASS` direct API32 `clear → launch` replay |
| RD-07 | running Guest → delete instance | `PASS` direct API32 `launch → delete → launch` replay |
| RD-08 | concurrent Guest processes | `PASS` bounded diagnostic user0/user1 slot evidence on slots 6/7 |

The bounded diagnostic evidence is under
`artifacts/m5-device-lab-rd-diagnostic-slot-check/`. It is not a substitute for
the formal 1200-second stability gate. API 33–36 are explicitly
`DEVICE_REGRESSION_PENDING`; they were not tested in this task.
