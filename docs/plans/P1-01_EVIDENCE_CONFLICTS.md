# P1-01 Evidence Conflicts and Scope Boundaries

Status: resolved as classifications, not erased. CAS HEAD is `40f629d03bbb0927f171a0e318c242398d8a141d`.

| Item | Evidence | Classification / action |
|---|---|---|
| REALAPP-COMPAT-02 S01-S10 | `out/verification/realapp-compat-02-s01s10-20260907/run.json` is a 10/10 PASS but identifies `51c53726` as both start and final HEAD. | Historical evidence only. Its host APK hash (`7c04…1271e0`) differs from the current debug host (`1447…4162a0`). It cannot establish current-HEAD device PASS. |
| Current AVD short baseline | `out/verification/p1-01-api36-s01-s04-continuous-20260908/run.json` binds the current HEAD, three APK SHA256 values, emulator fingerprint, API 36, x86_64, and 4 KB page size. | Current HEAD evidence, limited to the S01-S04 fixture contract; it is not a Xiaomi or commercial-App result. |
| Chrome | `realapp-compat-02-realapps-final-20260907/chrome-launch/debug-command-result.json` records a nested `NullPointerException` at launch. | Retained FAIL. Do not infer a Chrome launch or browser-flow PASS. P1-03/P1-04 are the next dependent investigations. |
| Quark | `…/quark-launch/debug-command-result.json` records `LAUNCH_PASS`, a visible first frame, session `a265b7da-e253-4bcc-a877-082c979007b9`, generation `1`. | Historical first-frame PASS only. Browser/business smoke is `NOT_TESTED`; it must not be promoted to BASIC_SMOKE. |
| Capability matrix | `realapp-compat-01-capability-final-20260907/capability-matrix.json` is from `bd315b27`, with 8 PASS and 2 FAIL. | Retain both failures: scheduling/notification/alarm/job/FGS markers and native-adversarial CASE 010. No capability equivalence claim. |
| VA paths in the desktop task book | P1-01 originally listed `D:/github/t57-reference-sources/VirtualApp/VirtualApp/README.md`, which does not exist. | Corrected to `D:/github/t57-reference-sources/VirtualApp/README.md`. The library build file remains under `VirtualApp/VirtualApp/lib`. |
| VA PRO comparison | The local README says open-source code stopped in 2017 and provides only commercial update statements. | `VA_PRO_EQUIVALENT = NOT_PROVEN`; no commercial function has been inferred from changelog text. |
| Single-case S04 invocation | `p1-01-api36-s04-20260908` reports `ACTIVITY_REUSE_NOT_OBSERVED`, while its own launch result has first frame. | Invalid isolated invocation: the runner tears down Host after S03, violating S04's live-session precondition. Retained as harness-use evidence; the required continuous S01-S04 run is 4/4 PASS. |

All P1-01 local references now resolve. The existing untracked `out/` tree was preserved; no old receipt was altered.
