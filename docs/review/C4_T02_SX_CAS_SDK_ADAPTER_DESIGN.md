# C4-T02 SX CAS SDK Adapter

## Scope

C4-T02 makes CAS the only sandbox engine path for SX `install/start/stop/user/profile/status`
entries. The live SX tree still contains `BlackBoxSandboxEngine`; deleting `engine-bb`/Bcore
is C4-T04. This task ships the CAS adapter that Host UI, debug smoke, and future SX wiring
must use.

RD results are `RD_BASELINE` only. They are not DingTalk PASS, live-SX APK PASS, or VA Pro
equivalence.

## DISCOVER / CLASSIFY

C4-T01 froze 17 `SandboxEngine` methods onto `SandboxSdk`, but:

1. Host UI used `SxSandboxAdapter` extras (`launchBundle`, `createClone`, `importInstalledApplication`)
   instead of the public SDK/engine mapping.
2. `SandboxSdk` had no `importInstalledApplication` / `stopAll`.
3. SDK failures often threw instead of returning diagnosable `SandboxOperationResult`.
4. Clone had no adapter-level rollback if the record lookup failed after user allocation.
5. There was no observer that re-reads catalog/status from CAS after each mutation.
6. There was no package-neutral RD fixture proving install, start, clone user, cleanup,
   recovery, and missing-package fail-closed through the engine.

That is `KI-R03-047` (`TEST_EVIDENCE_GAP`).

## Design

```text
UI / debug command
  -> SandboxApplicationLayer
       -> CasSandboxEngine   (SX method names, no catalog cache)
            -> SandboxSdk
                 -> SxSandboxAdapter
                      -> PackageServiceClient / RuntimeClient
                           -> PackageManagementSession / RuntimeBroker
```

Rules:

- `CasSandboxEngine` never stores `SandboxCatalog` or instance lists. Every lookup calls
  `sdk.catalog()` or `sdk.status()`.
- Adapter does not copy CAS authority. PackageManagementSession remains the lifecycle
  transaction owner.
- `onAttachBaseContext` is `NO_OP_CAS_HOST` (`DELETE`). No BlackBox ClassLoader hook.
- Failures return `errorCode` values (`PACKAGE_NOT_INSTALLED`, `CLONE_FAILED`, `SOURCE_REQUIRED`).
- Failed `cloneInstance` deletes the newly allocated virtual user.
- `createShortcut` only exposes public `instanceId`; Host `SandboxShortcutManager` pins it.
- `setDisplayName` is observed from catalog and not persisted here (`C4-T03`).
- Profile bytes / `sx_config` / ConfigProvider remain `C4-T03`.
- Production screens keep using `SandboxApplicationLayer`; engine operations now go through
  `CasSandboxEngine`. Debug `c4-t02-engine` uses the adapter only, not `PackageServiceClient`.

## Method map

| SX SandboxEngine | CAS mapping |
|---|---|
| initialize / isReady / onAppCreate | `SandboxSdk.status` |
| onAttachBaseContext | DELETE no-op |
| installFromHost | `importInstalledApplication` + `ensureInstance` |
| installFromApk | `importPackage` |
| uninstall | `deleteInstance` |
| clearData | `clearData` |
| listInstalled / get / isInstalled | `catalog` lookup |
| launch | `launch` |
| kill | `stop` |
| killAll | `stopAll` |
| clone | `cloneInstance` with rollback |
| createShortcut | identity `instanceId` for Host pin |
| setDisplayName | catalog observation; persist in C4-T03 |

## Acceptance

- Generic fixture import/start/clone/stop/clear/delete/recovery on dynamically resolved
  `RD测试` through `c4-t02-engine`.
- Missing package launch is fail-closed and diagnosable.
- Adapter does not own catalog state.
- Production Java has no `BlackBoxCore` / `PineXposed`.
- `va_pro_equivalent` remains `NOT_PROVEN`.
