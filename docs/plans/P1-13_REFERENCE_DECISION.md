# P1-13 Reference decision — split packages, dynamic loading, and revision lifecycle

Date: 2026-09-09.  This record was completed before P1-13 validation or product-code changes.

## Reference methods actually examined

| Source | Method / observed behavior | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NewBlackbox | `PackageManagerCompat.generateApplicationInfo` (lines 291–365) projects the package's `sourceDir`, `publicSourceDir`, `nativeLibraryDir` and a bounded shared-library list; `getResources` (367–375) creates an `AssetManager` for the package APK.  It is not a general split or Trichrome implementation. | `VirtualPackageStateBuilder.buildApplicationInfo` projects `splitNames`, `splitSourceDirs` and `splitPublicSourceDirs`; `GuestResourceLoader.load` adds base and every verified split to one `AssetManager`. | Preserve the complete, verified artifact set.  Do not copy NBB's Apache-legacy-library special case or infer a Chrome-specific rule. |
| NewBlackbox | `IOCore.enableRedirect` (107 onward) installs package-private data, library and profile path rules before Guest use. | `GuestRuntimeEnvironment.prepare` derives the immutable revision, data root and native-library search path before creating the loader. | Path routing remains package/user/revision scoped; no general remapping for a plugin or protected APK is added. |
| VirtualApp OSS | `PackageParserEx.initApplicationInfoBase` (191–225) sets code/native paths and, only for `dependSystem`, obtains `sharedLibraryFiles` from an unhooked host PackageManager. | `GuestPackageSpec.dexPath`, `GuestClassLoader`, and `GuestSharedLibraryPathResolver` use broker-verified package projections. | `sharedLibraryFiles` is a projection boundary, not proof that arbitrary host code or a missing split may be accepted. |
| VirtualApp OSS | `NativeEngine.startDexOverride` (47–57) snapshots installed APK paths; `redirectDirectory`/`enableIORedirect` (77–143) delegate Java/native redirection.  The README describes the old Java/framework/native architecture but contains no current commercial implementation. | `PackageRevisionSetVerifier.verify` hashes each base/split and the aggregate revision before `GuestRuntimeEnvironment` creates `PathClassLoader` or the isolated `InMemoryDexClassLoader`. | CAS retains its revision/digest authority; it does not adopt VA's path snapshot or claim VA PRO equivalence. |

## Android contract and current state

Android 14's documented DCL rule applies to apps targeting API 34+: every dynamically loaded
DEX/JAR/APK file must be made read-only before content is written; otherwise loading throws.  For
an existing file, integrity must be checked before relabelling it.  CAS's ordinary Guest path is
not an arbitrary plugin loader: `GuestClassLoader` receives the broker-verified base+split APK
path; the isolated path receives validated FD bytes.  `PackageRevisionSetVerifier` rejects a
missing artifact, digest mismatch, invalid split set, or aggregate-revision mismatch before the
loader is created.  This is appropriate for installed immutable package revisions, but it is not
yet direct API-34+ evidence for caller-supplied DEX/JAR handling.

`GuestResourceLoader.load` adds base then each split to the same `AssetManager`; its separate
base-only manifest manager avoids treating a configuration split's manifest as package metadata.
`GuestClassLoader` creates the platform `PathClassLoader` from `GuestPackageSpec.dexPath()` (base
plus split paths), and its isolated alternative accepts only direct read-only buffers from file
capabilities.  No P1-13 product defect has been inferred from source alone.

## Validation and change boundary

1. First run the existing split fixture as base+feature, inspect base class loading, feature
   component launch, resources, revision metadata and native ABI on API 35/36.  Execute three
   independent lifecycle rounds per available coordinate.
2. Exercise accepted and rejected package sets separately: missing feature, modified split,
   mismatched aggregate revision/version/signature, update, rollback, clear/delete/reinstall and
   two virtual users.  A rejection remains a rejection; no retry or fallback-to-base is allowed.
3. Add a package-neutral DCL probe only to establish the API-34+ read-only positive/negative
   platform result.  It must not load arbitrary network/external code, bypass digest validation,
   or classify every `lib/*.so` container as ELF.  Native DCL beyond the API-36 scope stays with
   P1-15/P2-11.
4. Modify runtime code only if a repeatable fixture result contradicts the verified revision,
   resource, loader, or lifecycle contracts above.  The legacy lifecycle checker currently
   expects pre-operation API spellings; it must be updated to recognize the current structured
   `deleteInstanceWithOperation` stop barrier and complete-artifact import API, not used as a
   reason to change product behavior.

## Explicitly rejected changes

* accepting an incomplete split set, a changed artifact, an unsigned/unknown revision, or a
  dynamic DEX merely because a class happens to load;
* treating every archive entry ending in `.so` as a loadable ELF or attempting to infer a packer;
* broadening native trust, extending timeouts, swallowing failures, or equating VA OSS/README
  behavior with VA PRO evidence.

Sources: [Android 14 safer dynamic code loading](https://developer.android.com/about/versions/14/behavior-changes-14) and [DexClassLoader API reference](https://developer.android.com/reference/dalvik/system/DexClassLoader).
