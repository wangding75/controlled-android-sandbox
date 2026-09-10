# P2-01 reference decision — Xiaomi ARM64 baseline

**Status:** `NO_PRODUCT_CHANGE` — 2026-09-10 (Asia/Shanghai)

## Question

Does the currently discovered Xiaomi device require the 32-bit companion before
the P1 candidate Host and ARM64 fixture can be installed and exercised?

## Read references and observed contracts

| Source | Location / method | Observed behavior | Decision relevance |
| --- | --- | --- | --- |
| NBB | `Bcore/build.gradle`, `defaultConfig.ndk.abiFilters` | The reference build declares `arm64-v8a` and `armeabi-v7a`; it is a build-time inclusion list, not a device-install rule. | Do not infer a 32-bit companion requirement from a historical reference ABI list. |
| VA | `VirtualApp/lib/build.gradle`, `defaultConfig.externalNativeBuild.ndkBuild.abiFilters` | The 2017 open-source build declares only `armeabi-v7a` and `x86`. | It is not a modern ARM64/HyperOS execution template. |
| VA | `README.md`, ABI/plugin-process description | The product description says a plugin may be used for a different ABI, but provides no current device-specific selection algorithm. | Treat commercial README claims as implementation-unknown, not as authorization to install an unnecessary companion. |
| CAS | `NativeAbiRoutePlanner.route` and `requiresCompanion` | `arm64-v8a` routes to `HOST_64`; only `armeabi-v7a` and `x86` route to `COMPANION_32`; unknown metadata fails closed. | The P1 candidate can use the Host 64 route when the actual device and Guest both report `arm64-v8a`. |

## Chosen contract

The actual device is the authority for this baseline: `ro.product.cpu.abilist`
is exactly `arm64-v8a`, its page size is `4096`, and the P1 ARM64 fixture
installed successfully.  The runner recorded `fixture32` and `companion32` as
`NOT_IN_CURRENT_SCOPE`; no companion was installed.  This is not a 32-bit or
cross-width pass and does not decide conditional P2-09.

No CAS product source was changed.  The only task-specific corrections were to
invoke the existing smoke cases by their actual IDs (`S02-guest-import-add` and
`S04-warm-launch-reuse`) after the task-book shorthand was rejected before any
test action.

## Rejected alternatives

* Installing `fixture-compat32` merely because a reference project has an
  ARM32 ABI filter — rejected; it would contradict the actual ARM64-only device
  contract and manufacture an unrelated test lane.
* Treating VA's README product claims as evidence that the current Xiaomi
  supports a particular companion layout — rejected; the corresponding modern
  implementation is not present in the open-source reference.
* Altering ABI routing, package visibility, permissions, retries, or timeouts
  to get a commercial-app result — rejected; P2-01 has no product failure that
  justifies a source change.
