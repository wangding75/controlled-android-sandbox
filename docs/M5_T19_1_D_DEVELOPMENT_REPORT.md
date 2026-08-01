# M5-T19.1-D Binder death-registration atomicity report

## Scope

This task fixes P1-04 only. It does not add Android service hooks, change the frozen 113-capability
matrix or claim device validation.

Baseline:

- Commit: `c93141603f468dfb2f65f31048f65d0346936d30`
- Tree: `7eab3f18919c690a599934fde4584ef22de99110`

## Defect

Four ownership paths had a death-link publication gap:

1. Broker Provider observers linked before the callback record entered the callback registry.
2. Virtual Job executions linked before entering `activeJobExecutions`.
3. Package virtual-system-service sessions linked before `VirtualSystemServiceStore.register`.
4. Ordered-Receiver completions linked before the finish-token entry entered the interceptor map.

A Binder that died in this interval could invoke a cleanup callback that found no authoritative
record. The original thread then published the already-dead object.

## Implementation

A shared `DeathRegistrationHelper` now provides one link state machine:

- `NEW → LINKING → LINKED` for a live reservation;
- `LINKING/LINKED → DEAD` when the recipient fires;
- any state → `CLOSED` through idempotent cleanup;
- close during `linkToDeath` is remembered and unlinked after the platform call returns;
- the owner rechecks both helper liveness and authoritative-record identity before success.

Each repaired owner now performs reserve/insert before linking and rolls back on link exception,
immediate death, replacement or failed liveness recheck.

Package-session reservations and replacement rules moved into
`VirtualSystemServiceClientRegistry`, while system-service bounds moved into
`VirtualSystemServiceLimits`. This keeps `VirtualSystemServiceStore` at the existing 1,500-line
architecture ceiling without weakening the gate or compacting the new race logic into an opaque
block.

## Deterministic regressions

No regression uses `Thread.sleep` to manufacture the critical ordering. Test Binders invoke
`recipient.binderDied()` directly from `linkToDeath`.

| Owner | Expected result |
|---|---|
| Broker observer | Registration rejected; observer registry size is zero |
| Virtual Job execution | Start rejected; Job remains `SCHEDULED`; active execution map is empty |
| Virtual-system-service session | Session inactive; client registry is empty; a dead replacement preserves the previous live session |
| Ordered Receiver completion | Install rejected; finish-token registry size is zero |

## Validation status

- Source fix: PASS
- Four immediate-death direct regressions: PASS
- Four-owner immediate-death suite repeated 100 times after extraction: PASS
- Static Android-source compilation and Host test suite: PASS
- Existing M5-T19.1-A/B/C regressions: PASS
- Android Gradle/APK build: not executed
- Android Binder-driver evidence: 0
- Emulator evidence: 0
- Physical-device evidence: 0

## Remaining limitations

- The Host Binder stub cannot prove the exact Android Binder-driver callback ordering.
- Real process death, remote Binder teardown and OEM scheduling remain device-gated.
- Other Binder registration sites were not expanded into this task unless they were one of the four
  reviewed P1-04 owners.
