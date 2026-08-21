# T57-R03 C0-T01 Continuation Protocol Evidence

## Scope

`C0-T01` verifies that a new PowerShell session can continue the CAS VA PRO
catch-up plan using only repository files. The check covers the task book,
progress ledger, governance files, last receipt, dependency readiness, Git
identity, branch/upstream/remote HEAD, evidence directory, and dynamic MuMu
`RD测试` resolution.

## DISCOVER / CLASSIFY

The initial read-only discovery found:

| Finding | Classification | Action |
|---|---|---|
| `KNOWN_ISSUES.yaml` contains the resolved `KI-R03-NATIVE-010` state `FIXED`, while the campaign validator rejected it | `TEST_EVIDENCE_GAP` / governance drift | Add `FIXED` to the governed issue-status enum; preserve the historical issue receipt |
| `tools/device/t57_quark_stability_probe.ps1` defaulted to historical serial `127.0.0.1:16416` | `ENVIRONMENT_BLOCKED` risk | Resolve `RD测试` through `t57_rd_common.ps1` at runtime and remove the default serial |
| No repository-level continuation preflight located the first dependency-ready task and its last receipt | `TEST_EVIDENCE_GAP` | Add `scripts/verify-catch-up-continuation.py` and its unit tests |

No new runtime `KNOWN_ISSUES` entry was created. Historical serials in old
reports remain evidence only. Historical serials in the explicit guard lists
of the existing campaign validator scripts are reported as allowlisted guards,
not used to select a device.

## Implementation

- Added the fail-closed continuation preflight and four focused unit tests.
- Added `FIXED` to `tools/capability/campaign_status.py` issue status governance.
- Changed the Quark stability probe to use the shared dynamic RD resolver and
  to write a device snapshot before monitoring.
- Marked `C0-T01` `IN_PROGRESS` in the progress ledger before implementation.

## Acceptance commands and results

All commands ran from `D:\github\controlled-android-sandbox`:

| Command | Result |
|---|---|
| `git fetch origin` and branch/upstream/remote HEAD checks | PASS; local and remote were `36513fec4984a277324353974d454bc99abc71ef` before implementation |
| `python scripts/mumu_instance.py --instance-name RD测试` | PASS; runtime-resolved instance `RD测试`, API 32, model `22041211A`, boot ID recorded in JSON evidence |
| PowerShell AST parse of `tools/device/t57_quark_stability_probe.ps1` | PASS |
| `python scripts/test_catch_up_continuation.py` | PASS; 4 tests |
| `python tools/capability/validate_campaign_infra.py` | PASS |
| `python tools/capability/test_campaign_infra.py` | PASS; 9 tests |
| `powershell -NoProfile -NonInteractive ... python scripts/verify-catch-up-continuation.py` | PASS in a second PowerShell session |
| `git diff --check` | PASS |

The continuation preflight reported the active task as `C0-T01`, last completed
task as `BOOTSTRAP-DOCS`, readable prior evidence paths as the task book and
progress ledger, canonical local identity `OpenAI <openai@users.noreply.github.com>`,
no unexpected executable hard-coded serials, and evidence directory
`verification/catch-up/C0-T01`.

## Evidence files

- `verification/catch-up/C0-T01/continuation-preflight.json`
- `verification/catch-up/C0-T01/continuation-preflight-second-session.json`

Both JSON files include the Git baseline, prior receipt commits, dynamic device
snapshot, static serial scan, and continuation directory. `RD测试` PASS is
recorded only as an API32/RD baseline and is not promoted to any broader
compatibility claim.

## Risks and limits

This task validates continuation governance and dynamic device selection. It
does not claim the C0-T02 reproducible build, C0-T03 full RD regression, or any
later phase gate. The allowlisted historical serial strings remain only in
negative/static guards and are not operational endpoint defaults.
