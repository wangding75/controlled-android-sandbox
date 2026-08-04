# M5-T19.1-O2 Package Authority Binder Capabilities

- Reopened finding: `NEW-P1-02` / original P2-08.
- Development baseline: `0a2c1ae33edca7d17af69b2c8b2cf29612f939b9`.
- Scope: Package Service root-role bootstrap and all management/runtime capability entry points.

## Problem

The previous remediation replaced `ActivityManager.getRunningAppProcesses()` with the exact Binder caller PID read from `/proc/<pid>/cmdline`. That improved availability but still treated a mutable process label as a role credential. A process name is suitable for diagnostics and routing evidence; it is not an unforgeable authorization primitive.

## Implemented behavior

- Existing `IPackageService` transactions retain their positions and public role-registration/root-operation methods fail closed.
- Package Service actively binds two fixed, non-exported endpoints: Host management and trusted Runtime bootstrap.
- Each endpoint returns a process-owned Binder token and its actual process PID.
- Package Service allocates the role epoch; clients cannot submit an arbitrary generation or pin recovery with `Long.MAX_VALUE`.
- Installed roles are fixed to token, UID and endpoint PID and linked to Binder death.
- Every management, runtime-permission, virtual-system-service and virtual-Job operation revalidates role token, UID, PID and server epoch.
- A same-UID Guest cannot claim a role by calling a public registration method and cannot use an endpoint token from another PID.
- Bootstrap reconnection uses bounded exponential delay, jitter and a circuit-open interval instead of an immediate unbounded main-thread loop.
- `/proc/<pid>/cmdline`, ActivityManager process enumeration and process-name comparison are absent from authorization code.

## Startup and recovery invariant

Package Service initiates outbound bindings to the Host and Runtime endpoints. Privileged sessions remain unavailable until their fixed endpoint has supplied a live token and PID. Package Service restart invalidates all in-memory slots and repeats the private bootstrap. Endpoint death revokes the matching role and schedules bounded recovery; existing scoped sessions are not accepted without the current live role and server epoch.

## Guest-to-Host boundary

`GuestContext` does not retain the Host Context as its `ContextWrapper` base. Unhandled future Android overloads therefore fail closed instead of automatically delegating to Host. Current Service, Broadcast, Receiver, Activity, permission and URI-grant overloads are explicitly denied. Framework hooks may use a private Host service transport only during installation. Before Guest `Application` creation, the service boundary is sealed to the installed-hook report. Missing core Notification, Job, Alarm, Clipboard, Account or Storage hooks block launch, and any known failed hook is denied instead of returning the Host manager.

## Security boundary

The change removes process-label authorization, public same-UID bootstrap races and client-controlled authority generations. It still does not turn processes sharing one application Linux UID into a kernel-enforced hostile-code boundary. Untrusted Native payloads and workloads that require strong process identity separation must use isolated UID execution.

## Verification

`PackageManagementAuthorizationSelfTest` covers:

- same-UID, different-PID token replacement rejection;
- token and generation mismatch rejection;
- Binder death revocation;
- Companion package/signature fail-closed behavior;
- legacy root entry points failing closed.

The static Android Host compile suite and the M5-T19.1-O caller-identity gate compile and execute the capability-aware paths. Emulator and physical-device Binder identity behavior remain part of the final Android validation phase.
