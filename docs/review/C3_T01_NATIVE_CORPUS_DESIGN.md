# C3-T01 Native Compatibility and Bypass Corpus Design

## Scope and evidence boundary

`C3-T01` establishes the product-scope native entrypoint inventory and a
repeatable compatibility/bypass corpus. The authoritative inventory is
`docs/native/T57_R03_NATIVE_BOUNDARY_MATRIX.yaml`; every row has an explicit
libc, `syscall()`, raw-instruction, status, risk, fixture, and `target_mode`
classification.

The campaign has two deliberately separate interpretations:

- `TRUSTED_COMPAT`: ordinary Guest native calls through the current libc/PLT or
  controlled `syscall()` paths are compatibility evidence.
- `ISOLATED_HOSTILE`: raw SVC/int instructions, unhooked device/ioctl paths,
  custom loaders, identity/process escape paths, and other host-authority
  boundaries require a kernel/supervisor boundary. A PLT hit cannot establish
  this mode.

The corpus does not claim VA Pro equivalence, kernel enforcement, API33+,
ARM-device, OEM, or commercial-app coverage. `BYPASS_CONFIRMED` is a useful
discovery status, not a failed test and not an isolation claim.

## Inventory classification

The matrix is the complete C3-T01 product-scope inventory. The newly exercised
case `NATIVE-ADV-011` covers the long-tail filesystem and native-handle paths:

| Entry family | Classification | Corpus evidence |
|---|---|---|
| `open`, `openat`, `openat2` | libc/controlled `syscall()` is compatibility-virtualizable; raw instruction remains uncontrolled | `NATIVE-ADV-001`, `002`, `003`, `011` |
| `faccessat`, `faccessat2`, `stat`, `fstatat` | wrapper compatibility is partial/virtualizable; raw and kernel-availability limits are recorded | `NATIVE-ADV-011` |
| xattr, `getcwd`/`chdir`, `realpath` | currently uncontrolled or explicitly limited; no hostile-isolation claim | `NATIVE-ADV-011` |
| chmod/chown family | currently uncontrolled and classified as a matrix-only boundary row | matrix status rows; no compatibility claim |
| `socket`, connect/bind/send paths | libc compatibility is exercised; direct syscall/raw paths retain bypass risk | `NATIVE-ADV-004` |
| `execve`, clone/fork, identity, procfs and FD paths | policy/refusal and observation boundaries are recorded; process authority is not virtualized by PLT | `NATIVE-ADV-005`–`009` |
| `ioctl` | safe pipe `FIONREAD` compatibility probe only; device/binder ioctl isolation is uncontrolled | `NATIVE-ADV-010`, `011` |
| `dlopen`, linker/JNI and custom loader paths | ordinary loader compatibility is distinct from custom-loader isolation | `NATIVE-ADV-008` |
| raw SVC/int, `syscall()` and inline assembly | architecture boundary is uncontrolled unless a separate authority boundary exists | `NATIVE-ADV-002`, `003`, `011` |

Rows marked `NOT_INTERCEPTED` or `PARTIAL` remain explicit known boundary
conditions. They are not silently promoted by a successful direct fixture.

## Fixture contract

`NATIVE-ADV-011` is test-only code in
`fixture-basic/src/main/cpp/adversarial_native.cpp`, shared by the 64-bit and
32-bit fixture applications. It records, independently and in one JSON detail
string, the following paths:

`open`, `openat`, `openat2`, `faccessat`, `faccessat2`, `stat`, `fstatat`, the
xattr family, `getcwd`/`chdir`, `realpath`, pipe `ioctl(FIONREAD)`, libc and
`syscall()` variants, and a negative open of a foreign Host path. Raw
architecture instructions remain represented by `NATIVE-ADV-003` and the raw
ioctl branch in `NATIVE-ADV-011` where the ABI provides them. Existing cases
retain the `execve`, socket, loader, procfs, FD, binder, and policy-refusal
coverage.

The build must contain all four fixture ABIs:

| Build | ABIs |
|---|---|
| `fixture-basic` | `arm64-v8a`, `x86_64` |
| `fixture-compat32` | `armeabi-v7a`, `x86` |

The fixture does not link production interception libraries. The test observes
the product from outside its implementation and must not turn a raw-path
observation into a production security statement.

## Acceptance predicates

1. The static matrix and corpus gate passes, including every matrix row's
   required fields and the required C3 operations/cases.
2. Both direct fixture APKs return all eleven case IDs with no `ERROR` result;
   direct `NATIVE-ADV-011` is `PASS_COMPAT` and records the xattr limitation or
   success explicitly.
3. The in-sandbox probe returns all eleven case IDs. `NATIVE-ADV-011` may be
   `BYPASS_CONFIRMED` because its purpose is to record uncontrolled entrypoints;
   its foreign Host path must remain `negative_host_path=DENIED` through the
   existing controlled path.
4. Four ABI native libraries are present and hashed in the evidence.
5. `va_pro_equivalent` remains `NOT_PROVEN`; existing native known issues are
   carried forward and no new issue is hidden as a pass.

## Reproduction and evidence

Resolve MuMu by the exact instance name `RD测试`; the runner must use the
session-resolved serial from `scripts/mumu_instance.py` and may use
`MUMU_ROOT` to locate the installed MuMu root. It must not contain a historical
serial or hard-coded emulator endpoint.

Tracked acceptance summaries belong in
`verification/catch-up/C3-T01/`. Timestamped raw ADB/logcat artifacts belong in
the ignored `artifacts/capability-audit/` tree. The evidence records the
baseline commit, dynamic device identity, APK/native-library hashes, static
gate output, direct 64/32 results, in-sandbox result, known issues, and the
explicit non-claim that PLT coverage is not hostile isolation.
