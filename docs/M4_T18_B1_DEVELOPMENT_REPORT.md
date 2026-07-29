# M4-T18 B1 Development Report — Structure, Persistence and Contract Closure

## Baseline

- Starting point: `2b97734f6a86e947ec2e2b2bfad0b528e6658f55` (`m4-t17-source-pass`).
- Device/emulator execution: not part of this batch.

## Delivered changes

### Virtual system-service persistence split

`VirtualSystemServiceStore` no longer owns raw JSON/file persistence. The responsibilities are separated into:

- `VirtualSystemServiceStoreCodec` — schema 1–5 decode compatibility, deterministic schema encode, duplicate-key rejection and per-resource count validation.
- `VirtualSystemServiceStorePersistence` — atomic temp-file replacement, fsync, 8 MiB payload limit, 12 MiB file limit, CRC32 envelope and `.corrupt` quarantine.

The store keeps only runtime behavior and transaction orchestration. Mutation rollback now restores the affected scope and all global ID/token counters.

### Activity/Task state split

The mutable Activity/Task model was removed from `ActivityTaskLedger` and placed in dedicated package-private state classes:

- `ActivityTaskMutableTask`
- `ActivityTaskMutableActivity`
- `ActivityTaskPendingResultLink`
- `ActivityTaskMatch`
- `ActivityTaskTextPolicy`

`ActivityTaskLedger` decreased from 1,872 lines at the M4-T17 source baseline to 1,733 lines. `VirtualSystemServiceStore` decreased from 1,692 to 1,482 lines.

### Contract and God-Class gate

A new `check-m4-t18-source-closure.py` gate:

- freezes the exact 13 legacy AIDL `Bundle` business methods;
- rejects any new unapproved AIDL `Bundle` method;
- requires a rationale and typed boundary for every legacy exception;
- rejects production Java/Kotlin files above 1,800 lines and native source files above 1,600 lines;
- verifies the persistence and Activity/Task decomposition remains present.

## Verification

PASS:

- architecture and package boundaries;
- typed contract checks;
- M4-T18 source-closure checks;
- M4-T14 Service regression;
- M4-T15 Activity/Task regression;
- M4-T16 PendingIntent, Alarm, Notification and Job regression;
- M4-T17 Native/ABI regression;
- static Android compilation and all Host self-tests;
- native/JNI tests;
- strict M3 evidence gate;
- reproducible source ZIP comparison;
- shell, Python and PowerShell structural checks.

The unified verification script reached the environment execution limit after all static Android/Host self-tests passed. Remaining Native, strict evidence and reproducible-package checks were continued in their original order and all passed.

## Remaining B2 scope

- resource ownership/death-cleanup inventory;
- Guest-query Host-fallback audit;
- PID/UID/Session/Generation/APK Revision binding audit;
- persistence rollback gaps outside the system-service store;
- instance and APK Revision cleanup closure.
