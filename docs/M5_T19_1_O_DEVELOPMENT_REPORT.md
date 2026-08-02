# M5-T19.1-O Stable Caller UID Identity

- Finding: P2-08 `PackageCallerVerifier` depended on `ActivityManager.getRunningAppProcesses()`, which can return incomplete or unavailable process data on modern Android and OEM devices.
- Baseline: `405b9690fd8b4cc12c79abbbd0d01abdbdb5be21`.
- Scope: package-service root capability authorization only; session owner PID guards remain unchanged.

## Implemented behavior

- Host management capabilities require the Host UID and the exact main-process name read from the caller PID `/proc/<pid>/cmdline`.
- Runtime capabilities require the exact Host `:sandbox_server` process or the exact Companion `:sandbox_server32` process, plus UID/package/signature checks.
- Companion package visibility is declared explicitly in the Host manifest.
- Host, Runtime and Companion package/permission constants are sourced from the stable `sandbox-contract` `RuntimePeerIdentity`.
- AMS process-list enumeration is removed; only the exact Binder caller PID is inspected with a bounded 512-byte read.
- Session objects still bind the minted capability to the exact calling UID/PID, so another process cannot reuse an existing session Binder.

## Security boundary

Same-UID Guest processes remain rejected from root management and Runtime capability minting. Failure to read the exact caller PID identity fails closed.

## Evidence boundary

Host API stubs verify UID/package policy and fail-closed cases. Android package visibility, PackageManager UID resolution, Binder Driver identity, emulator, and physical-device behavior are not claimed.
