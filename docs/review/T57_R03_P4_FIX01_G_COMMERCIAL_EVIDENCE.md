# T57-R03-P4-FIX01-G — Commercial evidence

RESULT: `LAUNCH_SMOKE_ONLY`

Evidence: `artifacts/capability-audit/fix01g/20260818T050920Z`

No personal accounts. Login-gated surfaces untested. XH was not
installed.

| App | package | cold import-launch | later launch | label |
| --- | --- | --- | --- | --- |
| Quark | com.quark.browser | FAIL `LAUNCH_GATE_FAILED` | PASS after prepare | `LAUNCH_SMOKE_FAIL` / `GENERAL_RUNTIME_DEFECT` |
| DingTalk | com.alibaba.android.rimet | PASS | one warm FAIL, fg/relaunch PASS | `LAUNCH_SMOKE_PASS` / `GENERAL_SYSTEM_VIRTUALIZATION` |
| DragonRead | com.dragon.read | PASS | PASS | `LAUNCH_SMOKE_PASS` / `GENERAL_SYSTEM_VIRTUALIZATION` |

Each run also: stop (bg), relaunch (fg), guest-pid kill attempt, recover
prepare, 3-minute observation soak.

Guest stub PID kill often did not resolve a spoofed commercial process
name (`killed: false`). That is recorded, not treated as death proof.

These are not broad compatibility PASS.
