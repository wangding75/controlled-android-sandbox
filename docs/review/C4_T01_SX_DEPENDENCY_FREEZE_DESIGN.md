# C4-T01 SX Dependency, Feature and Runtime Freeze

## Scope

This task freezes the SX production surface that C4 will migrate onto CAS as
the **only** sandbox host. Source of truth for the live SX tree is
`D:\github\all_project\sx` (override with `SX_SOURCE`). CAS mappings are in
this repository. The freeze does not copy BlackBox, Pine, or Xposed into CAS.

RD results are a device snapshot plus static completeness. They are not SX
business PASS, DingTalk PASS, or VA Pro equivalence.

## DISCOVER / CLASSIFY

Existing T52 inventories (`SX_LEGACY_FEATURE_INVENTORY.md`,
`SX_TO_SANDBOX_MAPPING.md`, `SX_UI_INVENTORY.md`) are historical. They do not
machine-check live Gradle modules, `SandboxEngine` methods, dynamic loads, or
data keys against `SandboxSdk`. That is `KI-R03-046` (`TEST_EVIDENCE_GAP`).

Live SX Gradle graph:

```text
:app
  -> :sandbox-api
  -> :engine-bb
       -> :sandbox-api
       -> :Bcore
       -> :Bcore:pine-core
       -> :Bcore:pine-xposed
:Bcore:black-fake / black-hook / pine-xposed-res
:android-mirror
```

Startup chain:

```text
SxApp.attachBaseContext
  -> LicenseConfig.configure (DROP)
  -> SandboxProvider.init
       -> BuildConfig.SANDBOX_ENGINE==blackbox ? BlackBoxSandboxEngine : FakeSandboxEngine
       -> SandboxEngine.initialize / onAttachBaseContext
  -> SxApp.onCreate -> onAppCreate + TimeGuard
```

`Class.forName("top.niunaijun.blackbox.BlackBoxCore")` appears in product
`ProfileRepository` and `ConfigBroadcast`. Pine/`xposed_init`/LSPosed metadata
remain in the SX APK. Those are DELETE for CAS production.

## Disposition

| Disposition | Meaning | Owner after freeze |
|---|---|---|
| `REPLACE` | Keep the product behavior; CAS SDK/adapter is the only engine | C4-T02 |
| `REIMPLEMENT_DATA` | Keep schema semantics; migrate onto instance-scoped CAS profiles | C4-T03 |
| `DELETE` | Must not ship in CAS production APK | C4-T04 |
| `DROP_NON_BUSINESS` | License/server/time-guard/fingerprint stay out of sandbox | product, not C4 |
| `GENERIC_ALREADY` | Already closed by C1/C2/C3; SX Hook is not copied | C4-T05 evidence |
| `OPTIONAL_SKU` | Third-party Xposed host; excluded by C3-T06 | C5-T04 only if ordered |

## Production entries

Every SX `SandboxEngine` method, launcher Activity, F1-F5 hook, data store,
and Gradle runtime module has a row in
`verification/catch-up/C4-T01/c4-t01-freeze.json`. Unknown classes and
dynamic loads in `app` / `sandbox-api` / `engine-bb` are listed there; Bcore
internals are recorded as a DELETE blob, not individually mapped.

CAS target for engine methods is `SandboxSdk` plus Host UI already rebuilt
behind `SxSandboxAdapter`. Adapter still talks to `PackageServiceClient` /
`RuntimeClient` directly; C4-T02 must make that the only engine path and
remove any remaining BlackBox reflection.

## Migration order

1. C4-T01 this freeze (no production runtime change except continuation harness).
2. C4-T02 `SandboxEngine` → `SandboxSdk` only.
3. C4-T03 `sx_config` / ConfigProvider / media bytes → versioned instance profiles.
4. C4-T04 delete engine-bb, Bcore, Pine, `xposed_init` from production graph.
5. C4-T05 F1-F5 + DingTalk on CAS-only, package-neutral first.

## Acceptance

- Freeze JSON covers every live SX engine method, product Activity, F1-F5
  hook, data key, Gradle runtime module, and recorded dynamic load.
- Each row has `disposition` and `cas_target` or an explicit drop reason.
- CAS production source still has no `BlackBoxCore` / `PineXposed` /
  `xposed_init` runtime dependency (rechecked).
- `va_pro_equivalent` remains `NOT_PROVEN`.
