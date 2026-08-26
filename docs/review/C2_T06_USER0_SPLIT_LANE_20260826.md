# C2-T06 user0 split-machine lane — 2026-08-26

The local RD lane was rerun without creating or executing the clone/user1 lane.
The runner now accepts `--user0-only`; in this mode it does not call
`lifecycle-clone`, does not set user1 permissions, does not launch user1, and writes
the separate receipt `verification/catch-up/C2-T06/c2-t06-user0-rd-summary.json`.

Command:

```text
python tools/capability/run_c2_t06_rd.py --instance RD测试 --loops 20 --user0-only
```

Result: `PASS` on `127.0.0.1:16416`.

- user0 full lane: 20 requested and 20 completed;
- network callback return evidence: 20/20;
- sensor callback return evidence: 20/20;
- permission-negative lane: 1/1;
- Guest death/force-stop cleanup: PASS;
- user0 profile hash: `a77f464d2f843e2c0b686cbd01d076ddf0cf8dbe413b1d97339b534c4907fb1a`;
- `user1_executed=false` and no clone user was created;
- raw evidence: `artifacts/capability-audit/catch-up-c2-t06/20260826T010346Z/`.

The five `C2_T06_LOOP_PASS` lines are intentional checkpoints (loops 1, 5, 10,
15, 20); the runner additionally validates the campaign completion count and all
per-loop network/sensor return markers so checkpoint frequency cannot mask missing
iterations. User1 evidence must be supplied by the other machine before the shared
two-user C4 gate can be closed.
