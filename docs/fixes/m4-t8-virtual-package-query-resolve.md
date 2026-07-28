# M4-T8 — Virtual PackageManager query and Intent resolve expansion

Date: 2026-07-28

## Scope

M4-T8 expands the Binder-owned virtual package state from basic package/component identity into a bounded PackageManager query model. It adds typed Intent-filter metadata, deterministic Guest-local resolve behavior, package/component enabled overrides, install metadata and fail-closed Guest-target handling. Emulator and physical-device validation remain outside this iteration.

## Implemented

### Catalog v5 package and component state

The atomic Catalog now persists per-package/per-virtual-user package and component enabled overrides while remaining able to read schema versions 1–4. Supported values are `DEFAULT`, `ENABLED` and `DISABLED`.

Only the PID/UID-bound management Session can change these values. Guest calls that attempt to mutate package or component enabled state fail closed. Resetting virtual policy clears permission, AppOps, package and component overrides together; deleting an instance removes the complete policy record.

### Typed Intent-filter snapshots

`VirtualIntentDataSnapshot` and `VirtualIntentFilterSnapshot` carry the trusted manifest's:

- filter priority;
- actions and categories;
- scheme and host;
- exact path, path prefix and bounded simple-glob path pattern;
- MIME type.

The typed snapshots are included in `VirtualComponentSnapshot` and transferred from Package Service to the active Guest generation without a business `Bundle`.

### Guest-local resolve model

The virtual PackageManager now implements bounded query/resolve behavior for Activity, Service and Receiver components:

- explicit package and explicit component targeting;
- action matching;
- category containment;
- URI scheme/host/path matching;
- MIME exact and wildcard matching;
- `MATCH_DEFAULT_ONLY`;
- `MATCH_DISABLED_COMPONENTS`;
- deterministic priority, specificity and class-name ordering;
- one result per component when multiple filters match.

Provider metadata supports multi-authority lookup, enabled-state filtering and fail-closed handling when a Guest-owned authority is disabled or unavailable.

### Package query metadata

The virtual package view now exposes:

- version name and version code;
- stable first-install time;
- advancing last-update time;
- a stable virtual installer identity;
- requested permissions when the corresponding query flag is present;
- component arrays according to PackageInfo flags;
- signature-digest comparison for the virtual package;
- declared shared-library names in the revision-bound package snapshot.

The first-install timestamp survives upgrades. The last-update timestamp advances on a successful imported revision.

### Guest-target isolation and bounded fallback

Queries explicitly targeting the Guest package, its components or its Provider authorities are answered entirely by the virtual model. A local no-match or disabled result returns empty/null and does not fall back to the host PackageManager.

Queries that do not explicitly target the Guest retain the existing bounded delegate path for host/system behavior. Full cross-package merging of virtual and host installed-package lists is not implemented.

## Tests and gates

New or expanded host-side evidence includes:

- action/category/data/MIME matching;
- priority and specificity ordering;
- explicit package isolation;
- `MATCH_DEFAULT_ONLY` and `MATCH_DISABLED_COMPONENTS`;
- disabled package and disabled component behavior;
- multi-authority Provider resolution;
- PackageInfo flag handling;
- first-install/last-update metadata;
- virtual installer and shared-library metadata;
- Catalog package/component state persistence and reset;
- typed Parcelable round trips;
- architecture gate `check-package-query-resolve.py`;
- static Android-source compilation of all new AIDL/API surfaces.

## Evidence boundary

This iteration proves source implementation, Catalog transitions, typed Binder transport, host-side query semantics, production entry-point wiring and static Android compilation. It does not prove:

- exact platform `IntentFilter.match()` scoring on Android;
- every PackageManager Binder signature across Android 12–16 or OEM variants;
- package visibility and `<queries>` behavior;
- preferred activities, domain verification or resolver UI behavior;
- system/virtual cross-package list merging;
- `SharedLibraryInfo` resolution or required/optional uses-library enforcement;
- compatibility with any third-party application.

## Next priority

1. Add capability-specific camera, microphone and location service proxies.
2. Add dex/oat cache ownership and revision cleanup.
3. Add SharedLibraryInfo resolution and required/optional uses-library enforcement.
4. Expand PackageManager visibility and cross-package query policy.
5. Split `RuntimeBrokerService` responsibilities before further system-service expansion.
