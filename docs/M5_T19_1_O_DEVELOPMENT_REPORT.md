# M5-T19.1-O2 Package Authority Binder Capabilities

- Reopened finding: `NEW-P1-02` / original P2-08.
- Development baseline: `0a2c1ae33edca7d17af69b2c8b2cf29612f939b9`.
- Scope: Package Service root-role bootstrap and all management/runtime capability entry points.

## Problem

The previous remediation replaced `ActivityManager.getRunningAppProcesses()` with the exact Binder caller PID read from `/proc/<pid>/cmdline`. That improved availability but still treated a mutable process label as a role credential. A process name is suitable for diagnostics and routing evidence; it is not an unforgeable authorization primitive.

## Implemented behavior

- Existing `IPackageService` methods retain transaction positions 1-5 and now fail closed with an explicit role-capability-required error.
- Capability-aware registration and operation methods are appended after the legacy AIDL methods, preserving old transaction IDs.
- Host management and Runtime roles use separate process-owned Binder tokens and generations.
- Package Service records the registering Binder caller UID/PID, role, token and generation.
- A live role cannot be replaced by another PID or Binder token.
- The role token is linked to Binder death; death retires the role registration.
- Every management, runtime-permission, virtual-system-service and virtual-Job operation revalidates the role token and generation.
- Companion Runtime registration additionally requires the signature permission and an installed, UID-matching Companion package.
- `/proc/<pid>/cmdline`, ActivityManager process enumeration and process-name comparison are absent from authorization code.

## Startup and recovery invariant

The Host registers the management role before Guest launch. The trusted Runtime Broker registers the runtime role before requesting a scoped Package Service capability. Package Service restart invalidates its in-memory role slots; trusted clients re-register their still process-owned token before reopening sessions. Existing sessions are not accepted without a live matching authority slot.

## Security boundary

The change removes process-label spoofing and prevents a second process from replacing or reusing an already live role capability. It does not create a hostile-code boundary between processes that share the application Linux UID. Initial same-UID registration relies on trusted startup ordering; hostile Guest code must use the isolated-UID execution route.

## Verification

`PackageManagementAuthorizationSelfTest` covers:

- same-UID, different-PID token replacement rejection;
- token and generation mismatch rejection;
- Binder death revocation;
- Companion package/signature fail-closed behavior;
- legacy root entry points failing closed.

The static Android Host compile suite and the M5-T19.1-O caller-identity gate compile and execute the capability-aware paths. Emulator and physical-device Binder identity behavior remain part of the final Android validation phase.
