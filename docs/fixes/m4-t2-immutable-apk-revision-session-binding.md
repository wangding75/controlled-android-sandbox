# M4-T2 — immutable APK revision and Runtime Session binding

## Scope

This iteration prevents an active Guest process Session from being reused after the imported APK bytes change. It is a source-only hardening iteration; Android build, Emulator and physical-device execution remain deferred.

## Implemented

- Bumped the internal Broker/Guest Runtime protocol from version 2 to version 3 because APK identity fields are now mandatory.
- Added mandatory request fields for APK SHA-256 and version code.
- Added a canonical package revision format: `v<versionCode>:sha256:<digest>`.
- Added `ApkRevisionVerifier`:
  - validates the expected digest format;
  - computes SHA-256 from the app-private APK file;
  - compares digests with `MessageDigest.isEqual`;
  - rejects changed bytes with `APK_SHA256_MISMATCH`.
- The Broker derives the authoritative package revision after verifying the file; callers cannot provide the final revision identity.
- `GuestSession` now stores an immutable package revision and preserves it across state transitions and generation recovery.
- `SessionRegistry` rejects an attempt to reuse a live process Session with a different package revision.
- Before preparing any process for an upgraded package, the Broker stops all live process Sessions belonging to the same virtual instance but a different revision. This avoids mixed old/new process state inside one virtual application instance.
- Cached prepared specifications are checked against the Session revision before `ALREADY_PREPARED` is returned.
- The Guest process verifies the APK bytes again immediately before class/resource loading and requires the revision to match the Broker-derived value.
- Revision-specific code-cache directories prevent old optimized code paths from being reused across APK revisions.
- Session status now exposes the package revision for diagnostics.

## Verification

- Domain Session registry test covers same-revision reuse and different-revision rejection.
- Session revision policy test covers matching and mismatched live-process selection.
- `ApkRevisionVerifierSelfTest` covers:
  - digest normalization;
  - canonical revision construction;
  - rejection after the APK file bytes change.
- `scripts/check-apk-revision-binding.py` prevents removal of Broker, Session, Guest and client-side revision controls.
- The complete host verification gate executes these checks.

## Evidence and limits

SHA-256 is the authoritative byte identity. The version code is retained as diagnostic/package metadata but is not independently re-parsed from the APK during this source-only iteration.

The Broker and Guest both hash the APK, reducing stale-file reuse and narrowing the replacement race. A same-UID host writer could theoretically change the file after the final Guest verification and before the platform finishes opening all APK resources. The next package-lifecycle transaction iteration must publish immutable revision files rather than overwriting an active path.

## Next priority

1. Make import, upgrade and delete operations transaction-safe.
2. Store APKs by immutable revision path rather than replacing one package path in place.
3. Add recovery for interrupted package lifecycle transactions.
4. Add privileged-control authorization beyond same-UID caller validation.
