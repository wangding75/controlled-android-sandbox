# R1 Persistence and Virtual UID Fix

## Scope

- H-02 package metadata corruption fail-open
- H-03 deterministic virtual UID collisions
- M-05 instance metadata corruption fail-open

## Changes

- Replaced silent JSON parse fallback with a recoverable primary/last-known-good store.
- Corrupt primary metadata is restored only from a successfully decoded backup.
- Corrupt primary and backup fail closed with `PersistentStateException`.
- Package replacement revalidates the installed APK package name, version, signing digest, path, and SHA-256.
- Replaced package-name hash UID generation with a persistent unique package-to-appId registry.
- Virtual UID registry allocation is transactional, stable after reload, and fails closed on corruption or exhaustion.
- UI disables import and package operations when trusted metadata cannot be loaded.

## Verification

- Full Android application appId range: 90,000 unique assignments with no collisions.
- UID persistence and backup recovery tests.
- Recoverable file primary repair and dual-corruption fail-closed tests.
- Full repository verification passed three times after the final fix.

## Remaining boundaries

- Android APK build and device storage failure injection remain blocked by the unavailable Android toolchain.
