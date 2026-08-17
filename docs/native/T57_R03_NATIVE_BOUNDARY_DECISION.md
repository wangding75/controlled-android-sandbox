# T57-R03 Native Boundary Architecture Decision

Campaign: `T57-R03-P0A-01`
Decision date: 2026-08-17
Status: ACCEPTED for the next implementation campaign
Maturity: `RD_BASELINE_NATIVE_DISCOVERY`
VA Pro equivalent: `NOT_PROVEN`

## 1. Recommended architecture

**Dual-track. Do not expand PLT/GOT as a hostile-code proof.**

1. **Default guest mode = `TRUSTED_COMPAT` / Option A.**
   Keep the current PLT/GOT + selected `syscall()` dispatcher + path/procfs
   projection + loader policy. This is the commercial-app compatibility path.

2. **Hostile / untrusted Native mode = `ISOLATED_HOSTILE` / Option B first.**
   Run that guest in an isolated process that has no useful host filesystem
   or network rights. All capability arrives from the Broker as FDs or Binder
   transactions.

3. **Optional hardening = Option C, fixture-only until proven.**
   A seccomp-BPF deny/errno filter may be prototyped in a dedicated test
   process in `T57-R03-P0A-02`. It must not be installed in the production
   guest Zygote child in that task.

4. **Option D (user-notify) and Option E (privileged companion) are
   privileged deployment paths.** They are `REQUIRES_PRIVILEGE`. They are not
   ordinary APK features.

## 2. Why

Source inventory and the existing `native_syscall_boundary_self_test` already
show that:

- libc wrappers and guest `syscall()` PLT can be mediated;
- raw `syscall` / `svc #0` / `__syscall` / custom loaders cannot;
- translated-ABI guests skip PLT install;
- `/dev/binder*` is not a Native control;
- identity syscalls (`getpid`, `getuid`, `ptrace`, `execve`, `clone`) are
  not intercepted.

VA Pro commercial rows `VA-555` / `VA-572` / `VA-600` / `VA-601` / `VA-637` /
`VA-640` / `VA-647` / `VA-676` advertise seccomp-BPF. Those rows are
compatibility signals, not a CAS license to copy a closed implementation.
CAS currently has **no** seccomp installer.

Adding more libc hooks would improve `TRUSTED_COMPAT` coverage (chmod, xattr,
getcwd) but would not change the hostile statement. T57-R02 already forbade
that substitution. This campaign repeats the prohibition.

Option B is the only `ISOLATED_HOSTILE` direction that an ordinary APK can
honestly pursue: remove authority from the process, do not pretend to
intercept every instruction in a same-UID Zygote child.

## 3. What is implementable on ordinary non-root Android

| Capability | Ordinary APK |
|---|---|
| PLT/GOT compatibility interception | Yes — already present |
| libc `syscall()` mediation | Yes — already present for listed numbers |
| Path / selected procfs projection | Yes — already present |
| Native trust admission | Yes — already present |
| Isolated process + Broker FDs | Yes — partially present |
| Deny guest `INTERNET` / give brokered sockets | Yes, at UID/permission level |
| Extra seccomp-BPF in a dedicated process | Maybe — fixture POC only |
| Raw SVC interception | No |
| True secondary Linux UID | No |
| Guest linker namespace of our own | No — can only deny foreign namespaces |
| `/dev/binder` driver mediation | No |

## 4. What requires a privileged environment

| Capability | Requirement |
|---|---|
| seccomp user-notify supervisor | Kernel ≥ 5.0 **and** privilege / policy that lets a trusted process own the listener (`REQUIRES_PRIVILEGE`) |
| First-filter replacement / relaxing Zygote | Platform / root |
| Real UID or mount namespace split | Platform / root / isolated app UID assignment |
| SELinux domain for hostile guests | Platform |
| Binder-driver interception | Kernel / privileged companion |
| Device-owner / system-signed companion | Product deployment boundary (Option E) |

If a future build uses those powers, the product must say so. It must not
ship a switch that looks like user-notify and silently falls back to PLT.

## 5. TRUSTED_COMPAT route

Keep:

- guest-root ELF PLT/GOT rebinding
- `controlled_syscall` for the current FS/net/lifetime set
- `NativeFileSystemResolver` + `NativeProcFileSystem` known leaves
- `NativeLibraryLoaderPolicy` soname / ELF / namespace-deny
- explicit Native trust at install and runtime

Do **not**, in the next task:

- add chmod/xattr/getpid hooks just to lengthen the matrix
- compress `native_interceptors.cpp` to clear KI-M10-002
- treat RD fixture PASS as `VA_PRO_EQUIVALENT`

Compatibility gaps (openat2 edge, maps robustness, loader search path) stay
classified as `COMPATIBILITY_GAP` or `TEST_EVIDENCE_GAP` and may be batched
later. They are not the hostile program.

## 6. ISOLATED_HOSTILE route

Target process properties:

- isolated process slot, not the ordinary guest Zygote child
- no ambient access to host `/data/data/<host>` or other guests
- no `INTERNET` unless the Broker hands a connected socket
- no `/dev/binder` usefulness beyond the Broker-supplied Binder
- Native libraries either forbidden or loaded only after Broker admission
- ptrace/execve either blocked by policy/seccomp POC or declared out of scope
  for the ordinary APK product

Success metric: a raw `svc #0` `openat` of a host-private path **fails
because the process lacks the right**, not because a PLT slot was patched.

## 7. Next formal implementation split

`T57-R03-P0A-02` — Native Enforcement POC (fixture / test process only):

1. Isolated-process Broker-only FS/net capability POC.
2. Dedicated-process seccomp-BPF feasibility probe (install, SIGSYS, ABI
   matrix, Zygote inherited filter interaction). Record
   `REQUIRES_PRIVILEGE` / `ENVIRONMENT_BLOCKED` honestly.
3. Do **not** implement user-notify in production.
4. Do **not** change production hook tables except test-only JNI needed to
   observe the POC.
5. Keep 32-bit ABI. CXX5202 remains `EXPECTED_BEHAVIOR`.

Later campaigns, only after P0A-02 evidence:

- `P0A-03` production isolated-hostile admission mode
- privileged companion (Option E) as an explicit product SKU, if ever

## Decision record

- Recommended option: **A for TRUSTED_COMPAT + B for ISOLATED_HOSTILE**
- Privilege requirement: **PARTIAL** — ordinary APK can do A+B; D/E require
  privilege; C is unproven on Android app processes
- Production hook changes in P0A-01: **none**
