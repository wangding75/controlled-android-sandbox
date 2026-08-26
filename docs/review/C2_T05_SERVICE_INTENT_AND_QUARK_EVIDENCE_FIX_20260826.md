# C2-T05 service transport and Quark evidence repair — 2026-08-26

## Scope

This receipt records the fail-closed repair sequence for the local `RD测试` API32
user0 lane. The user1 lane remains assigned to the other machine.

## First-failure diagnosis

The first C2-T05 attempt stopped before a phase completed because the interaction
probe surfaced Android's stale input-client exception from `hideSoftInput`. The
existing recovery matcher only covered `startInputOrWindowGainedFocus`, although
both methods use the same exact `IllegalArgumentException: unknown client` contract.

After that repair, the interaction phase passed but the first FGS launch exposed
two independent defects: `FixtureService` dereferenced a missing session extra, and
the framework service bridge modified the defensive copy returned by the API32
source-gate `Intent.getExtras()` without reattaching it. The latter dropped the
wire-encoded Intent extras before the AMS service-argument parcel was created.

The next diagnostic run proved the transport boundary: the component request and
broker route contained a 440-byte wire payload, while the Host Intent contained no
payload. Reattaching the modified Bundle with `putExtras()` repaired the boundary.
The successful run then recorded `C2_T05_FGS_PROMOTED ... type=1` and completed the
full loop and death-recovery phases.

The Quark check then stopped on the literal `GUEST_ACTIVITY_CREATE` marker. A cold
launch after stopping both Quark and Host reproduced the same absence, while the
authoritative launch result reported `activityCreated`, `activityResumed`,
`windowEvidence`, and `firstFrameDrawn` all true, with a complete lifecycle timeline;
`dumpsys activity activities` showed the CAS Guest stub `RESUMED` with Quark windows
and `reportedDrawn=true`. The runner now accepts that structured evidence together
with the drawn-window gate, while still failing closed if neither a runtime activity
marker nor complete structured evidence is present.

## Final local C2-T05 result

Command:

```text
python tools/capability/run_c2_t05_rd.py --instance RD测试 --loops 10
```

Result: `PASS` on serial `127.0.0.1:16416`.

- Full phase: all required interaction, scheduling, FGS, and loop markers 10/10.
- FGS promoted type: 1 for all 10 loops.
- Death phase: old Guest process killed, replacement callback observed, cleanup and
  notification/alarm residue checks passed.
- Quark: cold `import-launch` passed; structured lifecycle evidence and drawn-window
  evidence passed.
- Raw evidence: `artifacts/capability-audit/catch-up-c2-t05/20260826T005627Z/`.
- Compact receipt: `verification/catch-up/C2-T05/c2-t05-rd-summary.json`.

The earlier failed artifacts remain under their original timestamped directories and
are not relabeled as PASS.
