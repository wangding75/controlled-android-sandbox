# T54-R02 StubActivity window FATAL timeline

Scope: `Controlled Android Sandbox / 闪现2`, current target `RD测试`.

Evidence roots:

- Baseline reproduction: `D:\controlled-android-sandbox-evidence\T54-R02-M3-BASELINE-20260812-2210`
- Fixed 10-loop short gate: `D:\controlled-android-sandbox-evidence\T54-R02-M3-SHORT-20260812-231000-10x`
- Formal M3: `D:\controlled-android-sandbox-evidence\T54-R02-M3-FORMAL-20260812-commit-4d143402-run`

## Device identity

| Field | Value |
|---|---|
| MuMu instance | `RD测试`, index `1` |
| Instance id | `ginstance1400581448817661401` |
| VM / state | `MuMuPlayer-12.0-1` / `start_finished` |
| Manager ADB port / serial | `16416` / `127.0.0.1:16416` |
| Device | Redmi `22041211A`, Android `12`, API `32` |
| Android ID | `398eea33120cd887` |

The serial is connection data for this session. The resolver exact-matches `RD测试` and records
the current serial; the runtime rule is to resolve again after a restart, reconnect, or new
session. `SX测试` is historical T54-R01 evidence only.

## Baseline failure

The first complete baseline sequence was slot 7, virtual user 1, logcat time `16:06:40.077`,
Guest PID `4477`, package `com.warden.controlledsandbox.fixture`, session
`2a691724-6972-4d14-9770-8e4334ab9bdb`, generation `2`, and Stub slot `7`.

| Lifecycle point | Evidence |
|---|---|
| Guest create/resume | `GUEST_ACTIVITY_CREATE status=ACTIVITY_CREATED`, then `ACTIVITY_RESUME` |
| Cross-task pressure | Another Guest paused while command Activity/task switching ran; slot 6 stopped and slot 7 resumed |
| Host identity | `StubActivity7`; host task `105`; virtual task was the Guest task associated with slot 7 |
| Window | `DecorView@a7e7d24[StubActivity7]`; baseline snapshot reported `windows=[]` |
| Failure | `FATAL EXCEPTION: main`, `View=DecorView@a7e7d24[StubActivity7] not attached to window manager` |
| Framework call | `WindowManagerGlobal.findViewLocked → updateViewLayout → WindowManagerImpl.updateViewLayout → ActivityThread.handleResumeActivity:5025` |

The same class of failure was later reproduced on RD in a 32-bit Guest process (`PID 15893`,
`com.warden.controlledsandbox.companion32.debug:guest7`, DecorView `b5de9e7`) at
`17:46:39.923`. Its preceding lifecycle was `ACTIVITY_PAUSE → ACTIVITY_RESUME`, and the process
had no `STUB_WINDOW_DETACHED` callback for that exact root. This proves that relying only on
`onDetachedFromWindow()` is insufficient.

## Root-cause chain

1. A current Stub Activity had already resumed and its `ActivityClientRecord.window` still pointed
   to the current `Window`.
2. Between pause and the next resume, the OEM/framework cleanup removed the DecorView from
   `WindowManagerGlobal.mViews` without a usable detach callback at the failing root.
3. The Activity-side `mWindowAdded` marker and ActivityClientRecord remained logically present,
   while WMG root ownership was absent. Diagnostic events recorded `windowRootCount=0` and
   `windowRegistered=false`.
4. API 32 `ActivityThread.handleResumeActivity` then reached its final soft-input/forward-layout
   branch and called `updateViewLayout` with that missing root. `findViewLocked` threw the visible
   `IllegalArgumentException`; no application catch or suppression was involved.
5. The forward-navigation bit was an exposure factor: it selected the framework update branch, but
   it did not remove the root and is not the ownership root cause.

The exact first remover cannot be named from the baseline because the old build did not record
`removeView`, `removeViewImmediate`, WMG roots, or the framework ActivityClientRecord. The evidence
does establish the invariant violation: `ActivityClientRecord.window != null` / `mWindowAdded=true`
while the current DecorView was absent from WMG roots. The RD run added the missing root and record
fields and confirmed the same stale-root state before repair.

## Owner and record identity

The runtime owner tuple is:

`package + virtual user + session + generation + Stub slot + Activity token + virtual task`.

The diagnostic record also includes host task, framework Activity token, ActivityClientRecord
identity description, DecorView identity, window token/layout token, attached/registered flags,
WMG root count, and `mWindowAdded`. For the repaired RD sequence, representative fields were:

| Field | Repaired RD observation |
|---|---|
| Package / user | `com.warden.controlledsandbox.fixture` or `.fixture32`; command user was isolated per invocation |
| Session / generation / slot | session-specific UUID; generation `1`; slot `7` in the failing reproduction |
| Virtual task / host task | virtual task `2`; host task `209`/`210` in the later RD runs |
| Framework token | e.g. `android.os.BinderProxy@a20c1ba` |
| DecorView | e.g. `decor@9a2799d` |
| Window layout token | e.g. `BinderProxy@a20c1ba;softInput=256->0` |
| WMG roots before repair | `windowRootCount=0`, `windowRegistered=false` |
| ActivityClientRecord repair | `CS_WINDOW_RECORD: cleared=1 records=1 preserveWindow=0 pendingRemoveWindow=0` |

The native bridge looks up the actual `ActivityThread.mActivities` entry by the Activity's framework
`mToken`, verifies that the record points to this exact Activity, clears the stale `window`,
preserve/pending removal fields, and clears `Activity.mWindowAdded`. It fails closed with an
explicit `IllegalStateException` if any required framework invariant cannot be inspected or
updated; it does not swallow the framework exception.

## Fix ordering and semantics

`StubActivityBase` now performs a pre-resume stale-root check before `super.onResume()`. If the
current root is missing while `mWindowAdded` is still true, it records ownership loss, clears the
marker, and invokes the generic native ActivityClientRecord repair. The framework then owns its
normal `r.window == null → addView` path before its final update branch. Recovery is not a direct
`WindowManager.addView` workaround, does not finish the Activity, disable task switching, or
disable forward navigation.

The generation/owner fence rejects callbacks from a detached Activity, reused Stub slot, replaced
ActivityClientRecord, other virtual user, or previous forward-navigation window. The same current
DecorView remains the framework-owned object; no product package, RD/SX name, MuMu, or OEM branch
was added.

## Deterministic regression model

`StubActivityWindowOwnershipSelfTest` covers:

1. attach → remove/detach → stale callback (old behavior is reproduced and rejected);
2. Stub slot reuse from generation N to N+1;
3. ActivityClientRecord/Activity-token replacement;
4. user 0 versus user 1 ownership;
5. forward navigation allowing only the current DecorView.

Direct `javac/java` execution result: `PASS StubActivityWindowOwnershipSelfTest
oldBehavior=REPRODUCED_AND_REJECTED`.

## Gate result

The fixed 10-loop short gate completed `10/10`; all 128 command results passed, Companion32
probes were `64`, teardown passed, and target FATAL/ANR findings were empty. The formal gate
completed `1200/1200` seconds with 18/18 command results passing, simultaneous Guest slot
evidence, Companion32 probes `8`, teardown `PASS`, and target FATAL/ANR findings empty.
