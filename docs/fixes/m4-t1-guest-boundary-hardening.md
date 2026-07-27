# M4-T1 — Guest Context and class-loading boundary hardening

## Scope

This iteration reduces accidental Guest access to host implementation classes and host-owned storage surfaces. It remains a source-only iteration: Emulator and physical-device execution are deliberately deferred.

## Implemented

- Changed `GuestClassLoader` from selected host namespaces being parent-first to an explicit deny-by-default rule for all `com.warden.controlledsandbox.*` implementation classes.
- Preserved only `com.warden.controlledsandbox.contract.*` as a parent-visible stable Binder contract surface.
- Removed Runtime, Framework and Native bridge implementation packages from the Guest parent-first policy.
- Made `GuestContext.getBaseContext()` terminate at the Guest wrapper instead of returning the host Context.
- Redirected the principal application-private Context storage APIs to the virtual-instance root:
  - data, files, cache, code cache and no-backup directories;
  - databases and SharedPreferences;
  - app-created directories;
  - external files/cache, OBB and media directories.
- Made package and split Context creation fail closed when the requested package/split is outside the active Guest package.
- Kept configuration and credential-protected Context derivation inside the Guest Context.
- Made device-protected Guest storage explicitly fail closed until a real per-instance implementation exists.
- Returned a defensive copy from `getApplicationInfo()`.
- Added executable Guest Context and class-loader boundary tests.
- Added a source gate that prevents these boundary controls from being silently removed in later iterations.

## Verification

The host-side static Android compilation harness executes both boundary suites:

- `GuestClassLoaderSelfTest`
- `GuestContextBoundarySelfTest`

The repository-wide gate additionally checks the required source controls with `scripts/check-guest-boundary.py`.

## Security interpretation

This change blocks ordinary Guest code paths that use the assigned Guest `ClassLoader` and standard `Context` APIs. It does not create a hostile-code security boundary.

Guest code still executes inside a process owned by the host application and therefore shares the host Linux UID. Deliberate code may attempt reflection, native calls, alternate/system class-loader access, Binder abuse or exploitation of unvirtualized Android services. Those risks require process/UID isolation and broader framework mediation; a Java wrapper alone cannot solve them.

Until that architecture exists, the project must continue to reject the claim that untrusted or malicious APKs are safely isolated.

## Remaining Context gaps

- SQLite helper/open-database behavior must be tested against the redirected database path on Android.
- `ContentResolver`, account, clipboard, notification, job, alarm and other system-service identities require continued virtualization.
- Device-protected storage is intentionally unsupported.
- External/shared-storage behavior needs policy enforcement beyond returning virtual paths.
- Same-UID management Binder surfaces still require an authority model stronger than caller UID checks.

## Next priority

1. Bind every Runtime Session to an immutable APK revision.
2. Make install, upgrade and delete operations transaction-safe.
3. Introduce an explicit privileged control authority for Broker and Native-policy operations.
4. Continue PackageManager and system-service virtualization.
