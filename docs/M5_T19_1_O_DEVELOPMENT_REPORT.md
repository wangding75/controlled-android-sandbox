# M5-T19.1-O Stable Caller UID Identity

- Finding: P2-08 `PackageCallerVerifier` depended on `ActivityManager.getRunningAppProcesses()`, which can return incomplete or unavailable process data on modern Android and OEM devices.
- Baseline: `405b9690fd8b4cc12c79abbbd0d01abdbdb5be21`.
- Scope: package-service root capability authorization only; session owner PID guards remain unchanged.

## Implemented behavior

- Host package-service capabilities are authorized by the Host application UID and a valid Binder calling PID.
- Runtime capabilities accept the Host application UID or an installed Companion release/debug package UID that also holds the signature permission.
- Companion package visibility is declared explicitly in the Host manifest.
- AMS process-list and caller process-name checks are removed.
- Session objects still bind the minted capability to the exact calling UID/PID, so another process cannot reuse an existing session Binder.

## Security boundary

Android processes sharing one application UID are not treated as independent OS security principals. This change removes an unreliable process-name gate instead of claiming process-name isolation. Guest-to-Host API restrictions remain enforced by the runtime/framework boundary and per-session capabilities.

## Evidence boundary

Host API stubs verify UID/package policy and fail-closed cases. Android package visibility, PackageManager UID resolution, Binder Driver identity, emulator, and physical-device behavior are not claimed.
