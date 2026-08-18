# T57-R03-P4-FIX01-B — Fault / ANR / Death evidence

RESULT: `CLASSIFIER_AND_KILL_GROUNDED`

Evidence: `artifacts/capability-audit/p2a/20260818T032704Z`

## What changed

`tools/capability/run_p2a_rd.py` no longer treats probe
`PASS` / `FAIL` / `ERROR` as proof that a fault happened.

Each row emits a `faultEvidence` record with:

- target package / user
- virtual session id
- generation
- process slot
- platform PID (`:guestN`, from Guest `pid` projected as `platformPid`)
- fault mode
- inject timestamp
- OS exit evidence
- log token (system ANR vs fixture-only)
- binder-death token
- old PID dead
- new PID / session / generation
- stale session change
- final recovery

Prepare now copies the Guest process `pid` into
`RuntimeKeys.PLATFORM_PID`. The debug JSON includes that field.

Kill targets `kill -9 <guest-stub-pid>`, never
`am force-stop com.warden.controlledsandbox.fixture`.

## RD matrix (API32 `RD测试`)

| Mode | PID | oldPidDead | Classification |
| --- | ---: | --- | --- |
| java | 21448 | yes | `FAULT_INDUCED` (FATAL EXCEPTION + new session) |
| main | 22382 | yes | `FAULT_INDUCED` |
| service | 23752 | yes | `FAULT_INDUCED` |
| native-segv | 24920 | yes | `PROCESS_DEATH_WITHOUT_OS_FATAL` |
| native-abort | 26257 | yes | `PROCESS_DEATH_WITHOUT_OS_FATAL` |
| isolated-native-segv | 27736 | yes | `KILL_FALLBACK` (fixture native unavailable) |
| anr-activity | 28850 | yes | `ANR_NOT_PROVEN` (fixture `ANR_*` only) |
| anr-service | 29982 | yes | `ANR_NOT_PROVEN` |
| anr-provider | 30876 | yes | `ANR_NOT_PROVEN` |
| kill | 32270 / live retry | yes | `KILL_RECOVERED` (stub PID, broker survived, new session) |

Generation stayed `1` across recover while **session id changed**.
Session fencing is the recovery proof; generation value reuse is
allowed by the taskbook when session changes.

Isolated native is classified `KILL_FALLBACK`, not native SIGSEGV PASS.

ANR is not claimed. 25s fixture stalls still complete; there is no
ActivityManager / ApplicationExitInfo ANR reason for the stub PID.

## Kill

The kill command is `kill -9 <platformPid>` of
`com.warden.controlledsandbox.debug:guest{slot}`.

The physical fixture package is not force-stopped.

A follow-up live-kill during `hold-prepare` did not observe `:guestN`
in `ps` at t+6s (prepare result PID is already gone after the debug
Activity teardown). Broker PID survived. Recover created a new session
and a new stub PID. Remainder: in-hold live SIGKILL still races the
debug command lifecycle.

Reboot skipped (`--skip-reboot`) to keep ADB stable.

## Forbidden paths not taken

- Probe status is not an induced-fault signal
- Fixture `ANR_*` logs are not ANR
- Isolated JNI failure is not SIGSEGV PASS
- `am force-stop` of the physical guest package is not the death proof
