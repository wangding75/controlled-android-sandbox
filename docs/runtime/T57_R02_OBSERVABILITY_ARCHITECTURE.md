# T57-R02 Observability Architecture

`RuntimeEventLog` is the single event sink used by Guest, broker and component runtime paths. `GuestRuntimeTrace` is the typed entry point for new events. Records are JSONL with schema version 3 and preserve the human-readable log line.

Every normalized event carries:

- `traceDomain`: `FRAMEWORK`, `BROKER`, `GUEST`, `BINDER`, `PROCESS`, `NATIVE`, `CRASH`, or `ANR`;
- `launchId` and `binderToken` when the producer has them;
- `package`, `virtualUserId`, `session`, `generation`, `processName`, and `slot`;
- `physicalPid` and `threadTid`;
- `component`, task/activity/window identifiers, status, error type and message when present.

The domains are intentionally orthogonal to the implementation class. A failure must be joinable across process and lifecycle boundaries by package + virtual user + session + generation, with launch/binder/task/window fields added for the relevant path. Native crash, Java crash and ANR files remain separate artifacts but share PID, role and timestamp fields.

Evidence collection is bounded and rotated by `RuntimeDiagnostics`; no event is treated as proof of Framework ownership without a corresponding framework-side field.
