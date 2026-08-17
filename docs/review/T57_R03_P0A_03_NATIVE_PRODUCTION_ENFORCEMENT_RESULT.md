# T57-R03-P0A-03 Native Production Isolated-Hostile Enforcement

RESULT: PASS

Maturity: `RD_BASELINE_NATIVE_ENFORCEMENT`
VA Pro equivalent: `NOT_PROVEN`

## Git

- start HEAD: `d06f6cafe20543dcd3953695426e8fec02aae523`
- branch: `feature/t57-r03-va-pro-capability-campaign`
- campaign commit: see `git log -1` after this task's commit

## What shipped

Production typed contract:

- `NativeExecutionProfile` = `TRUSTED_COMPAT` | `ISOLATED_HOSTILE`
- Distinct from `NativeGuestPolicyContract.executionMode` (PLT applicability)
- `HostileAdmissionSnapshot`, `HostileCapabilityRequest/Result/Snapshot`
- `IHostileCapabilityBroker`

Production Broker:

- `HostileCapabilityRegistry` — opaque tokens bound to package/user/generation/session
- Rejects stale generation, wrong owner/user, revoked/expired tokens
- Network allowlist is loopback-only for this version
- Revoke stops new use; already-delegated kernel FDs are not remotely unmapped

Production seccomp:

- `hostile_seccomp.cpp` + `NativePolicy.installHostileSeccomp`
- Installed only in isolated workers when profile is `ISOLATED_HOSTILE`
- Deny: socket/connect/bind/sendto/sendmsg/socketcall/ptrace/execve/execveat
- Action: `SECCOMP_RET_ERRNO`
- Unknown syscalls: ALLOW

Isolated guest worker (`BaseIsolatedGuestProcessService`) installs the filter after
`ISOLATED_UID` is proven.

## RD测试

Session: `artifacts/capability-audit/native-enforcement/20260817T081311Z`

- instance `RD测试`, serial dynamically `127.0.0.1:16416`, API 32, ABI x86_64 executed
- host UID 10193 / isolated UID 99033

| Case | Result |
|---|---|
| FS libc/syscall/raw | DENIED_BY_KERNEL_POLICY |
| Broker FS | PASS_CAPABILITY |
| FS guess/mismatch | DENIED |
| Net libc/syscall/raw | DIRECT_DENIED_BY_SECCOMP |
| Broker net | PASS_CAPABILITY |
| FD delegation | PASS_FD |
| Stale generation | DENIED |
| Seccomp x86_64 / x86 | FEASIBLE |
| arm64 runtime | UNVERIFIED_RUNTIME |

TRUSTED_COMPAT PLT path was not removed. Default guests stay compatibility mode.

## Local audit

`python tools/capability/run_local_capability_audit.py --campaign native`

NEW_REGRESSION=0. M10 Known Issues unchanged.

## Binder driver

`/dev/binder*` is still openable. Ordinary APK cannot mediate the Binder driver.
Broker RPC is preserved. Recorded PARTIAL for driver-level Binder.

## Next

P0A-04 Native RD baseline closure. Not VA Pro Equivalent.
