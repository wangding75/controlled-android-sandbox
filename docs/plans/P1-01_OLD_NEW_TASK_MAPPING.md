# P1-01 Old-to-New Task Mapping

This mapping preserves prior task identifiers and evidence provenance. It does not re-close an old task or upgrade historical evidence.

| New task | Prior fact source / task | Carry-forward rule |
|---|---|---|
| P1-01 | C0-T01, C0-T02, C0-T04 | Reuse only their baseline/ledger conventions; current HEAD and APKs are frozen independently in `P1-01_BASELINE.json`. |
| P1-01 | C6-T01A through C6-T01G | Cross-API evidence remains historical unless the exact source HEAD and APK hashes are the same. |
| P1-01 | C6-T02C | Historical Xiaomi 25019PNF3C / API36 / ARM64 / 4 KB evidence remains device-specific; never reuse its stale wireless serial. |
| P1-01 | REALAPP-COMPAT-01 | Capability matrix is retained with its two FAIL results and old source head. |
| P1-01 | REALAPP-COMPAT-02 | The 15-file commit is the current source delta. Chrome is still launch FAIL/NPE; Quark is only launch/first-frame PASS. |
| P1-02 | Prior broad NBB/VA comparison work | Replaced by a per-product-change reference decision record and a failing pre-fix fixture. |
| P1-03 / P1-04 | REALAPP-COMPAT-02 | Own shared-library projection and startup/Application/LoadedApk investigations, respectively. |
| P1-05 to P1-15 | C1/C2/C3/C6 evidence | Prior evidence supplies hypotheses and regression context only; each task needs its own current fixture and acceptance. |
| P1-16 then P2-01 | C6-T02C and C7 planning | Candidate freeze preceded fresh Xiaomi discovery. P2-01 now has a current ARM64 fixture baseline, but remains `BLOCKED_DEVICE_APP_LOCK` until the native Quark control is unlocked; see `P2-01_XIAOMI_BASELINE_REPORT.md`. |

The old ledger remains an historical ledger. This two-stage plan is the current execution ordering.
