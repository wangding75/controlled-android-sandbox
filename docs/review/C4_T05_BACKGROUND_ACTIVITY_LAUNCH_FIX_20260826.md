# C4-T05 repeated Guest launch background-start fix (2026-08-26)

## Finding

The first complete C4-T05 run after the Quark low-memory recovery stopped at SX
business loop 5 with `LAUNCH_FAILED:guest Activity create/resume/window not
confirmed`. This was not a low-memory classification. The Host log contained
Android ActivityTaskManager's explicit rejection:

```text
Abort background activity starts from 10196
callingUidHasAnyVisibleWindow=false
allowBackgroundActivityStart=false
```

Loops 1-4 had `GUEST_ACTIVITY_CREATED`, `RESUMED`, and `FIRST_FRAME_DRAWN`.
The debug command Activity was declared `noHistory`, while each virtual task
was launched with Host `NEW_TASK|MULTIPLE_TASK`; after the Guest stopped, the
Host command Activity was no longer a visible task owner for the next loop.

## Fix

- Keep the debug command Activity in its physical Host task by removing only
  `android:noHistory` from the debug manifest. `excludeFromRecents` remains.
- Add the internal `hostTaskReuse` launch-policy hint to the broker envelope.
  It is removed from Guest Intent extras and grants no background-start
  privilege.
- When this debug/RD-only hint is present, retain `NEW_TASK` but do not add
  `MULTIPLE_TASK|RESET_TASK_IF_NEEDED`, allowing the visible command Activity's
  physical task to remain the Host task owner across Guest stop/start rounds.
- Apply the hint only to the C4-T05 SX business loop; ordinary launches retain
  their existing task flags and intent payload behavior.

## Verification

The following evidence was collected on dynamically resolved `RD测试`
(`127.0.0.1:16416`, API 32, model `22041211A`):

- Static Android compile/self-tests: PASS.
- Debug/fixture/companion APK build and install: PASS.
- Short SX business probe: 10/10 loops PASS, crossing the former loop-5
  failure point.
- Full C4-T05 run: SX business 100/100 PASS; DingTalk 7.8.10/1178 cold, hot,
  upgrade, post-upgrade, background, foreground and login-surface evidence
  PASS; static gates PASS.

Raw evidence is under:

`artifacts/capability-audit/catch-up-c4-t05/20260826T012154Z/`

The C4-T05 runner returned `PASS` with exit code 0. This fixes the local
repeated-launch rejection; it does not by itself clear the separate remote
user1 evidence or the C4-R05 pressure gate.
