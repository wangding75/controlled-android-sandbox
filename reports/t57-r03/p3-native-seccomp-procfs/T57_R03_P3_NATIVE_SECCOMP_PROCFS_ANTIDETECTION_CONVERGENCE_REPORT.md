# T57-R03-P3 Native / Seccomp / Procfs / Anti-detection Convergence

Task: `T57-R03-P3-NATIVE-SECCOMP-PROCFS-ANTIDETECTION-CONVERGENCE`

Result: **PASS WITH DEFERRED**

This campaign establishes the common Native boundary for CAS. It does not claim
VA PRO Native 100% parity. Direct raw syscall/SVC coverage, custom ELF loaders,
full Guest seccomp user-notify, translated-ABI device proof, and hardened-app
corpus coverage remain explicit follow-up work.

No source backup ZIP, Git bundle, Review Pack, push, OEM work, P4 work, GMS,
WebView, SX, or XH-specific Native hook was created.

## 1. Baseline

| Item | Value |
|---|---|
| Branch | `feature/t57-r03-va-pro-capability-campaign` |
| START_HEAD | `9ecca0c0342520409a12e878c149df64c0a8ad19` |
| START_TREE | `42fac1b2caed8cd19e8b8a9216d327d95274b7d5` |
| Start worktree | Clean, verified before changes |
| Production FINAL_HEAD | `48c4d16586c6d451cac428a7550380583b619ee4` |
| Production FINAL_TREE | `85a2d21fa2f6c08832034426fe1c7987bc79c455` |
| Production commit | `feat(native): converge syscall procfs and identity boundaries` |
| Report | This fixed-path report is the subsequent Git receipt commit |

The `FINAL_HEAD`/`FINAL_TREE` pair above identifies the complete production
code and test state immediately before the report receipt commit. The final Git
tip after adding this report is recorded in the final task response, without
amending the production commit.

## 2. Root Causes

The pre-P3 Native boundary had several real, related causes rather than one
missing function:

1. `native_interceptors.cpp` was the primary aggregation point. It had broad
   libc/PLT coverage, but no single policy object shared by libc wrappers,
   `syscall()`, procfs, FD ownership, and process identity.
2. A libc wrapper and a direct `syscall()` or inline `SVC/SYSCALL` were not the
   same semantic path. PLT/GOT installation was therefore not evidence of raw
   syscall protection.
3. Java and Binder had virtual identity contracts while Native identity,
   `/proc`, FD targets, and process-control operations were only partially
   projected.
4. Procfs handling was leaf-oriented. Known status/maps/cmdline paths did not
   by themselves establish a coherent `/proc/<pid>/task`, `fd`, `fdinfo`, or
   `map_files` namespace.
5. There was no process-local FD ownership ledger capable of carrying virtual
   ownership across `dup*` and `F_DUPFD*`, or of distinguishing Guest FDs from
   Host-internal and broker transport FDs.
6. Late-loaded modules needed a refresh of the hook target set. A real Android
   data-path alias (`/data/data` versus `/data/user/0`) also had to be treated
   generically so that the late-loader path and the projected path agreed.
7. `HostileSeccompInstaller` was a useful isolated-process deny filter, but it
   was not the Guest syscall projection or a user-notify implementation. The
   two responsibilities were previously easy to conflate.

## 3. Architecture

```text
Guest Native Call
        |
        +--> PLT/GOT libc hook or libc syscall() entry
        |          |
        |          +--> common NativeSyscallPolicy lookup
        |
        +--> direct raw syscall/SVC/SYSCALL
                   |
                   +--> explicit supported boundary / known partial escape

common process-local read-mostly policy snapshot
        |
        +--> filesystem projection + path/dirfd confinement
        +--> procfs projection + identity rendering
        +--> process identity / prctl / ptrace / exec policy
        +--> FD ownership ledger + dup fencing
        +--> linker admission + late-module refresh
        +--> Guest seccomp contract
        |
trusted internal syscall path (CAS-owned, recursion-safe)
        |
Linux kernel
```

The implementation is split into the following responsibilities:

- `native_boundary.*`: syscall action lookup, process identity projection,
  Guest seccomp contract, and architecture-specific trusted syscall entry.
- `native_policy.*`: immutable/read-mostly process snapshot, session/generation
  revision, Guest cwd, capability roots, and FD ledger registration.
- `native_file_system.*`: lexical normalization, `AT_FDCWD`/dirfd resolution,
  `/proc/self/fd` resolution, confinement, and stale-revision checks.
- `native_procfs.*`: path classification, dynamic directory projection, status,
  cmdline, maps, mounts, cgroup, task, fd, fdinfo, and map_files snapshots.
- `native_process_interceptors.*`: identity, prctl, ptrace, clone/fork/vfork,
  execve, cwd/metadata, dlsym, and FD operation wrappers.
- `native_fd_ledger.*`: Guest-owned, Host-internal, broker transport,
  inherited, and virtualized-path ownership with dup propagation.
- `native_interceptors.cpp`: existing high-frequency hook dispatch plus the
  common `syscall()` routing point. It remains large, but the new policy,
  procfs, FD, and process responsibilities are not appended as one new block.

## 4. Capability Results

Status means P3 capability status, not a claim that every CPU, kernel, or
commercial hardened App is covered.

| Capability | Status | Result |
|---|---|---|
| PLT_GOT | PARTIAL | Common filesystem, process, procfs, FD, loader, and syscall symbols are in the target registry. Late refresh was observed with `patchFailures=0`; raw instructions remain outside PLT/GOT. |
| LIBC_INTERCEPTION | PARTIAL | `open*`, `stat*`, `access*`, `readlink*`, directory, mutation, identity, process-control, loader, and FD paths use common policy. Unlisted libc/inline variants are not claimed. |
| SYSCALL_INTERCEPTION | PARTIAL | `syscall()` dispatch covers common identity, FS, procfs, process-control, FD, and seccomp operations. Unknown calls pass through to preserve Android/ART compatibility. |
| RAW_SYSCALL | PARTIAL | The boundary is characterized and the internal trusted path exists. Guest inline raw `SYSCALL/SVC` still bypasses userspace hooks; API35/API36 recorded `BYPASS_CONFIRMED` honestly. |
| PID_UID_IDENTITY | PARTIAL | `pid`, `ppid`, `tid`, `uid`, `euid`, `gid`, and `egid` wrappers share the virtual snapshot. `ppid=1` and `tid=virtual pid` are conservative CAS contracts; a rich virtual thread ledger is deferred. |
| PRCTL | PARTIAL | Name, dumpable, seccomp, and no-new-privs queries/sets have explicit contracts. Guest seccomp installation is denied; unknown options are forwarded. Short and zero-argument variadic forms are tested. |
| PTRACE | PARTIAL | `0` and current virtual targets translate to the host process; foreign targets fail with `ESRCH`; no unconditional fake success or unconditional fake denial is used. |
| CLONE_FORK | PARTIAL | `fork`/`vfork` and unbrokered process creation are fail-closed. `CLONE_THREAD` remains allowed for a kernel thread operation; broker-mediated process creation is not implemented. |
| EXECVE | PARTIAL | Configured Guest `execve`/`execveat` is fail-closed, preventing a Host binary/linker context escape. Full Guest exec with argv/envp/linker namespace preservation is deferred. |
| FILESYSTEM_PROJECTION | PARTIAL | Open, metadata, access, readlink, rename, unlink, mkdir, rmdir, chmod, chown, truncate, cwd, and dirfd forms share the resolver. Complete xattr/mount/metadata parity is deferred. |
| PATH_NORMALIZATION | PARTIAL | Absolute/relative paths, `.`, `..`, `AT_FDCWD`, dirfd-relative operations, and proc-fd paths are normalized and confined. Symlink-heavy and unenumerated FD edge cases remain partial. |
| METADATA_CONSISTENCY | PARTIAL | Open/stat/access/readlink use the same virtual path decision and revision fence. Kernel inode identity and all symlink semantics are not synthesized. |
| PROC_STATUS | PARTIAL | `Name`, `Pid`, `PPid`, `TracerPid`, `Uid`, `Gid`, `Threads`, `Seccomp`, and `NoNewPrivs` are rendered from the shared model or real safe values. Other status fields remain kernel/projected mix. |
| PROC_CMDLINE | PARTIAL | Own and virtual PID cmdline projection uses the virtual process name and avoids the stub name. Non-owned PID spaces are fail-closed rather than fabricated. |
| PROC_MAPS | PARTIAL | Address range, permissions, offset, inode, and path shape are preserved while CAS/private paths are projected. It is not a complete arbitrary ELF/custom-loader map model. |
| PROC_TASK | PARTIAL | Own task roots and virtual task status/stat paths are classified dynamically; helper/foreign process enumeration is not exposed as a complete virtual process graph. |
| PROC_FD | PARTIAL | Known and observed FDs are projected through the ledger; Host-internal/broker descriptors are denied/redacted. Unknown inherited descriptors remain a bounded compatibility edge. |
| PROC_MAP_FILES | PARTIAL | Map-file paths are classified and readlink/stat targets are sanitized consistently where the map is known. Full arbitrary map_files parity is deferred. |
| PROC_MOUNTINFO_CGROUP | PARTIAL | Core CAS paths and obvious private/container signatures are removed from projected snapshots. A complete Android mount namespace/cgroup identity is out of scope. |
| FD_OWNERSHIP | PARTIAL | The process-local ledger distinguishes Guest, Host-internal, broker, inherited, and virtualized-path descriptors. Provider/Binder FD producers are not all ledger-backed yet. |
| DUP_FENCING | PARTIAL | `dup`, `dup2`, `dup3`, `F_DUPFD*`, close, socket accept, and inherited observation propagate ownership/revision. Raw direct FD syscalls and unknown inherited descriptors remain partial. |
| LINKER_NAMESPACE | PARTIAL | Existing P2 ABI/library selection and namespace boundary remain active; P3 adds dlopen/ext admission and path-aware late refresh. Custom linker namespaces are not accepted. |
| LATE_DLOPEN | CLOSED | Framework-service smoke on API35/API36 showed late hook refresh, `refreshed=true`, `patchFailures=0`, and the new payload executing through the Guest route. |
| CUSTOM_LOADER | DEFERRED | Manual mmap/ELF relocation/PLT construction is detected as a boundary but is not generalized into a new loader implementation. |
| SECCOMP | PARTIAL | Owner and policy are explicit. `HostileSeccompInstaller` remains an isolated-process classic-BPF deny filter; it is not Guest syscall mediation, trap, or user-notify. |
| GUEST_SECCOMP | PARTIAL | Guest `prctl(PR_SET_SECCOMP)` and `seccomp(SECCOMP_SET_MODE_*)` installation is denied to protect CAS interception; safe queries are mediated/forwarded according to contract. |
| SECCOMP_REENTRANCY | PARTIAL | CAS-owned policy queries and procfs materialization use a trusted syscall path, avoiding interceptor recursion in tested flows. Advanced trap/user-notify reentry is not implemented. |
| ANTI_DETECTION_BASELINE | PARTIAL | CAS-created Host package/path, PID/UID, process-name, procfs, FD, and linker inconsistencies are reduced. The implementation does not globally spoof real Android facts or hide every detection surface. |

### 4.1 Process and filesystem policy

- Unknown or dangerous `execve`, process creation, foreign ptrace targets,
  stale revisions, internal FD access, and invalid path escapes fail closed.
- `NativeProcessIdentity` keeps host accessors separate from Guest accessors.
  This prevents a virtual PID from accidentally becoming a kernel target.
- `NativeFdLedger::duplicate` carries ownership, virtual path, and policy
  revision to duplicated descriptors. A stale revision returns `EAGAIN` on
  protected FD operations.
- Capability paths are resolved into the current data root. Host package/path
  aliases are generic `/data/data/<package>` and `/data/user/0/<package>` forms;
  no package-specific anti-detection branch was added.

### 4.2 Procfs projection

The classifier covers `/proc/self`, `/proc/thread-self`, the configured virtual
PID, task roots, status/stat/statm/io, cmdline, maps/smaps, mounts/mountinfo,
cgroup, fd/fdinfo, and map_files. Directory materialization is dynamic for
the configured process, descriptor ledger, task root, and map snapshot. Unknown
leaves and `/proc/net` are fail-closed or left to the existing network policy;
they are not filled with fabricated universal values.

Maps retain structural fields and sanitize only path identity that would reveal
CAS internals. `map_files` readlink/stat projection uses the same map/path
policy, so it is not an independent leak path.

### 4.3 Seccomp ownership

`HostileSeccompInstaller`:

- installs only in the explicitly selected hostile isolated worker;
- uses architecture-specific classic BPF with `SECCOMP_RET_ERRNO|EPERM` for
  the fixed high-risk set (`socket`, connect/bind/send, ptrace, execve, etc.);
- sets `no_new_privs` and returns explicit installation failure status;
- tries the trusted `seccomp()` syscall path, then the `prctl` fallback;
- does not provide Guest path projection, raw-SVC interception, trap, or
  `SECCOMP_RET_USER_NOTIF`.

The Guest contract is separate: queries may be observed/forwarded, while
installation that could disable or bypass the CAS boundary is denied. This is
deliberate fail-closed behavior, not a claim of commercial seccomp parity.

## 5. Cross-layer Identity

The P1/P2 Java and Binder identity contract remains the source of virtual
session identity. P3 consumes the same process-local Native snapshot.

Observed API35/API36 Native service evidence:

- virtual `pid=20054` and `uid=10000` from Native;
- `/proc/self/status` and `cmdline` use the projected process identity/name;
- libc and `syscall(SYS_openat/readlink/stat...)` see the same projected data
  root and the same procfs content shape;
- `getpid`/`getuid` agree with the Native procfs projection;
- `PR_GET_NAME` succeeds through the short variadic call path;
- `fork` and `execve` child paths are reported `BLOCKED_BY_POLICY`, not fake
  successful child creation;
- late `.so` refresh reports `refreshed=true`, `patchFailures=0`;
- `gid`/`egid` project to the virtual UID because CAS has one virtual identity
  number in this policy contract and no independent virtual GID allocator.

The supported Native paths therefore do not show the previous obvious
`Java/Binder=virtual, Native=physical` split. Direct raw syscalls remain an
explicit exception and are reported as such.

## 6. VA / NBB Native Reference Comparison

The local reference audit used public/checked-in source only. It did not treat
old VA source as current VA PRO implementation evidence.

| Capability | VA reference | NBB reference | CAS Before | CAS After | Decision |
|---|---|---|---|---|---|
| Path redirection | `NativeEngine` / `IOUniformer` relocate and reverse paths | `FileSystemHook`, `UnixFileSystemHook` | Existing path hooks and known leaves | Common resolver, dirfd normalization, metadata/cwd/proc sharing | Adopt the boundary concept; do not copy implementation |
| Native hook registration | NativeEngine/Substrate-style hook framework | Dobby/xDL and runtime hooks | Startup PLT/GOT focus | Central target registry plus late refresh and `dlsym` projection | Keep CAS hook substrate; add policy boundary |
| PID/UID/Binder identity | Native/Java hooks in VA family; current commercial behavior not proven from old source | `BinderHook`, `VirtualSpoof` | Java/Binder stronger than Native | Native wrappers/procfs share virtual snapshot | Cross-layer contract is CAS-owned |
| Procfs | Commercial changelog signals for maps/task/fd/map_files | Broad anti-detection utility paths | Fixed/known leaves | Classifier and dynamic projection | Prefer coherent projection over marker deletion |
| FD ownership | Public old source does not prove a complete modern FD ledger | Dobby/file hooks do not prove one | No uniform ownership | Ledger plus dup/revision fencing | New CAS substrate |
| Seccomp | Commercial changelog signals seccomp-BPF/trusted syscall/reentry | Public NBB reference is primarily hook/spoof based | Hostile deny filter only | Owner/contract separated; Guest install denied | No claim of VA PRO seccomp parity |
| Anti-detection | Commercial compatibility scope is broader | `AntiDetection.cpp` and `VirtualSpoof.cpp` contain broad marker/property spoofing | Inconsistency leaks | CAS fixes self-created leaks only | No app-specific or vendor-specific spoofing |

Local source paths reviewed:

- `ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/client/NativeEngine.java`
- `ref/upstream/VirtualApp/VirtualApp/lib/src/main/jni/Foundation/IOUniformer.cpp`
- `ref/upstream/NewBlackbox/Bcore/src/main/cpp/Utils/AntiDetection.cpp`
- `ref/upstream/NewBlackbox/Bcore/src/main/cpp/Utils/VirtualSpoof.cpp`
- `ref/upstream/NewBlackbox/Bcore/src/main/cpp/Hook/FileSystemHook.cpp`
- `ref/upstream/NewBlackbox/Bcore/src/main/cpp/Hook/UnixFileSystemHook.cpp`
- `ref/upstream/NewBlackbox/Bcore/src/main/cpp/Hook/RuntimeHook.cpp`
- `ref/upstream/NewBlackbox/Bcore/src/main/cpp/Hook/DexFileHook.cpp`
- `ref/upstream/NewBlackbox/Bcore/src/main/cpp/Hook/BinderHook.cpp`

Public reference repositories: [VirtualApp](https://github.com/asLody/VirtualApp)
and [NewBlackbox](https://github.com/ALEX5402/NewBlackbox). Their public
material is a design reference, not evidence of the current VA PRO commercial
implementation.

## 7. VA PRO Gap Mapping

The mapping is rooted in the checked-in
`docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml`. Changelog numbers are
commercial compatibility signals, not test results for CAS.

| Root cause / VA PRO signal | CAS result | Status | Follow-up |
|---|---|---|---|
| seccomp-BPF mode, trusted syscall, reentry (`VA-555`, `VA-572`, `VA-600`, `VA-601`, `VA-637`, `VA-640`, `VA-647`, `VA-676`) | Explicit owner, classic deny filter separation, trusted internal syscall path, Guest install contract | PARTIAL | Advanced Guest mediation/user-notify and broader kernel enforcement |
| raw syscall / short native hook / no-inline-hook mode | `syscall()` common entry is projected; direct inline instruction bypass is characterized | PARTIAL | Architecture-specific kernel/interposition work; no false closure |
| `execve`, `ptrace`, fork/clone process boundary | Guest operations fail closed or translate current targets; no Host binary context escape | PARTIAL | Broker-mediated Guest exec/process model |
| procfs task/fd/map_files/maps and maps-line handling (`VA-686`, `VA-693`, and procfs traversal signals in the corpus) | Dynamic classifier, virtual status/cmdline/maps/task/fd/fdinfo/map_files, structural maps sanitizer | PARTIAL | Full virtual process namespace and arbitrary map parity |
| openat2/faccessat2 and path hardening (`VA-648`) | Common resolver and supported syscall dispatch; invalid dirfd/path escape denied | PARTIAL | Complete syscall matrix and symlink/metadata edge cases |
| late linker/library loading and hardened apps | Late `dlopen` refresh works on API35/API36; custom loader is fail-closed | PARTIAL | Custom ELF loader and hardened corpus |
| broad anti-detection | CAS own identity/path/FD inconsistency reduced | OUT_OF_SCOPE for universal spoofing | Compatibility Extension Plane only if a future general requirement is proven |

The four gap labels used by this report are:

- `CLOSED`: the scoped production contract is implemented and directly
  observed; only `LATE_DLOPEN` qualifies in this campaign.
- `PARTIAL`: the main architecture and common paths work, with explicit
  unsupported or unverified boundary.
- `DEFERRED`: deliberately not attempted in P3, such as custom loaders and
  translated-ABI device proof.
- `OUT_OF_SCOPE`: universal anti-detection spoofing, OEM behavior, and
  app/vendor-specific compatibility patches.

## 8. Native Code Impact

Counts use the native extension set (`*.cpp`/`*.h`) and keep CMake/configuration
files separate:

| Metric | Result |
|---|---|
| Modified native extension files | 14 |
| Added native extension files | 7 (6 production substrate files + 1 deterministic test) |
| Additional native build configuration files | 2 CMake files |
| Largest Native source file | `native_interceptors.cpp`, 2,157 lines |
| `native_interceptors.cpp` baseline/current | 1,838 → 2,157 lines, +319 |
| Did the old interceptor file continue to grow? | Yes, for dispatch integration; the new policy/procfs/FD/process modules prevent all P3 logic from becoming one file |
| New unified policy/substrate | Yes: syscall policy, identity, trusted syscall, FS/procfs/FD/process boundaries |
| New deterministic test | 1: `native_convergence_policy_self_test.cpp` |
| Extended deterministic/hook test | `native_hook_self_test.cpp` |
| P3 Known Issue reclassifications | 11 (`CLOSED=0`, `PARTIAL=8`, `DEFERRED=1`, `STILL_OPEN=2`) |
| New Known Issues | 0 |

The `native_interceptors.cpp` God-File debt remains recorded as
`KI-M10-002: STILL_OPEN`; no macro compression or artificial line-count
reduction was used.

## 9. Tests and Evidence

### 9.1 Deterministic and build checks

| Check | Result |
|---|---|
| `cmd /c gradlew.bat :app:assembleDebug :fixture-basic:assembleDebug :fixture-compat32:assembleDebug :sandbox-companion32:assembleDebug --no-daemon` | PASS; all configured arm64-v8a, armeabi-v7a, x86, x86_64 native builds completed |
| `python tools/static_android_compile.py` | PASS |
| `python tools/capability/run_local_capability_audit.py --all` | `29 PASS / 13 KNOWN_ISSUE / 0 EXPECTED_WARNING / 0 NEW_REGRESSION / 13 FAIL / 0 UNVERIFIED`; exit reflects pre-existing known issues |
| `python scripts/check-native-files-loader.py` | PASS |
| `python scripts/check-native-boundary-matrix.py` | PASS |
| `git diff --check` | PASS; only the tracked JSON line-ending warning is reported by Git |
| `bash scripts/test-native.sh` policy/FS/procfs/FD/syscall tests | PASS through deterministic test execution, including new convergence substrate and short/zero-argument prctl assertions |
| `bash scripts/test-native.sh` complete host interceptor runner | Integration Verification Debt: WSL host lacks `android/log.h` and `jni.h`; Android Gradle/NDK build is the authoritative source compile |
| ASAN/UBSAN/CMake sanitizer target | Not run; no existing suitable P3 target was present, and no new sanitizer infrastructure was started |

The final serialized audit artifact was:
`artifacts/capability-audit/all/20260820T191922Z`.

The host runner failure is recorded in
`build/verification/native-host-test-execution.json` and is not counted as a
Native source regression because every earlier deterministic test in that
runner passed and the actual Android/NDK targets compiled.

### 9.2 API32 MuMu / RD测试

Dynamic resolution selected serial `127.0.0.1:16416`, API32. No historical
serial was hardcoded.

- Native adversarial campaign: PASS, all ten cases produced meaningful output;
  evidence `artifacts/capability-audit/native/20260820T191403Z`.
- Native enforcement campaign: PASS; FS/NET enforcement proven on RD, isolated
  UID/process distinct, FD and stale-revision cases covered, seccomp fixture64
  and fixture32 feasibility recorded; evidence
  `artifacts/capability-audit/native-enforcement/20260820T191448Z`.
- Direct x86 companion evidence is retained as source/device coverage evidence;
  it is not promoted to translated-ABI parity.
- `PR_GET_NAME`, dumpable, seccomp, and no-new-privs results are observed and
  reported, not replaced by fixture constants.

### 9.3 API35 official AVD

AVD: `T57_R03_API35_x86_64`, serial `emulator-5558`, x86_64.

Targeted `native-adversarial` service reached `SERVICE_DONE` and the DebugCommand
returned `status=PASS`. Native evidence included:

- libc and `syscall()` projected the same CAS data root;
- raw inline syscall was `BYPASS_CONFIRMED`;
- `/proc/self/status`, maps, FD/task probes, `/proc/net` policy, getpid/getuid,
  prctl, fork/exec policy, late dlopen, and dup/proc-fd cases completed;
- fork/exec cases were `BLOCKED_BY_POLICY`;
- late refresh reported `refreshed=true`, `patchFailures=0`, `targets=34`.

The framework runner then logged the pre-existing API35
`NoSuchMethodException: serviceDoneExecuting` reflection failure after the
Native result was emitted. This is `Integration Verification Debt`, not a P3
Native failure and not a re-opening of Activity/FIX03.

### 9.4 API36 official AVD

AVD: `T57_R03_API36_x86_64`, serial `emulator-5560`, x86_64.

Targeted native smoke produced the same result shape as API35: service/native
cases completed, `getpid=20054`, `getuid=10000`, prctl projection succeeded,
raw direct was explicitly `BYPASS_CONFIRMED`, process creation was blocked,
late refresh had `patchFailures=0`, and the same pre-existing
`serviceDoneExecuting` runner exception occurred after Native completion.

### 9.5 API37

`DEFERRED_API37`: no official API37 AVD was present in `avdmanager list avd`.
This does not block P3.

### 9.6 Source versus device coverage

| Coverage | Proven |
|---|---|
| Source/build | Android NDK source builds for configured arm64-v8a, armeabi-v7a, x86, and x86_64 targets; companion32 wiring compiles |
| Runtime device | API32 RD测试, API35 x86_64, API36 x86_64 |
| Not device-proven | ARM64 runtime smoke in this campaign, translated-ABI Guest execution through a different host ABI, API37, OEM kernels |
| Raw instruction coverage | Characterized on x86/x86_64; not claimed as protected |

## 10. Production Blockers

`PRODUCTION_P3_BLOCKERS: NONE OBSERVED`.

No new P3 crash/ANR/startup failure, native compile failure, path escape in
the supported resolver, Host UID/PID leak through supported wrappers, stale
session execution, or seccomp recursion/deadlock was observed.

The following are deliberately not blockers under the task definition:

- direct raw syscall/SVC bypass, because it is explicit `RAW_SYSCALL: PARTIAL`;
- custom ELF loaders;
- complete user-notify/trap virtualization;
- translated ABI and API37 device coverage;
- hardened commercial App corpus;
- runner/evidence `serviceDoneExecuting` reflection debt;
- existing non-P3 known issues reported by the collect-all audit.

## 11. Deferred and Integration Issues

- Full direct raw syscall/SVC interception or kernel-assisted mediation for all
  supported instruction variants.
- Custom mmap/ELF relocation loaders and complete custom linker namespace
  semantics (`CUSTOM_LOADER_DEFERRED`).
- Rich virtual thread IDs and complete virtual process graph enumeration.
- Complete Provider/Binder/inherited FD producer ledger, including all
  `ParcelFileDescriptor` paths.
- Complete Android mount namespace, mountinfo, cgroup, and kernel metadata
  projection.
- Guest seccomp trap/user-notify with a trusted supervisor and complete
  reentry semantics.
- ARM64/ARM32 runtime device matrix, translated ABI runtime proof, API37, OEM.
- Large hardened-App/commercial packer corpus and any future general
  compatibility extension candidates.
- API35/API36 framework `serviceDoneExecuting` reflection/runner evidence debt;
  the Native result is emitted before this failure.

## 12. Known Issues

P3 statuses were added without deleting the legacy issue registry status or
rewriting historical ownership:

| P3 status | Issues |
|---|---|
| CLOSED | None |
| PARTIAL | `KI-T57-009`, `KI-R03-NATIVE-001` through `005`, `KI-R03-NATIVE-008`, `KI-R03-NATIVE-009` |
| DEFERRED | `KI-R03-NATIVE-007` |
| STILL_OPEN | `KI-R03-NATIVE-006` (Binder device-node observation), `KI-M10-002` (Native interceptor source-closure/God-File debt) |
| NEW | None |

Important meanings:

- `KI-R03-NATIVE-001` remains partial because direct raw instructions bypass
  PLT/GOT.
- `KI-R03-NATIVE-002` remains partial because process operations are controlled
  or denied but raw inline paths remain outside coverage.
- `KI-R03-NATIVE-003` remains partial because unknown proc leaves, `/proc/net`,
  and complete inherited-FD enumeration are not fabricated.
- `KI-R03-NATIVE-004` remains partial because xattr/mprotect and full metadata
  parity are not in P3.
- `KI-R03-NATIVE-005` remains partial because late dlopen is handled but a
  custom loader is deferred.
- `KI-R03-NATIVE-008` remains partial because HostileSeccompInstaller is
  deny-only and Guest installation is mediated/denied; user-notify is absent.
- `KI-R03-NATIVE-009` remains partial because the core ledger/dup/proc-fd path
  works while all Provider/Binder and unknown inherited producers are not
  complete.

## 13. Commits

| Commit | Purpose |
|---|---|
| `48c4d16586c6d451cac428a7550380583b619ee4` | Native policy, syscall, process identity, filesystem, procfs, FD, seccomp boundary, late-loader refresh, deterministic tests, fixture, build wiring, and Known Issues update |
| Report receipt commit | Adds this fixed-path report; exact final tip is returned by the final Git receipt after commit |

No amend, rebase, squash, merge-main, force push, or push was performed.

## 14. Final Receipt Fields

```text
RESULT: PASS WITH DEFERRED
TASK: T57-R03-P3-NATIVE-SECCOMP-PROCFS-ANTIDETECTION-CONVERGENCE
START_HEAD: 9ecca0c0342520409a12e878c149df64c0a8ad19
START_TREE: 42fac1b2caed8cd19e8b8a9216d327d95274b7d5
FINAL_HEAD: 48c4d16586c6d451cac428a7550380583b619ee4
FINAL_TREE: 85a2d21fa2f6c08832034426fe1c7987bc79c455
NATIVE_INTERCEPTION_SUBSTRATE: PASS, unified policy/procfs/FD/process substrate
PLT_GOT: PARTIAL
LIBC_INTERCEPTION: PARTIAL
SYSCALL_INTERCEPTION: PARTIAL
RAW_DIRECT_SYSCALL: PARTIAL, BYPASS_CONFIRMED for Guest inline raw path
PID_UID_IDENTITY: PARTIAL
PRCTL: PARTIAL
PTRACE: PARTIAL
CLONE_FORK: PARTIAL
EXECVE: PARTIAL, fail-closed for configured Guest
FILESYSTEM_PROJECTION: PARTIAL
PATH_NORMALIZATION: PARTIAL
METADATA_CONSISTENCY: PARTIAL
PROC_STATUS: PARTIAL
PROC_CMDLINE: PARTIAL
PROC_MAPS: PARTIAL
PROC_TASK: PARTIAL
PROC_FD: PARTIAL
PROC_MAP_FILES: PARTIAL
PROC_MOUNTINFO_CGROUP: PARTIAL
FD_OWNERSHIP: PARTIAL
DUP_FENCING: PARTIAL
LINKER_NAMESPACE: PARTIAL
LATE_DLOPEN: CLOSED within scoped loader boundary
CUSTOM_LOADER: DEFERRED
SECCOMP: PARTIAL
GUEST_SECCOMP: PARTIAL
SECCOMP_REENTRANCY: PARTIAL
ANTI_DETECTION_BASELINE: PARTIAL
JAVA_BINDER_NATIVE_IDENTITY: CONSISTENT on supported paths
P1_REGRESSION: NONE OBSERVED
P2_REGRESSION: NONE OBSERVED
ACTIVITY_FIX03_REGRESSION: NONE OBSERVED; existing serviceDone runner debt remains
API32_SMOKE: PASS on RD测试
API35_SMOKE: PASS targeted Native path; runner debt recorded
API36_SMOKE: PASS targeted Native path; runner debt recorded
API37: DEFERRED_API37
VA_REFERENCE: REVIEWED, reference only
NBB_REFERENCE: REVIEWED, reference only
VA_PRO_GAP_CLOSED: scoped late-dlopen refresh and common supported boundary pieces
VA_PRO_GAP_PARTIAL: raw syscall, procfs breadth, FD producers, seccomp, linker, identity edge cases
PRODUCTION_P3_BLOCKERS: NONE OBSERVED
DEFERRED_INTEGRATION_ISSUES: raw SVC, custom loader, user-notify, ABI/API37, Provider/Binder FD breadth, runner, hardened corpus
NATIVE_KNOWN_ISSUES_CLOSED: 0
NATIVE_KNOWN_ISSUES_REMAINING: 11 reclassified; 8 PARTIAL, 1 DEFERRED, 2 STILL_OPEN
STATIC_COMPILE: PASS
LOCAL_AUDIT: 29 PASS / 13 KNOWN_ISSUE / 0 NEW_REGRESSION
NEW_REGRESSION: 0
GIT_DIFF_CHECK: PASS
COMMITS: 48c4d165 + report receipt commit
REPORT: D:\github\controlled-android-sandbox\reports\t57-r03\p3-native-seccomp-procfs\T57_R03_P3_NATIVE_SECCOMP_PROCFS_ANTIDETECTION_CONVERGENCE_REPORT.md
GIT_STATUS: recorded after final receipt
NEXT: WAIT_FOR_NEXT_TASK
```

## 15. Next

`WAIT_FOR_NEXT_TASK`

P3 stops here. No P4, Integration Verification campaign, OEM, SX, or XH work
is started automatically.
