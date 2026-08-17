# T57-R03-P0A-01 Native Boundary / Threat Model / Adversarial Baseline

RESULT: PASS

This report is `RD_BASELINE_NATIVE_DISCOVERY`. It is not `VA_PRO_EQUIVALENT`.

## Git

- branch: `feature/t57-r03-va-pro-capability-campaign`
- baseline HEAD: `ed7b370cff4fffe828be1f821286ff1bcd10dcfb`
- baseline TREE: `f083ddd11ad0c962c59f442cc00bf61b7d649966`
- parent / main: `9e5d3e73628d80872c21776897898493925c7a97`
- campaign commit / tree: see `git log -1 --format="%H %T"` after the single commit

No amend, rebase, squash, force-push, merge, or push.

## Why fixture-basic was reused

`fixture-compat32` already shares `fixture-basic` Java and CMake. RD install already ships both APKs. A new Gradle module would add lockfile / settings / a third guest package without improving crash isolation.

Hostile probes live in test-only sources:

- `fixture-basic/src/main/cpp/adversarial_native.cpp`
- `fixture-basic/src/main/cpp/adversarial_payload.cpp`
- `NativeAdversarialProbe*` Java

Each case runs in a forked child. Nothing was added to `sandbox-native` production interceptors.

## Current Native Surface

Source inventory: `docs/native/T57_R03_NATIVE_BOUNDARY_MATRIX.yaml` (54 rows).

| Status | Count |
|---|---|
| INTERCEPTED | 18 |
| PARTIAL | 11 |
| NOT_INTERCEPTED | 24 |
| NOT_APPLICABLE | 1 |
| UNKNOWN | 0 |

Mechanism: guest-root ELF PLT/GOT rebinding (`dl_iterate_phdr`) plus a partial libc `syscall()` dispatcher. Not seccomp. Not a kernel boundary.

Translated-ABI guests and isolated processes skip PLT install (`GuestRuntimeEnvironment`).

### Intercepted (libc / listed `syscall()` numbers)

open / openat / openat2, access / faccessat / faccessat2, readlink*, unlink*, rename*, mkdir*, `/proc/.../maps` known leaves, socket / connect / bind / getsockname / getpeername / sendto / recvfrom, dlopen, android_dlopen_ext, libc wrapper table.

### Partial

stat family (no `fstat`), mmap (no `mprotect` / `mmap64`), kill/tgkill (self-term only), most other procfs, `syscall()` unknown numbers, System.load diagnostic, linker namespace deny-only, native search path.

### Not intercepted

chmod / chown / xattr, getcwd / chdir / realpath, getpid / getppid / gettid, getuid / getgid, prctl, ptrace, clone / fork / vfork, execve, dlsym, JNI_OnLoad wrap, custom mmap loader, `__syscall`, raw SVC / `syscall` instruction, `/dev/binder*`, seccomp.

## Adversarial Results

Execution that actually ran on RD测试:

- 64-bit fixture, ABI `x86_64`, context `DIRECT_FIXTURE`
- 32-bit fixture, ABI `x86`, context `DIRECT_FIXTURE`
- `arm64-v8a` / `armeabi-v7a` compiled, not executed on this device: `UNVERIFIED_RUNTIME`
- In-sandbox guest service: `BLOCKED_BY_KNOWN_ISSUE` (`KI-R03-NATIVE-010`)

| Case | Direct x86_64 / x86 | In-sandbox | Meaning |
|---|---|---|---|
| NATIVE-ADV-001 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | libc open/stat/readlink work |
| NATIVE-ADV-002 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | `syscall(SYS_openat)` equals libc without hooks |
| NATIVE-ADV-003 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | raw `syscall` / `int $0x80` executed; hook bypass not observed in-sandbox |
| NATIVE-ADV-004 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | libc / syscall / raw sockets created; localhost:9 connect failed equally |
| NATIVE-ADV-005 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | status/maps readable; fd/task/net are directories (`EISDIR`) |
| NATIVE-ADV-006 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | ptrace attach to fixture child returned 0 |
| NATIVE-ADV-007 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | `execve("/system/bin/true")` exit 0 in a child |
| NATIVE-ADV-008 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | soname `dlopen` + `android_dlopen_ext` + `JNI_OnLoad` marker; custom filesDir path ENOENT |
| NATIVE-ADV-009 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | `/proc/self/fd`, dup, inherited child FD all reveal the real path |
| NATIVE-ADV-010 | PASS_COMPAT | BLOCKED_BY_KNOWN_ISSUE | `/dev/binder`, `/dev/vndbinder`, `/dev/hwbinder` all open; no transaction |

`DIRECT_FIXTURE` does not install CAS hooks. Equality of libc vs syscall vs raw on that path is expected. It still proves the RD ABI can execute the bypass instructions.

Unhooked `syscall(SYS_openat/SYS_connect/SYS_sendto)` bypass is already asserted by `native_syscall_boundary_self_test.cpp`.

## Bypass Findings

| Class | Status |
|---|---|
| `syscall()` | Guest PLT is PARTIAL (mediated numbers). Unhooked / `__syscall` bypass is an architecture gap. In-sandbox not dynamically observed (`KI-R03-NATIVE-010`). |
| raw syscall instruction | EXECUTED on RD `x86_64`. Userspace PLT cannot see it. |
| raw SVC | Compiled for arm64; this RD session executed x86_64/x86, so arm64 SVC is `UNVERIFIED_RUNTIME`. Same architecture class. |
| custom loader | soname / `android_dlopen_ext` work. Absolute custom path failed ENOENT. mmap custom ELF remains NOT_INTERCEPTED. |
| procfs | Known leaves only. fd / task listing / map_files / `/proc/net` not virtualized. |
| execve | Allowed in fixture child (`/system/bin/true`). Not intercepted. |
| ptrace | Attach to fixture child succeeded. Not intercepted. |
| FD escape | `/proc/self/fd` / dup / inherit reveal the real path. |

## Architecture Decision

- `TRUSTED_COMPAT`: Option A — keep current PLT/GOT.
- `ISOLATED_HOSTILE`: Option B — isolated process + Broker-only capability. Success is “raw `openat` fails because the process lacks the right”, not because a PLT slot exists.
- Option C: fixture-only seccomp-BPF POC in P0A-02. Not production.
- Option D: `REQUIRES_PRIVILEGE`.
- Option E: privileged deployment SKU, not an ordinary APK feature.

Recommended option: **A + B**. Do not add more production PLT hooks as a hostile proof.

Privilege requirement: **PARTIAL**. Ordinary APK can do A+B. User-notify / real UID / Binder-driver mediation need privilege.

## VA Pro Mapping

Commercial changelog rows only. Not CAS implementation evidence.

| ID | cas_status | Note |
|---|---|---|
| VA-555 | GAP | no seccomp installer |
| VA-572 | GAP | no 32-bit seccomp |
| VA-600 | GAP | no seccomp-only mode |
| VA-601 | GAP | no seccomp redirect |
| VA-616 | NEEDS_TEST | bind/connect/`syscall()` PLT exist; no setxattr |
| VA-637 | GAP | no seccomp execve |
| VA-640 | GAP | no seccomp ptrace |
| VA-647 | GAP | no guest seccomp self-install |
| VA-648 | NEEDS_TEST | openat2/faccessat2 PLT exist; no seccomp |
| VA-674 | GAP | fd / map_files not virtualized |
| VA-676 | GAP | no trusted-syscall reentry |
| VA-686 | NEEDS_TEST | partial procfs; no seccomp mode |
| VA-693 | NEEDS_TEST | maps already line-oriented |
| VA-709 | UNVERIFIED | absent from locked README snapshot |

## RD测试

- MuMu instance name: `RD测试`
- dynamic ADB serial: `127.0.0.1:16416` (session-resolved; not a script constant)
- API: 32
- ABI: `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`
- executed fixture ABI: `x86_64` and `x86`
- boot_id: `2cb21625-099a-42d9-aa93-f6ddc52793f7`
- android_id: `398eea33120cd887`
- model: `22041211A`
- result: meaningful Native adversarial evidence collected
- in-sandbox service: `KI-R03-NATIVE-010`
- evidence: `artifacts/capability-audit/native/20260817T065551Z/` (gitignored)

## Build

`gradlew.bat :app:assembleDebug :sandbox-native:assembleDebug :sandbox-companion32:assembleDebug :fixture-basic:assembleDebug :fixture-compat32:assembleDebug`

BUILD SUCCESSFUL. CXX5202 remains `EXPECTED_ARCHITECTURAL_WARNING`. 32-bit ABI was not removed.

| APK | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `f39fc5094a779a192942f70b0a230edd16293b7ecdb7aa0b3ea0ad5941ed54b6` |
| `sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk` | `91110eb1d9c24d3d8a8607c3e39cf089fda24a0cceb232e72fd4d73aedbe7a2c` |
| `fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk` | `55489584dca911fe4a03af265e62962715b09d12a10b2f6213f950894b22cdfc` |
| `fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk` | `917e25d084d41bc03e8404fce8938f0ea7711d5d15f6b433eb7359e5d0762ac8` |

Native SO hashes (debug merged libs):

| SO | ABI | SHA-256 |
|---|---|---|
| `libcontrolled_sandbox_fixture.so` | arm64-v8a | `2842240e5da08da6443f652afcf2e7e4ee6ee62f33dc907ad58628f6511af782` |
| `libcontrolled_sandbox_fixture.so` | x86_64 | `8c311aa470ce22ad50869bc84615173fdb1c136c8254b3adbad5ec9bb3ffd8a2` |
| `libcontrolled_sandbox_fixture.so` | armeabi-v7a | `cd0a2d6653f79071e84944f0edae2b7c1d94627d81bd7f4868966c25431e797b` |
| `libcontrolled_sandbox_fixture.so` | x86 | `25d7c63d7548fb7ab11edea724dc3a7d6eed69116feeca253d0dc8df71fb71be` |
| `libfixture_adv_payload.so` | arm64-v8a | `f9aa0d97b105b28d61bc1e94a1441c03b8c39f1434fe931180bc563a90f1e260` |
| `libfixture_adv_payload.so` | x86_64 | `1e1bc686b881da66b959e586e702332ecbec94e5d6ff785fb0190aeea7ddc67e` |
| `libfixture_adv_payload.so` | armeabi-v7a | `16270909b037084f66893a33dacca2022bed2a3fc89d15297b42b38bb7c904d5` |
| `libfixture_adv_payload.so` | x86 | `6f8a5c664729e0fb9ec921e92a105193dca9a68c19d3a01ee3393946da6c6c8d` |

## Local Native Audit

`python tools/capability/run_local_capability_audit.py --campaign native`

Latest classified run: `artifacts/capability-audit/native/20260817T065409Z`

- total: 10
- PASS: 7
- KNOWN_ISSUE: 3 (`KI-M10-001`/`KI-M10-002`, `KI-R03-023`, `KI-M10-005`)
- NEW_REGRESSION: 0
- FAIL: 3 (all classified KNOWN_ISSUE)

`native_interceptors.cpp` was not compressed. M10 `aac6db55` line-count gaming was not reintroduced.

## Production code

Not modified:

- `sandbox-native` production hook logic
- `sandbox-runtime` core runtime
- `sandbox-framework` business logic
- Host/Broker security policy

Test-only files that touch the host app:

- `app/src/debug/java/.../DebugCommandActivity.java` — added `native-adversarial` command. Debug-only harness. No runtime semantic change.

## Maturity

- maturity: `RD_BASELINE_NATIVE_DISCOVERY`
- VA Pro equivalent: `NOT_PROVEN`

## Next recommended task

`T57-R03-P0A-02` Native Enforcement POC, fixture / isolated test process only:

1. Isolated-process Broker-only FS/net capability.
2. Dedicated-process seccomp-BPF feasibility probe.
3. Do not implement user-notify in production.
4. Do not expand production PLT tables as a hostile proof.
