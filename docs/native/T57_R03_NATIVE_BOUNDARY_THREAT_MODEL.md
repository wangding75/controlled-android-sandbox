# T57-R03 Native Boundary Threat Model

Campaign: `T57-R03-P0A-01`
Capability: `native_loader_jni_io`
Maturity: `RD_BASELINE_NATIVE_DISCOVERY`
VA Pro equivalent: `NOT_PROVEN`

This document freezes the Native trust model before any additional production
enforcement is written. It is a discovery/design artifact. It does not authorize
more PLT/GOT hooks as a hostile-code security proof.

## Security modes

### TRUSTED_COMPAT

Purpose: compatibility for ordinary App / SDK Native behavior.

Allowed mechanisms:

- PLT/GOT interception
- libc wrapper interception
- linker symbol rebinding
- path projection
- procfs projection
- JNI / Native loader compatibility
- selected system-module rebinding (`libandroid_runtime`, `libopenjdk*`, `libjavacore`)

Goal: let a normal Native application run correctly inside the virtual environment.

This mode **must not** be claimed to constrain hostile Native code that
deliberately bypasses userspace hooks.

Current CAS Native implementation lives in this mode only.

### ISOLATED_HOSTILE

Purpose: guests that may actively bypass userspace hooks.

Surfaces that must be considered:

- direct `syscall()`
- inline assembly
- raw syscall instruction / raw SVC
- custom loader
- direct Binder device access
- procfs probing
- ptrace
- execve
- FD escape
- mmap / dlopen
- process / thread enumeration
- network syscall bypass
- filesystem syscall bypass

This mode finally requires a **provable enforcement boundary**.

PLT/GOT hooking is **not** an `ISOLATED_HOSTILE` security boundary.

## Assets

| Asset | Why it matters | Current Native control |
|---|---|---|
| Guest filesystem identity | Guest must see virtual `/data/data/<pkg>` and APK/lib paths | Path projection through hooked `open*` / `stat*` / `readlink*` |
| Guest package identity | Package, UID, PID, process name must not leak host values | Virtual UID/PID exist in policy and some procfs text, not in `getuid`/`getpid` |
| Host path privacy | Host private files, other guests, instance roots | Hooked path resolver confinement; raw syscall bypasses it |
| Host process identity | Host PID/UID/cmdline/maps must not identify the sandbox | Partial procfs snapshots; identity syscalls not intercepted |
| Host package visibility | Host installed packages / maps paths | maps sanitization on hooked open; unhooked open sees host maps |
| Network identity | Bind/connect/DNS must not expose host addressing | Hooked socket family; raw network syscalls bypass policy |
| Binder / system capability | Guest must not talk to host Binder driver as the host app | Java framework hooks only; `/dev/binder*` not mediated |
| Cross-guest isolation | One guest must not read another guest's data or FDs | Path policy when hooks apply; FD/procfs escape remains |

## Attackers

| Attacker | Intent | Relevant mode |
|---|---|---|
| Normal native library | File/JNI/network compatibility | TRUSTED_COMPAT |
| Native SDK | Stable libc + loader behavior | TRUSTED_COMPAT |
| Hardened commercial app | Anti-virtualization / environment checks | TRUSTED_COMPAT pressure; some probes already look like hostile |
| Anti-virtualization SDK | `/proc`, maps, Binder, syscall fingerprints | Mixed; often exceeds TRUSTED_COMPAT |
| Intentionally hostile guest native code | Bypass hooks, escape FS/net/Binder, ptrace, exec | ISOLATED_HOSTILE |

The last row is the intentionally hostile attacker class.

## Trust boundaries

```text
Java guest
    │ JNI
    ▼
guest native library
    │ imported libc / libdl symbols   ← current CAS PLT/GOT sits here
    ▼
libc / bionic
    │ syscall() PLT                   ← mediated only if that slot is patched
    ▼
dynamic linker
    │ custom loader / mmap+jump       ← not mediated
    ▼
kernel syscall boundary               ← no CAS seccomp / user-notify
    │
    ├── Binder driver (/dev/binder*)  ← not mediated in Native
    ├── Host / Broker process         ← Java/Binder control plane
    └── isolated guest process        ← exists as a slot; not a Native supervisor
```

Current enforcement is **above** the kernel syscall boundary and **inside**
guest-root ELF imports. Anything that does not take those imports is outside
the current control.

## Bypass classes

| Class | Current status | Notes |
|---|---|---|
| libc wrapper | INTERCEPTED for 74 imported symbols | Compatibility only |
| `syscall()` | PARTIAL | Guest PLT `syscall` is rewritten to `controlled_syscall`; unknown numbers forwarded |
| `__syscall` | NOT_INTERCEPTED | Bionic private stubs are not rebound |
| raw SVC / `syscall` instruction | NOT_INTERCEPTED | Userspace PLT cannot see it |
| custom loader | NOT_INTERCEPTED | mmap + hand-rolled ELF / anonymous JIT |
| FD reuse / escape | PARTIAL | `dup*`/`fcntl` tracked for sockets/capability FDs; `/proc/self/fd` not virtualized |
| `/proc` enumeration | PARTIAL | Known leaves only (`maps`, `cmdline`, `status`, `mountinfo`, `stat`, `statm`, `io`) |
| ptrace | NOT_INTERCEPTED | Synthetic `TracerPid: 0` is text only |
| execve | NOT_INTERCEPTED | Process image / identity can change |
| direct network syscall | PARTIAL | Mediated only through patched `syscall()` or libc |
| Binder driver access | NOT_INTERCEPTED | `/dev/binder`, `/dev/vndbinder`, `/dev/hwbinder` |
| translated-ABI guest | NOT_APPLICABLE to PLT | `GuestRuntimeEnvironment` skips PLT install when `translatedGuestAbi` |

## Security statement

Current T57 Native PLT/GOT / symbol rebinding can only prove
**TRUSTED_COMPAT** compatibility interception.

It cannot prove **ISOLATED_HOSTILE** enforcement.

Evidence already in-tree:

- `sandbox-native/src/main/cpp/include/controlled_sandbox/native_hook.h`
  states this is not a security boundary.
- `controlled_syscall` comments state raw SVC remains outside the PLT.
- `native_syscall_boundary_self_test.cpp` **asserts** that unhooked
  `syscall(SYS_openat/SYS_connect/SYS_sendto)` reaches host resources.
- `validate_campaign_infra.py` forbids `native_loader_jni_io.implementation_status=PASS`.

Adding more libc symbols does not convert this statement into a hostile-code
proof. Kernel, supervisor, or privileged mediation is required for
`ISOLATED_HOSTILE`.

## C3-T04 RD_BASELINE residual kernel limits

The C3-T04 isolated-UID + Broker + deny-only seccomp campaign on MuMu `RD测试`
API32 proved: isolated UID assignment; kernel denial of CAS core storage,
other-Guest secrets, and ungranted Host paths; Broker grant/scope/revision/
expiry/replay; inherited FD Host-private leak count 0; ptrace/execve/socket
denied by the hostile worker filter; isolated PID gone and tokens revoked
after unbind.

Residual kernel limits that remain explicit, not isolation PASS:

| Surface | C3-T04 status | Why it remains |
|---|---|---|
| `/dev/binder` open | `KERNEL_LIMIT_EXPOSED` | no Native driver mediation (`KI-R03-NATIVE-006`) |
| `fork`/`clone` | `KERNEL_LIMIT_EXPOSED_SAME_UID` | ART/threads need `clone`; UID does not change |
| seccomp user-notify | not implemented | ordinary APK (`KI-R03-NATIVE-008`, C3-T05) |
| isolated UID without hostile filter | network not a boundary | `KI-R03-NATIVE-ENF-001` |
| raw SVC in TRUSTED_COMPAT | unmediated | `KI-R03-NATIVE-001`; hostile path does not use PLT |

This section is RD_BASELINE only. It is not VA Pro equivalence.
