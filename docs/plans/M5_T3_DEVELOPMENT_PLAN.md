# M5-T3 Development Plan — Ordered Broadcast, PendingResult and Foreground Service

## Baseline

- Source baseline: `b4b94ec8a5eb0ab2c4c72f8f723932d2c28507db`
- Branch: `feature/m5-t3-broadcast-fgs`
- Device and emulator execution are outside this source batch because the current environment lacks the locked Android toolchain.

## Frozen scope

### Ordered Broadcast

- Preserve deterministic priority order and result code/data/extras propagation.
- Add bounded chain-wide execution budget, per-receiver timeout accounting and explicit skipped count.
- Distinguish receiver abort, policy abort, timeout and delivery exception terminal reasons.
- Keep failure handling outside `RuntimeBrokerService`.
- Keep one-shot result authority bound to package, virtual user, Session, generation and receiver class.

### PendingResult

- Track Broker completion Binder death inside the Guest process.
- Reject replay, late finish, stale generation and invalid payloads.
- Keep local timeout cleanup and prevent custom finish tokens from reaching Host AMS.
- Add constructor compatibility coverage without falling back to unordered delivery.

### Foreground Service

- Model `startForegroundService` as pending promotion rather than immediate foreground ownership.
- Enforce bounded promotion deadline.
- Enforce background-start allow/deny plus explicit exemption reason.
- Validate requested foreground-service type mask against the declared mask.
- Bind active foreground ownership to notification ID/tag.
- Handle demotion, stop, process death, sticky recovery and promotion timeout cleanup.
- Preserve started/bound Service behavior and M4-T14 lifecycle compatibility.

## Validation

- New pure-Java and Android-stub self-tests for ordered-chain budget and Foreground Service policy.
- Existing M4-T14 through M5-T2 regression gates.
- Static Android compilation, Host tests, Native/JNI tests, strict evidence gates and reproducible source package comparison.
- Real APK build is attempted but may remain blocked by the missing JDK 17 / Android SDK environment.

## Delivery

After PASS:

- fast-forward merge to local `main`;
- complete source ZIP;
- complete Git bundle;
- M5-T2 to M5-T3 patch;
- cumulative baseline patch;
- plan, development report, VA/NBB comparison, verification log and SHA-256 manifests.

## Execution result

**Execution status: PASS**

The frozen source scope is implemented. Full repository validation and artifact generation are recorded in the formal verification log and delivery package. Android build/device status remains separately blocked/not-tested.
