# T56-R02-Q02 Quark Manifest Analysis

Device: `192.168.137.186:39531` model `25019PNF3C` / `xuanyuan` API 36 HyperOS OS3.0  
HEAD: `bfa436f14044c47c51809ae8d95f3c049b9d0fa5` = `origin/feature/t56-product-convergence`  
Worktree: dirty (other generic work interleaved)

## Root Cause

Classification: **OTHER_PROVEN_PARSER_DEFECT**  
Android 11+ `<queries><provider android:authorities="…"/></queries>` is a **package-visibility query**, not an application `ContentProvider`.

Flash2 treated every `<provider>` start tag as `ManifestModel.addProvider`. Two query nodes have **no `android:name` attribute** and different authorities, so they collided on `className=""` and `mergeFrom` correctly fail-closed.

Not:

- STRING_POOL_INDEX_HANDLING
- RAW_VALUE_MISSING_TYPED_STRING_PRESENT
- ATTRIBUTE_RESOURCE_MAP (resource map exists and works for named attrs)
- skip-empty-provider workaround

### Android official (`aapt2 dump xmltree`)

- 36 `E: provider` tags
- First two are children of `E: queries` (manifest line 12–13)
  - only `android:authorities=com.oplus.privacysandbox.provider`
  - only `android:authorities=com.oplus.securepay.SampleProvider`
  - **no `android:name`**
- Remaining 34 are application ContentProviders with non-empty names

`QUARK_SPLIT_COUNT=0` (single `base.apk` only)

### Binary XML (pulled base)

| field | query provider 0 | query provider 1 |
|---|---|---|
| parent | `queries` | `queries` |
| attrs | 1 (`authorities`) | 1 (`authorities`) |
| `name` pool / raw / typed | **absent** | **absent** |
| authorities raw/typed | `com.oplus.privacysandbox.provider` TYPE_STRING | `com.oplus.securepay.SampleProvider` TYPE_STRING |

Application providers encode `android:name` as pool key `name` (resid `0x01010003`) + TYPE_STRING. Decoder already resolves those.

### Flash2 old result

- Two empty-name “providers” entered `ManifestModel`
- `Conflicting duplicate component declaration:` (blank class)
- Import aborted; Guest never started

### Flash2 after fix

Offline parse of the same pulled APK:

- `PROVIDER_COUNT=34`
- `EMPTY_PROVIDER_NAMES=0`
- `ACTIVITY_COUNT=411` `SERVICE_COUNT=141` `RECEIVER_COUNT=40`
- launcher `com.ucpro.MainActivity`
- application `com.ucpro.QuarkApplication`
- versionCode `1130`

## Generic Fix

`BinaryXmlManifestParser`:

- If the element stack contains `queries`, do not register `provider` / `activity` / `service` / `receiver` / `activity-alias` as components.
- Application components still require a non-empty `android:name` (`MISSING_COMPONENT_NAME:<tag>`).
- `ManifestModel.mergeFrom` conflict check unchanged.
- No package / OEM / app-name branches. Special-case count: **0**.

## Tests

- `testQueriesProviderIsNotComponent` — two query providers + one app provider → one component
- `testTypedStringComponentNames` — P01–P07 names on activity/alias/service/receiver/provider
- `testMissingApplicationComponentNameFailsClosed` — P08
- Existing `testManifestParser` still PASS
- `:sandbox-domain:selfTest` PASS

`static_android_compile`: domain SelfTest PASS; full script fails on pre-existing `FrameworkProxySelfTest` (AMS outbound process records, **not Q02**).

`:app:assembleDebug` PASS.

## Quark Import

| item | value |
|---|---|
| Host package / version | `com.quark.browser` `10.15.5.1130` / `1130` |
| base / splits | base only, SHA-256 `81BDDB678F3918B683CDC48C0CCF8682137E0C1CBF23930DF17B36120446206A` |
| original empty-name duplicate | **COUNT=0** after parser fix |
| untrusted import | `UNTRUSTED_NATIVE_GUEST_DENIED` (product trust gate; same as UI “不信任”) |
| trusted import (`trustNativeGuest=true` = UI “信任并导入”) | `INSTANCE_READY` |
| providers in Guest prepare | FileProvider, AppDataShareProvider, CleanerProvider, FileStatusContentProvider READY |
| Package Record | created (lookup then instance) |
| launcher resolve (offline) | `com.ucpro.MainActivity` |

`QUARK_IMPORT_PASS` for manifest/package record. Duplicate empty-name error gone.

## Quark Runtime Canary (one launch)

Stopped at first prepare failure. **Not LAUNCH_PASS.**

| gate | result |
|---|---|
| GUEST_PREPARED | FAIL |
| Application | `setEmbeddedParam` published `com.quark.browser` (Guest pid 23832) |
| Activity create / resume | not reached |
| 30s / FATAL / ANR | process killed after prepare fail |
| Host Quark | still `u0_a303` pid **22457** |

### First Runtime Blocker

- subsystem: Guest Provider prepare / `PackageItemInfo` metadata
- API: `PackageItemInfo.loadXmlMetaData(PackageManager, String)`
- exception: `NullPointerException` (receiver null)
- after 4 providers READY; next typical FileProvider (`com.uapp.adversdk.file.FileProvider`) needs XML meta
- classification: **GENERAL_RUNTIME / PROVIDER_METADATA**  
- **not** Instagram `Intent[]` Broker error

Q02 does not fix this.

## Cross-App (Quark vs Instagram G01–G10)

| item | Quark canary |
|---|---|
| `:guestN` / setArgV0 / setEmbeddedParam | Guest process named `com.quark.browser`; embedded identity published |
| setComponentEnabledSetting | not reached |
| TelecomManager | not reached |
| PendingIntent.getBroadcast / getIntentSenderWithFeature / `Intent[]` | **not triggered** (died earlier) |

`GENERIC_CONFIRMED_BY_CROSS_APP` for `Intent[]`: **no** (Quark did not reach that API).

## Host Safety

`HOST_QUARK_MUTATION = NONE_OBSERVED`

- pid 22457 unchanged
- versionName/versionCode unchanged
- lastUpdateTime still `2026-08-14 00:35:33`
- no clear / force-stop / uninstall / install -r of Quark

## Git

- branch `feature/t56-product-convergence`
- HEAD = origin = `bfa436f1`
- parser + SelfTest sit in files that already had other dirty hunks
- **COMMIT_DEFERRED_DUE_TO_INTERLEAVED_WORKTREE**
- no push
