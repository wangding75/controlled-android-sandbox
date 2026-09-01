# C4-R05 Formal Timeout Process-Tree Boundary (2026-09-01)

## Decision

The retained/hot formal lane stopped at `fanqie/user-1/cold-018` while the enclosing
R05 phase reached its 14,400-second host envelope. The preserved first-failure bundle is

`verification/catch-up/C4-R05/formal-two-round-20260901-rebootstrap-v1/round-2-retained-hot-recovery/launch-matrix/attempt-001/attempts/fanqie/user-1/cold-018/`

The failure is not accepted as a CAS or Fanqie readiness failure. The original R05 runner
timed out its direct `run_c4_r03_low_memory_continuation.py` child, but Python's Windows
timeout handling did not terminate the nested R03 process. A separately started continuation
then ran `install_rd_apks()`. Device evidence shows the four expected package updates
(`com.warden.controlledsandbox.debug`, `com.warden.controlledsandbox.companion32.debug`,
`com.warden.controlledsandbox.fixture`, and `com.warden.controlledsandbox.fixture32`) and
`ActivityManager` killed the Host with `reason=USER REQUESTED, description=...due to installPackageLI`.
The old cold-stop operation consequently never produced `debug-command-result.json`.

This is an acceptance-orchestration process-boundary race. It is distinct from the earlier
host `LOW_MEMORY` event and from the Guest `FIRST_FRAME_DRAWN` contract. The first failure,
request IDs, operation IDs, logs, package/process/window/surface snapshots and APK/device
metadata remain authoritative and are not rewritten as a pass. The one successful manual
post-restart observation in `attempt-002` is retained as a separate observation but cannot
replace the failed row or complete the formal lane.

## Fix boundary

`tools/capability/run_c4_r05_rd.py::run_command` now uses `Popen.communicate()` with a bounded
timeout and, on timeout, terminates the entire child process tree (`taskkill /T /F` on Windows,
process-group termination on POSIX) before the R05 continuation selector runs. The termination
command, PID, return code and output are recorded in the phase command record. This does not
increase the phase deadline, retry an operation, add readiness sleep, or change the cold/hot
SLO. It prevents a stale nested child from overlapping the next install/rebootstrap boundary.

## Verification boundary

- `scripts/test_c4_r05_orchestrator.py` covers timeout classification and recorded process-tree
  termination using a bounded local child.
- `python -m py_compile tools/capability/run_c4_r05_rd.py scripts/test_c4_r05_orchestrator.py`
  must pass.
- A fresh clean-commit R05 evidence directory is required. The current failed lane remains
  historical evidence; it is not merged into a later PASS.
