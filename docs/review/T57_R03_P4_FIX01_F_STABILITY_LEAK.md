# T57-R03-P4-FIX01-F — Repetition / leak / soak

RESULT: `CUMULATIVE_PARTIAL`

Aggregator: `tools/capability/run_fix01f_rd.py`

## Cumulative counts (this FIX01 session)

From `artifacts/capability-audit/fix01f/20260818T045104Z` and
`20260818T050147Z` (interrupted to free RD测试 for FIX01-G):

| item | counted | target |
| --- | --- | --- |
| Activity | 4 | 100 |
| Service | 1 | 100 |
| Provider | 1 | 100 |
| PI | 2 | 100 |
| Alarm | 1 | 100 |
| Notification | 1 | 100 |
| Job | 3 | 100 |
| hostile issue/revoke | 0 | 100 |
| clear/reinstall | 0 | 20 |
| process death/recycle | 0 | 20 |

These are real runner increments, not invented totals.

## Prior soak (not discarded)

`artifacts/capability-audit/p2e/20260817T122841Z`: 30-minute soak,
35 cycles, 0 launch failures. That soak remains the long-run sample.

## Leak snapshots

`leak-before.json` was collected (process/FD/thread counts for Host
and guest-named processes). After snapshots were not written because
the 100-cycle run was still in progress when RD测试 was needed for
commercial deepening.

No claim of "no leak" is made without the after snapshot.

## Remainder

100-iteration targets are not met. The aggregator exists and can be
resumed. Hostile/clear/death loops were not reached in the short run.
