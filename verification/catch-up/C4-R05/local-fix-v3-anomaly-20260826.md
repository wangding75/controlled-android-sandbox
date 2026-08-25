# C4-R05 local-fix-v3 DingTalk anomaly record

Date: 2026-08-26 (Asia/Shanghai)  
Device: MuMu `RD测试`, API 32 / Android 12, host serial `127.0.0.1:16416`, emulator memory 8 GB.

## Scope and result

This is a bounded local regression after the fixes made from the `9e5d898b` progress point. It is not a formal C4-R05 closeout. The runner keeps the strict readiness gates: cold <= 30,000 ms and hot <= 10,000 ms.

| lane | runner status | functional evidence | readiness | classification |
|---|---|---|---:|---|
| DingTalk user0 cold-001 | `PASS` | Activity created/resumed, Window, Surface, non-black screenshot, `FIRST_FRAME_DRAWN` | 61,090 ms | `READINESS_SLO_EXCEEDED` |
| DingTalk user0 hot-001 (manual resume attempt 2) | `PASS` | Activity created/resumed, Window, Surface, non-black screenshot, `FIRST_FRAME_DRAWN` | 24,927 ms | `READINESS_SLO_EXCEEDED` |

The two lanes therefore demonstrate that the previous launch crashes are no longer reproduced on this environment, but the strict readiness SLO is still exceeded. The runner exited non-zero by design; this must not be counted as a formal PASS or used to close C4-R05.

## Root-cause evidence

1. **Guest bootstrap / dynamic receiver dispatch (fixed in this change).** Direct host registration/unregistration avoids waiting on the Guest main-thread dispatcher during application startup. The runtime now publishes the PREPARING session before early component callbacks and installs bridges before `Application.attachBaseContext`.
2. **API 32 pre-launch record ordering (fixed in this change).** Android 12 can consume the temporary launching record before the framework projection hook runs. The bridge now logs and continues when that record is absent, while still projecting the transaction.
3. **Settings.Config outbound attribution (fixed in this change).** Android 12 Settings Provider validates the submitted `AttributionSource` against the physical Binder caller. The Config transport proxy rewrites only the outbound transport identity to the host identity and restores the virtual Guest identity afterward.
4. **Residual performance/environment anomaly (unresolved).** Both current lanes draw a real first frame but miss the readiness budgets. This is consistent with the user's observation that the commercial app is heavy and cold start is slower, and may include MuMu/host scheduling pressure. A second machine's first-frame success is comparative evidence only; matched cross-machine timing is still required before assigning the residual SLO miss solely to environment.

## On-site log evidence

Current cold log contains the complete successful lifecycle path:

```text
GUEST_PREPARED status=READY package=com.alibaba.android.rimet ...
PRELAUNCH_RECORD_NOT_FOUND_CONTINUE api=32 ...
PRELAUNCH_TRANSACTION_PROJECTED ... component=com.alibaba.android.rimet.biz.LaunchHomeActivity
GUEST_ACTIVITY_FIRST_FRAME_DRAWN ... windowAttached=true windowRegistered=true ...
```

The current cold and hot `case.json` files have `fatalMarkers=[]`; historical markers are explicitly separated by the runner. The old v2 log retains the superseded failure for audit comparison:

```text
Caused by: java.lang.SecurityException: Calling uid: 10045 doesn't match source uid: 10002
```

That UID mismatch is absent from the current v3 run. The old API 32 activity-record exception is likewise historical only; the current log contains `PRELAUNCH_RECORD_NOT_FOUND_CONTINUE` followed by `PRELAUNCH_TRANSACTION_PROJECTED`.

## Evidence files

- [cold case](./local-fix-v3-dingtalk-20260826/attempts/dingtalk/user-0/cold-001/case.json) — SHA-256 `3230FD006A93C82B6A2884C1B4D47476E9172D613E9BE563BA37F21CDA5521E5`
- [hot case](./local-fix-v3-dingtalk-hot-20260826/attempts/dingtalk/user-0/hot-001/case.json) — SHA-256 `B60C60EE1C66FBFA26EFD8A842415C62C6C4FDC3AFEB67174BCDE23618F6C4BC`
- [current cold log](./local-fix-v3-dingtalk-20260826/attempts/dingtalk/user-0/cold-001/logcat.txt) — SHA-256 `0CD902FEC54B9654ECE4F856DF3AFD8BDF186EABF0059CF2DD5B415C07C6F6E1`
- [current hot log](./local-fix-v3-dingtalk-hot-20260826/attempts/dingtalk/user-0/hot-001/logcat.txt) — SHA-256 `117BB522BD6DAC54D55C18CE9C7C36774E1FDABCF661D72E5E98712CFDA0218F`
- [superseded v2 UID-mismatch log](./local-fix-v2-dingtalk-20260826/attempts/dingtalk/user-0/cold-001/logcat.txt) — SHA-256 `9CF52121D1971998A403355121F409095CE2162B9DA1603842861DA6A86E7D26`

## Gate decision

`C4-R05 = BLOCKED`, `C4 = IN_PROGRESS`. The fixes are ready for review and the APK/static checks passed, but the strict readiness gate has not. Do not mark the use cases or stage formally closed. Next step is a matched run on the other machine (same build, cold/hot timings and logs), followed by the environment-versus-runtime comparison; if the SLO is intentionally relaxed for heavy commercial apps, that policy change must be recorded separately rather than hidden in this result.
