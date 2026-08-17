# T57-R03 Native Enforcement Architecture Options

Campaign: `T57-R03-P0A-01`
Scope: design only. No production Native enforcement change in this campaign.

Commercial VA Pro changelog rows (`VA-555` … `VA-709`) are
`commercial_release_signal` only. They are not CAS implementation evidence and
are not used as a source-code specification.

## Option A — Continue PLT/GOT only

What it is: keep walking guest ELFs with `dl_iterate_phdr`, rewrite
`R_*_JUMP_SLOT` / `R_*_GLOB_DAT` for a symbol table, and mediate selected
`syscall()` numbers from that PLT slot.

### Advantages

- Compatibility: this is how current RD API32 Native guests already run.
- Implementation cost: incremental. CAS already has 74 target symbols, path
  projection, procfs snapshots, loader policy, and socket policy.
- App friendliness: ordinary NDK/libc/JNI continues to work. No extra UID,
  no extra seccomp crash surface, no Zygote-filter fight.

### Disadvantages

- Raw syscall instruction / raw SVC / `__syscall` / custom loader bypass the
  entire table.
- Translated-ABI guests already skip PLT install
  (`GuestRuntimeEnvironment`, `translatedGuestAbi`).
- Isolated processes skip guest PLT install.
- Hostile-code boundary does not exist.

### Verdict

Necessary for `TRUSTED_COMPAT`. **Cannot be the final `ISOLATED_HOSTILE`
solution.** Further production hook expansion is not a security proof.

## Option B — Isolated UID/process + Broker-only capability

What it is: run untrusted Native in a process that has no useful host
authority. All filesystem, network, and Binder capability arrives only as
Broker-issued FDs or Binder transactions.

CAS already has pieces:

- isolated process slots (production contract 64 ordinary + 16 isolated)
- `NativePolicy.configureFileCapabilities` (Binder-transferred directory FDs)
- Host / Broker Java control plane
- explicit Native trust admission (`EXPLICITLY_TRUSTED`)

### Evaluation

| Topic | Assessment |
|---|---|
| Filesystem broker | Feasible without root: guest sees only capability FDs + projected paths. Raw `openat` of host paths still works **unless** the process also lacks DAC/SELinux rights to those paths. |
| Network broker | Feasible: deny `INTERNET` / give a brokered socket. Raw `socket`/`connect` still works if the UID has the permission. |
| Binder broker | Feasible at Java/framework layer; Native `/dev/binder` remains if the node is reachable. |
| FD ownership | Already started. Must treat `/proc/self/fd`, `dup*`, and inherited FDs as first-class. |
| Cross-process transport cost | High for chatty FS/net. Acceptable for hostile / untrusted Native, not for every commercial game. |
| Process lifecycle | Isolated process + generation/recovery already exist; Native abort/SIGSEGV matrix is still open (`process_death_recovery`). |
| Android app sandbox constraints | Ordinary APK **cannot** create a true secondary Linux UID. Virtual UID is a policy number, not a kernel UID. Same-UID isolation is therefore DAC-weak. |

### Verdict

Best **product-feasible** `ISOLATED_HOSTILE` direction on a non-root APK:
combine process isolation with **removing** host filesystem/network rights from
that process, not with more PLT hooks. Same-UID remains a kernel limit.

## Option C — seccomp-BPF

What it is: install an additional seccomp filter in a dedicated process that
returns `ERRNO`/`KILL`/`TRAP` for disallowed syscalls.

### Analysis

| Topic | Assessment |
|---|---|
| Can an Android app process install an extra filter? | Often yes as a **more restrictive** stacked filter after Zygote. SELinux / vendor policy may still deny `seccomp()`. |
| `no_new_privs` | Zygote typically already set it. Additional filters are still allowed; they cannot regain privilege. |
| Kernel / API differences | Filter ABI, audit, and available syscalls differ across API 32–36 and OEM kernels. |
| Zygote inherited filter | Already present. New filter is ANDed. A CAS filter cannot **relax** Zygote. |
| ABI differences | x86_64 / arm64 / x86 / armeabi-v7a syscall numbers and socketcall multiplexing differ. One filter is not portable. |
| Crash / recovery | A too-tight filter kills ART, binder ioctl, futex, clone, or `restart_syscall`. Recovery is process death, not a soft error. |
| Signal handling | `SECCOMP_RET_TRAP` needs a dedicated SIGSYS handler. Easy to deadlock if the handler itself faults. |
| Compatibility risk | High in the host/guest Zygote child. Unacceptable as a default for `TRUSTED_COMPAT`. |

This campaign must not enable seccomp in production runtime.

A POC is allowed only in a dedicated test fixture process.

### Verdict

Useful **hardening layer** for an already-isolated hostile process. Not a
drop-in replacement for PLT compatibility. Not production in P0A-01.

## Option D — seccomp user notification / supervisor

What it is: `SECCOMP_FILTER_FLAG_NEW_LISTENER` + a supervisor that inspects
and emulates syscalls (VA commercial signal `VA-555` / `VA-601` / `VA-676`).

### Analysis

| Topic | Assessment |
|---|---|
| Android kernel availability | `SECCOMP_RET_USER_NOTIF` needs Linux ≥ 5.0. Some API32 images have it; OEM kernels vary. |
| App privilege | Creating a listener FD from the **same** process is sometimes possible. Supervising **another** process, installing the first filter, or punching through Zygote's existing filter is commonly blocked. |
| FD transfer | Listener FD must be owned by a trusted supervisor. Same-UID guests can steal or race that FD unless SCM_RIGHTS is tightly gated. |
| Listener ownership | Must not live in the hostile guest. Needs Broker / companion / privileged process. |
| Feasibility without root / platform privilege | **Not a reliable ordinary-APK feature.** Treat as `REQUIRES_PRIVILEGE` until a fixture POC proves otherwise on a named kernel. |

### Verdict

`REQUIRES_PRIVILEGE` for any product claim. Do not design a fake
user-notify switch that silently no-ops on ordinary APKs.

## Option E — privileged companion / platform integration

What it is: if true hostile mediation needs a privileged process, device
owner, root, system signature, or a kernel feature, that is a **product
deployment boundary**, not a hidden APK capability.

### Analysis

A privileged companion could:

- install seccomp user-notify for the guest
- run the guest under a real secondary UID / isolated mount namespace
- mediate Binder at the driver
- apply SELinux domains

None of those are ordinary Play-distributed APK powers.

### Verdict

Valid deployment option for a managed / OEM / lab build. Must be labeled as
privileged. Must not be advertised as a default CAS APK capability.

## Comparison

| Option | TRUSTED_COMPAT | ISOLATED_HOSTILE | Ordinary APK | Privilege |
|---|---|---|---|---|
| A PLT/GOT | Yes | No | Yes | No |
| B Isolated process + broker | Partial | Partial (DAC/SELinux limited) | Yes | No |
| C seccomp-BPF | Harmful if default | Hardening only | Maybe, fixture-only until proven | Usually no |
| D user-notify | No | Strongest userspace kernel mediation | No | `REQUIRES_PRIVILEGE` |
| E privileged companion | N/A | Strongest overall | No | Yes — deployment boundary |

## What this campaign will not do

- Add more production PLT symbols to “close” raw SVC.
- Enable seccomp in `sandbox-native` / guest Zygote children.
- Pretend VA Pro seccomp changelog rows are implemented.
- Delete 32-bit ABI to hide CXX5202.
