# T53 Global Review

Review date: 2026-08-12. Scope: changed source, generic F2-F5 paths, isolated DingTalk manager, fixture/runtime evidence, T52 regression boundaries, and backup requirements.

## Findings

| Severity | Finding | Classification | Disposition |
|---|---|---|---|
| P0 | No evidence of credential, payment, license or authentication bypass | Out of scope / F6 | None introduced |
| P0 | No package-specific branch in generic framework/runtime/native source | Architecture | Verified by source search; DingTalk gate is isolated in `compatibility/dingtalk` |
| P0 | No host media absolute path or host location value crosses the Guest contract | Isolation | Source store copies into instance-owned Guest files; location values come from profile |
| P0 | No fake READY, System.exit suppression, SIGSEGV anti-suicide, kill hook or catch-ignore fallback | Lifecycle/security | Rejected legacy SX approaches; failures remain explicit |
| P1 | Legacy `android.hardware.Camera` native path bypasses Java `media.camera` Binder proxy; Camera1 substitution is incomplete | General Camera1 adapter | OPEN and blocks T53 PASS; native Camera1 adapter or explicit production fail-closed gate is required |
| P1 | Guest WebView late-unbind during teardown raised `ServiceConnection not bound` in Companion32 | GENERAL_RUNTIME_DEFECT | CLOSED by teardown state ordering and explicit late-unbind lifecycle handling; final Stage A has zero target FATAL/ANR matches |
| P1 | Camera2 image/video path has no fatal signal in final fixture runs | Generic Camera2 | Closed for tested MuMu path; vendor/HAL matrix remains for hardware |
| P2 | SurfaceView callback was not produced by the virtual Guest window; SurfaceTexture preview path passed | Android window compatibility | Documented fixture limitation; no DingTalk-specific workaround |
| P2 | First user1 launch was killed by MuMu low-memory killer and recovered on retry | External emulator resource pressure | Recorded in evidence; not a profile or runtime identity mix-up |
| P2 | DingTalk protected pages were not entered without a real account | Product/session boundary | `REAL_USER_SESSION_REQUIRED`; generic fixtures remain independently complete |

## Architecture checks

- Generic profiles are persisted once per instance/user and sampled by service adapters; API-call randomization was not used.
- Camera media uses instance-owned relative metadata and SHA-256 verification. Host absolute paths are rejected by the contract self-test.
- Location, device, Wi-Fi and cell services are projected through the Guest service/runtime boundary rather than by editing DingTalk UI data.
- Camera sessions, callback lists and media readers are bounded and teardown paths remove identities. Camera2 output stream IDs are allocated per session.
- Binder payloads use profile snapshots and bounded lists. No camera bytes are transported through a profile Binder call.
- Binder death/reconnect and generation recovery remain governed by the T52 runtime broker; DingTalk startup recovery exercised that path.
- ClassLoader, Provider, Activity, Context and package projection remain generic. No DingTalk dex modification or exported flag fabrication exists.
- Native camera buffer code is limited to a Guest-owned `Surface` and sandbox-owned JPEG bytes; it writes the camera3 JPEG BLOB footer and returns explicit negative adapter errors.

## Verification commands/results

- `git diff --check`: PASS.
- `:sandbox-contract:test :sandbox-framework:test :app:testDebugUnitTest`: PASS.
- `:app:assembleDebug :sandbox-companion32:assembleDebug :fixture-basic:assembleDebug`: PASS.
- Final Stage A: 16/16 PASS across fixture64/fixture32 × user0/user1; target FATAL/ANR matches: 0.
- Final Quark short regression: import/launch/stop 3/3 PASS; target FATAL/ANR matches: 0.
- Final DingTalk loop: profile plus 10/10 launch and 10/10 stop PASS; target FATAL/ANR matches: 0.
- Camera2 image fixture after the teardown fix: preview/capture/result PASS without FATAL/SIGABRT.
- Camera1 fixture: explicit boundary failure, no result PASS claimed.
- Core package search for `com.alibaba.android.rimet`: only isolated DingTalk manager and audit documentation contain the gate.
- Static handwritten Android compile harness: not used as product build evidence; its known stub limitations are retained in the evidence log. AGP/Gradle builds are authoritative.

## Post-fix lifecycle evidence

The original post-seal Stage A log exposed a real `Chrome_ProcessLauncherThread` FATAL in 32-bit Companion32: WebView called `GuestContext.unbindService` after the runtime had already released the Broker-side connection. The generic fix closes Guest component routing before component/provider teardown, preserves strict unbound errors while live, and accepts only a late unbind after teardown. The final 16-case run records the explicit `CS_GUEST_SERVICE late unbind ignored after component-router teardown` marker and no target FATAL/ANR matches.

The immutable revision fix also removes only ART-generated `lib/oat/*.prof` sidecars before publishing a revision and then seals the published tree. Quark import and the final short lifecycle regression pass without mutable revision drift.

## T53 disposition

There are no unresolved P0 findings. The open P1 Camera1 native adapter is a required F3 item in the user's PASS criteria, so the truthful result is `T53 RESULT: BLOCKED` until that generic boundary is completed and re-regressed. No main merge, amend, rebase, squash or force push is permitted.
