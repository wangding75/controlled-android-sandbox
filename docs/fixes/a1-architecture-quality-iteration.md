# A1 architecture quality iteration

## Goal

Raise the sandbox from a feature-complete local prototype toward a maintainable runtime base without splitting Gradle modules or changing runtime behavior.

## Frozen stages

1. **A1-1 Domain package boundaries — COMPLETE**
   - Move flat domain classes into explicit subdomain packages.
   - Preserve class names and behavior.
   - Add package/path architecture gates.
2. **A1-2 Typed IPC contract foundation — COMPLETE**
   - Added versioned Parcelable request/result/error/snapshot models.
   - Migrated the App-to-Broker runtime-status path to typed AIDL.
   - Kept a documented compatibility adapter for legacy Bundle callers during migration.
3. **A1-3 Domain ports and runtime dispatchers — COMPLETE**
   - Added `Clock`, `TokenGenerator`, `AuditSink`, and `SessionMetricsRepository`.
   - Migrated Runtime Status from `RuntimeBrokerService` into `RuntimeStatusDispatcher`.
   - Kept concrete Broker registry access behind `BrokerRuntimeStatusSource`.
   - Added automated port/dispatcher dependency gates.
4. **A1-4 Legacy package cleanup and dependency gates — COMPLETE**
   - Removed stage-specific `dev.controlledsandbox.b2` production and harness packages.
   - Reorganized Runtime and Framework into explicit responsibility packages.
   - Added path, dependency direction, external-access and Manifest component gates.

## A1-1 package map

| Package | Responsibility |
|---|---|
| `domain.packageinfo.manifest` | Manifest model and binary XML parsing |
| `domain.packageinfo` | Package upgrade policy |
| `domain.identity` | Virtual UID and path namespaces |
| `domain.session` | Guest Session and generation lifecycle |
| `domain.process` | Slot allocation and process planning |
| `domain.component.activity` | Activity launch and task state |
| `domain.component.service` | Service lifecycle state |
| `domain.component.receiver` | Dynamic and Manifest Receiver state |
| `domain.component.provider` | Authority, Observer, URI Grant and Cursor Lease state |
| `domain.routing` | One-time route tickets |
| `domain.persistence` | Recoverable persistence primitives |
| `domain.protocol` | Runtime protocol compatibility |

## Non-goals

- No Gradle module split.
- No Android behavior change.
- No new component functionality.
- No fake device-validation claim.
