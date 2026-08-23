# C3-T05 ADR: seccomp user-notify is not an ordinary-APK product feature

Status: `NOT_APPLICABLE` for production CAS on an ordinary APK.
Date: 2026-08-23
Depends: C3-T04 isolated-hostile boundary
Maturity: `RD_BASELINE`
VA Pro equivalent: `NOT_PROVEN`

## Decision

Do **not** implement seccomp user-notify (`SECCOMP_RET_USER_NOTIF` /
`SECCOMP_FILTER_FLAG_NEW_LISTENER`) in the production Guest or Host Zygote
child.

The product hostile boundary remains C3-T04:

1. isolated UID / process (Option B)
2. Broker-only capability ledger
3. process-local classic BPF **deny/errno** filter in the isolated hostile
   worker (Option C, scoped, not KILL)

User-notify (Option D) and privileged companion (Option E) stay labeled
`REQUIRES_PRIVILEGE`. They are valid OEM/managed SKUs, not the default APK.

## Why

| Probe | Result on dynamically resolved MuMu `RD测试` |
|---|---|
| Kernel | Linux `5.4.32-perf-gda349bfae95e` (uname aarch64 / API32) |
| `CONFIG_SECCOMP` | `y` |
| `CONFIG_SECCOMP_FILTER` | `y` |
| `CONFIG_SECCOMP_USER_NOTIF` | **absent** from `/proc/config.gz` |
| Classic BPF deny-only | Feasible: P0A-02 `SECCOMP_FILTER_FEASIBLE`; C3-T04 ptrace/execve/socket `DENIED_BY_SECCOMP` |
| Listener ownership | Even on kernels that compile user-notify, supervising another process and owning the listener FD is not a reliable ordinary-APK power |
| SELinux | this RD image reports `Permissive`; Enforcing OEM images are unverified |

Linux 5.4 *can* implement user-notify, but this target kernel did not enable
it. A userspace installer would get `EINVAL`/`ENOSYS` and must not silently
fall back to PLT.

VA commercial rows `VA-555` / `VA-601` / `VA-676` remain compatibility
signals, not a CAS license or implementation.

## Alternatives compared

| Option | Ordinary APK | C3-T05 conclusion |
|---|---|---|
| A PLT/GOT | Yes | TRUSTED_COMPAT only |
| B isolated + Broker | Yes | **product hostile FS/capability boundary** (C3-T04) |
| C classic BPF deny/errno | Isolated worker only | **hardening in C3-T04**; not a Zygote-child default |
| D user-notify supervisor | No | `NOT_APPLICABLE` / `REQUIRES_PRIVILEGE` |
| E privileged companion / OEM image | No | future SKU, not this campaign |

## Production impact

- No production `SECCOMP_RET_USER_NOTIF` installer.
- No fake “user-notify enabled” switch.
- `HostileSeccompInstaller` remains isolated-hostile deny-only BPF.
- `KI-R03-NATIVE-008` stays recorded as the unimplemented privileged path.
- C3-T04 residuals (`/dev/binder` observation, same-UID `clone`) are not
  closed by this decision.

## Recovery / later SKU

A privileged companion or OEM kernel with `CONFIG_SECCOMP_USER_NOTIF=y`, a
trusted listener owner outside the Guest, gated SCM_RIGHTS, and SELinux
policy may revisit Option D. That requires a new product decision and must
not rewrite this ADR silently.
