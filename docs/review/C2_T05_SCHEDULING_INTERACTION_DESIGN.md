# C2-T05 Notification、Alarm、Job、FGS、Window/Input/IME/Display RD API32 Design

## Scope and evidence boundary

This document defines the package-neutral device campaign for `C2-T05`. It closes the
method families selected by the C2-T01 P0 catalog on the dynamically resolved MuMu
`RD测试` API32 device only. A passing run is `RD_BASELINE`/`RD_API32_L3`; it does not
claim Android Matrix, OEM, ARM, 16 KB, commercial-app, SX/XH, or VA PRO equivalence.

The campaign reuses the existing typed `VirtualSystemServiceStore` and
`VirtualInteractionProfileSnapshot`, the C1 PendingIntent identity/revision ledger, and the
process-local `GuestInteractionState`. It does not add package-specific production branches.

## DISCOVER / CLASSIFY

The pre-task collect-all audit was diagnostic-only: 34 gates PASS, 8 existing
`KNOWN_ISSUE`, 0 `NEW_REGRESSION`. The existing Notification, Alarm, Job, FGS, Window,
Input/IME and Display hooks, typed contracts, stores and host self-tests were present. The
remaining scoped finding was classified as `KI-R03-038` (`TEST_EVIDENCE_GAP`): there was no
single package-neutral RD fixture proving request/return/callback/death for this C2-T05
method set. Existing `KI-T57-010` and `KI-T57-011` remain broader historical evidence gaps;
this task closes only the API32 campaign described here.

## Method matrix

| Domain | Guest request | Required device return/callback evidence | Cleanup/death gate |
|---|---|---|---|
| Notification | channel/group, `notify(tag,id)`, active query, content/delete PendingIntent | channel and Guest tag/id are readable; content PendingIntent callback is delivered | cancel removes the Guest record; system-held token remains revision-scoped |
| Alarm | exact `PendingIntent` alarm, exact-capability query, cancel | schedule return and delayed broadcast callback with request identity | one-shot delivery and cancel leave no current alarm residue |
| Job | `JobInfo` network/charging/storage/deadline constraints, schedule, pending query | Guest job id is restored; `JobService.onStartJob` and `jobFinished` callback | cancel/finish converge; no duplicate callback or stale Host id |
| FGS | foreground service start, declared `dataSync` type, notification association | promotion marker contains a non-zero declared/runtime type | stop removes the service/notification ownership |
| Window | Activity window/token, metrics and lifecycle | non-null Guest window token plus virtual dimensions/rotation | Activity destroy closes the process-local window state |
| Input/IME | focused `EditText`, `show/hideSoftInput`, enabled IME query | request/return is recorded and Host IME catalog is not exposed | hide/finish path and Activity destroy leave no input session |
| Display | `DisplayManager` ids/info/context and `WindowManager` metrics | only the configured virtual display id and profile dimensions are observed | Activity destroy closes display callback/ownership state |

The notification click is exercised through the notification's Guest content
`PendingIntent.send()` after the notification has been posted. This proves the same
cross-process token/relay path without depending on OEM SystemUI coordinates. The C1-T05
RD receipt remains the inherited evidence for system-holder delivery after Guest death;
this campaign also arms a fresh exact alarm/notification pair, kills the Guest process, and
checks the alarm callback plus final cleanup.

## Campaign phases

1. Resolve `RD测试` by instance name, install the locked test APK set, reset/import the
   package and grant the Host-side fixture permissions required by API32.
2. Launch the fixture campaign and run one interaction probe plus the scheduling loop. Each
   loop records notification channel/post/click/cancel, exact alarm schedule/callback/cancel,
   constrained JobInfo schedule/pending/job callback/finish, and FGS type/promotion/stop.
   The three ordinary PendingIntents use stable request identities with
   `FLAG_UPDATE_CURRENT`, so repeated loops refresh the sender payload without creating an
   unbounded sequence of equivalent remote records; notification tags/channels and Job ids
   remain loop-specific for independent readback.
3. Record the Activity window token, virtual display metrics/context, IME query and
   show/hide return values, then explicitly finish and verify lifecycle cleanup markers.
4. Arm a short system-held exact alarm and notification, kill the resolved Guest process,
   observe the relay callback on the replacement process, run explicit cleanup, and inspect
   notification/alarm dumps for residue.
5. Preserve raw logcat, command results, environment snapshot, APK hashes and a structured
   receipt under `artifacts/capability-audit/catch-up-c2-t05/` and
   `verification/catch-up/C2-T05/`.

The final campaign uses a wall-clock budget of 18 seconds per requested loop because the
exact-alarm delivery and JobScheduler callback are real device operations. This budget only
changes runner liveness; required marker counts and cleanup checks remain exact.

## Acceptance and failure policy

The runner fails closed when a required marker, identity field, callback or cleanup marker is
missing, or when `FATAL EXCEPTION`, ANR, stale-session or cross-user evidence appears. A
runner defect or deterministic implementation defect is repaired and rerun. A real runtime
or external-device failure is not relabeled PASS; it is recorded as BLOCKED only when safe
in-scope repair cannot proceed without manual intervention.

The receipt records the exact RD API32 scope and keeps `va_pro_equivalent` as
`NOT_PROVEN`.
