# M4-T15 comparison with VirtualApp and NewBlackbox

## Scope

This report compares only the M4-T15 Activity/Task virtualization increment. Source implementation, production wiring and Android device evidence are reported separately. VA/NBB repository implementations are reference points, not independent proof that every branch works on current Android releases.

## Added in this iteration

- Additional Android-compatible Activity launch flag policy.
- `FORWARD_RESULT` ownership transfer and fail-closed validation.
- `NO_HISTORY` retirement and bounded document-task recent policy.
- Package/user-isolated running and recent task queries.
- Owner-checked task move-to-front and removal.
- Atomic, CRC-protected task checkpoint persistence.
- Runtime Broker restart restoration with dead transport authority removed.
- Typed Binder request/result/task projection contract for internal task operations.

## Comparison

| Capability | Controlled Sandbox M4-T15 | VirtualApp | NewBlackbox | Current gap |
|---|---|---|---|---|
| Launch modes | Explicit standard/singleTop/singleTask/singleInstance source model | Mature virtual AMS/ATMS routing | Broad modern routing | Android signature/device matrix incomplete |
| Intent launch flags | Bounded task-affecting flag policy including new-document, no-history and recent controls | Broad implementation accumulated over time | Broad implementation, branch dependent | Many compound/version-specific cases remain |
| Activity result forwarding | Ledger-owned `FORWARD_RESULT` chain with invalid-state rejection | Mature result routing | Broad result/Binder routing | Guest framework callback integration incomplete |
| Running task query | Session-, package- and virtual-user-scoped internal projection | Mature virtual task projections | Broad task-manager hooks | No complete `RunningTaskInfo` framework adapter |
| Recent task policy | Bounded deterministic archive with exclude/retain rules | Mature Recents/task handling | Broader modern handling | System Recents UI and `RecentTaskInfo` parity absent |
| Move/remove task | Owner-checked Runtime Binder operations | Mature AppTask/task manager paths | Broad task controls | Guest `AppTask` facade absent |
| Broker restart restoration | Versioned atomic CRC checkpoint; dead routes/results dropped | Mature process/task recovery, branch dependent | Broader runtime recovery, project dependent | Window state and platform process recreation absent |
| Cross-user isolation | Explicit package/virtual-user checks in query and mutation | Established virtual-user model | Established virtual-user model | Device Binder identity evidence absent |
| Device evidence | 0% under current evidence policy | Long implementation history, version dependent | Version/project dependent | No controlled Android build or Emulator evidence |

## Evidence-based judgment

M4-T15 closes a real source-level gap: Activity state no longer exists only as volatile in-process ledger data. Task ordering, bounded recent history and saved Activity state can survive Runtime Broker restart without reviving dead one-time route authority. The added query/mutation contract also makes task ownership explicit and testable.

VA and NBB remain substantially ahead in Android-version-specific AMS/ATMS interception, framework object projection, Window and transition integration, system Recents behavior and accumulated third-party application testing. M4-T15 is not equivalent to either project at runtime and does not support a compatibility-rate claim.

## Metrics after M4-T15

- Capability entries: 95.
- Source: 91 complete, 4 partial, weighted 97.9%.
- Production: 87 wired, 6 partial, 1 blocked, 1 not applicable, weighted 95.7%.
- Device: 0 verified, 93 not tested, 1 blocked, 1 not applicable; weighted 0.0%.

These are repository evidence metrics, not APK compatibility percentages.

## Unfinished items

1. Guest-facing `ActivityManager`/`ActivityTaskManager` adapters for running/recent task and `AppTask` calls.
2. Full startActivity-for-result callback path through actual Android Binder signatures.
3. Window/token/transition and process-recreation fidelity.
4. Complete launch flag interaction matrix across Android API levels.
5. Device build, Emulator fixture execution and third-party APK evidence.
6. Decomposition of the enlarged Activity task ledger and transactional persistence handling.

## Next priority

M4-T16 should connect the task model to Android framework-facing adapters: bounded `RunningTaskInfo`/`RecentTaskInfo`/`AppTask` projections, start/result callback routing, task-front/removal calls and API-version signature policies. Only after that source path is stable should Emulator Activity/task validation be promoted to a formal device gate.
