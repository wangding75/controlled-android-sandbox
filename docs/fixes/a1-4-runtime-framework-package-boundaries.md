# A1-4 Runtime and Framework package boundaries

## Scope

This stage is a structural refactor only. It does not add sandbox behavior and does not split Gradle modules.

## Runtime package map

| Package | Responsibility |
|---|---|
| `runtime.broker` | Binder service, caller guard, broker state, production clock/token/audit adapters |
| `runtime.guest` | Guest process bootstrap, class loading, context, resources, shared preferences and WebView profile |
| `runtime.component.activity` | Activity broker/guest bridge and Stub activities |
| `runtime.component.service` | Broker Service lifecycle adapter |
| `runtime.component.receiver` | Dynamic and Manifest Receiver runtime adapters |
| `runtime.provider` | Provider routing, Batch, Cursor, FD, Observer and lifecycle coordination |
| `runtime.diagnostics` | Structured events, crash/liveness diagnostics and init provider |
| `runtime.protocol` | Legacy Bundle operation names and keys during typed-contract migration |
| `runtime.status` | Typed runtime-status dispatcher, source and legacy adapter |

## Framework package map

| Package | Responsibility |
|---|---|
| `framework.core` | Hook installation, proxy lifecycle, signatures and telemetry |
| `framework.identity` | Guest identity, argument/result rewriting and package metadata |
| `framework.activity` | AMS/ATMS hooks and Activity/task model |
| `framework.routing` | One-time Activity route payloads and ownership |
| `framework.packagemanager` | PackageManager proxy |
| `framework.permission` | PermissionManager and AppOps hooks |
| `framework.service` | Notification, JobScheduler and Storage hooks |

## Enforced rules

- No production or test source may use `dev.controlledsandbox.b2`.
- Runtime and Framework Java package declarations must match their filesystem paths.
- Flat root classes are forbidden.
- Internal package imports follow explicit allowlists.
- App may access only the Runtime Binder entry point, protocol compatibility keys and diagnostics facade.
- Android Manifest components must reference the migrated Service, Activity and Provider classes.
- The local static Android compiler executes all migrated self-test main classes by their new names.
- Historically distinct same-name regression suites are preserved under unique class names and executed exactly once.

## Visibility

Moving Java classes across package boundaries requires a limited set of types and methods to become public. These are still internal implementation APIs: external access is prohibited by `scripts/check-package-boundaries.py`. Public visibility is used only to cross internal Java package boundaries inside the existing Gradle module.

## Validation boundary

This stage validates source layout, Java compilation, local self-tests and manifest names. It does not validate Android class loading, Binder process startup or OEM behavior on a device.
