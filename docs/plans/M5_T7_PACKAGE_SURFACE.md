# M5-T7 — PackageManager, PackageInstaller, Shared Library and Instrumentation expansion

## Goal

Expand the repository-owned package surface without claiming Android device parity. The stage covers:

1. typed required/optional Java/native/SDK/static shared-library declarations and deterministic resolution;
2. manifest instrumentation declarations and PackageManager query projection;
3. typed PackageInstaller session parameters, status/progress, bounded failure history and retry state;
4. PackageManager query breadth for instrumentation, shared libraries and signing metadata;
5. clean-room comparison against the vendored VA/NBB reference snapshots.

## Source boundary

`ref/upstream/**` is read-only reference material. Product code is independently authored and does not import, compile or package reference sources.

## Device boundary

Host/static tests may prove parsing, contracts, persistence, routing and fail-closed behavior. Real Android PackageInstaller callbacks, framework object constructors, platform shared-library availability, ART loading and instrumentation execution remain device-deferred.
