# T57-R03-P0A-02 Isolated Hostile Boundary POC Result

RESULT: PASS

This report is `RD_BASELINE_NATIVE_ENFORCEMENT_POC`.

It is not `ANDROID_MATRIX`, OEM, or `VA_PRO_EQUIVALENT`.

## Git

- branch: `feature/t57-r03-va-pro-capability-campaign`
- baseline HEAD: `99dcdc5f158004ba8e791fae7e38f4bf4f77c26b`
- baseline TREE: `161c890f21bf16469b891c67b6b0073d6815a767`
- parent T57-R03-01: `ed7b370cff4fffe828be1f821286ff1bcd10dcfb`
- parent main: `9e5d3e73628d80872c21776897898493925c7a97`
- campaign commit / tree: see `git log -1 --format="%H %T"` after the single commit

No amend, rebase, squash, force-push, merge, or push.

## Process Isolation

Host debug isolated Service `NativeEnforcementIsolatedService`
(`android:isolatedProcess="true"`, not exported). Not a virtual guest Service.
`KI-R03-NATIVE-010` was not touched.

RD session `artifacts/capability-audit/native-enforcement/20260817T073257Z`:

| Role | UID | PID | processName |
|---|---|---|---|
| Host / Broker | 10193 | 6254 | `com.warden.controlledsandbox.debug` |
| Isolated child | 99024 | 6290 | `com.warden.controlledsandbox.debug:native_enf_iso:com.warden.controlledsandbox.NativeEnforcementIsolatedService` |

Proof: child UID is in the isolated-app range and is not the host app UID.
POC is valid.

Fixture32 isolated seccomp used UID `99026` / PID `6501` / ABI `x86`.

This proves Android OS isolated-UID primitives. It does **not** prove CAS
guest runtime production wiring.

## Filesystem

| Path | Result |
|---|---|
| libc `open` | `DENIED_BY_KERNEL_POLICY` (errno 2 ENOENT) |
| `syscall(SYS_openat)` | `DENIED_BY_KERNEL_POLICY` (errno 1 EPERM) |
| raw `syscall` / `int $0x80` | `DENIED_BY_KERNEL_POLICY` (errno 2 ENOENT) on x86_64 |
| Broker opaque capability | `PASS_CAPABILITY` (session token match) |
| Guess / traversal / mismatch | `DENIED` |

arm64 raw SVC: compiled, `UNVERIFIED_RUNTIME` on this RD device.

Conclusion: **PROVEN**. `BROKER_FS_CAPABILITY=PROVEN_ON_RD`.

Enforcement is kernel UID / SELinux / isolated process, not a PLT hook.

## Network

| Path | Result |
|---|---|
| libc socket/connect loopback | `DIRECT_ALLOWED` |
| `syscall(SYS_socket/SYS_connect)` | `DIRECT_ALLOWED` |
| raw socket/connect | `DIRECT_ALLOWED` |
| Broker-mediated request | `PASS_CAPABILITY` (nonce match) |

Conclusion: **PARTIAL**. `BROKER_NET_CAPABILITY=PARTIAL/NOT_ENFORCED`.

Option B isolated process alone is not a network hostile boundary on an
ordinary APK that holds `INTERNET`. Recorded as `KI-R03-NATIVE-ENF-001`.
No production connect hook was added.

## Seccomp

Dedicated isolated test process only. Not installed on the host Broker,
runtime, or guest Zygote child.

| ABI | prctl NO_NEW_PRIVS | filter install | filtered getppid | live getpid | signal | classification |
|---|---|---|---|---|---|---|
| x86_64 host isolated | 0 | 0 | -1 / EPERM | alive | 0 | `SECCOMP_FILTER_FEASIBLE` |
| x86 fixture32 isolated | 0 | 0 | -1 / EPERM | alive | 0 | `SECCOMP_FILTER_FEASIBLE` |
| arm64-v8a | compiled | — | — | — | — | `UNVERIFIED_RUNTIME` |
| armeabi-v7a | compiled | — | — | — | — | `UNVERIFIED_RUNTIME` |

Filter: classic BPF, `getppid` → `SECCOMP_RET_ERRNO|EPERM`, other syscalls
ALLOW. Forked child. Process exited normally. Policy disappears with the
process.

Conclusion: **FEASIBLE**. `SECCOMP_FILTER_FEASIBLE_ON_RD`.

This is fixture-level ordinary-APK feasibility. It is not VA Pro seccomp
mode and not a production installer.

## Architecture

- Option B filesystem: **PROVEN** on ordinary APK isolated UID.
- Option B network: **PARTIAL**. Direct connect still works. Split Network
  Boundary next.
- Option C: **FEASIBLE** as fixture-only hardening. Not a product switch.
- Option D user-notify / Binder-driver mediation: still
  `REQUIRES_PRIVILEGE`. Not implemented.
- Option E privileged companion: still a deployment SKU, not an ordinary
  APK feature.

Case B from the task: next step is P0A-03 architecture with Network
Boundary split. Hostile boundary is not complete.

## Known gaps

- Binder driver `/dev/binder*` still unmediated in Native.
- Custom mmap loader still `NOT_INTERCEPTED`.
- execve / ptrace still not a hostile control.
- procfs virtualization still known-leaves only.
- FD delegation (`NATIVE-ENF-FD-001`) deferred to P0A-03.
- arm64 runtime raw SVC / seccomp: compiled, `UNVERIFIED_RUNTIME`.
- CAS guest isolated-hostile production wiring: not proven
  (`KI-R03-NATIVE-010` unchanged).
- Isolated UID still has `INTERNET` (`KI-R03-NATIVE-ENF-001`).

## RD测试

- MuMu instance name: `RD测试`
- dynamic ADB serial: `127.0.0.1:16416` (session-resolved via
  `scripts/mumu_instance.py` + `adb devices -l`; not a script constant)
- API: 32
- ABI list: `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi`
- executed: host x86_64 isolated FS/Net/Seccomp; fixture32 x86 isolated
  seccomp
- boot_id: `2cb21625-099a-42d9-aa93-f6ddc52793f7`
- android_id: `398eea33120cd887`
- model: `22041211A`
- evidence: `artifacts/capability-audit/native-enforcement/20260817T073257Z/`
  (gitignored)

This only represents `RD_BASELINE_NATIVE_ENFORCEMENT_POC`.

## Build

`gradlew.bat :app:assembleDebug :sandbox-native:assembleDebug :sandbox-companion32:assembleDebug :fixture-basic:assembleDebug :fixture-compat32:assembleDebug`

BUILD SUCCESSFUL. CXX5202 remains `EXPECTED_ARCHITECTURAL_WARNING`. 32-bit
ABI was not removed.

| APK | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `b16bd970583e72906304974a085f710498ce08e828b741b10276e6c76ded00d4` |
| `sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk` | `91110eb1d9c24d3d8a8607c3e39cf089fda24a0cceb232e72fd4d73aedbe7a2c` |
| `fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk` | `a8c9c5172574e9a84a800e986530dedd584d270eb672c6faad3ae353b8fc15c6` |
| `fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk` | `8c823724d92766722e536eab1c50938dc08f34b317ab704ca5c68c69507d0527` |

`libcas_native_enf.so` compiled for arm64-v8a, x86_64, armeabi-v7a, x86.

## Local Native Audit

`python tools/capability/run_local_capability_audit.py --campaign native`

Latest classified run: `artifacts/capability-audit/native/20260817T072254Z`

- total: 11
- PASS: 8
- KNOWN_ISSUE: 3 (`KI-M10-001`/`KI-M10-002`, `KI-R03-023`, `KI-M10-005`)
- NEW_REGRESSION: 0
- FAIL: 3 (all classified KNOWN_ISSUE)

Existing M10 Known Issues were not fixed.

## Production runtime files changed

`PRODUCTION_RUNTIME_FILES_CHANGED`: **0**

Not modified:

- `sandbox-native` production interceptor logic
- `sandbox-runtime` core
- `sandbox-framework` business runtime
- production Host/Broker security policy
- production Manifest official component surface

Test-only / infrastructure:

- `app/src/debug/` isolated Service, Broker, campaign, JNI
- `app/src/debug/AndroidManifest.xml` isolated Service
- `app/build.gradle` debug CMake for `libcas_native_enf.so`
- fixture isolated seccomp + isolated-safe `FixtureApplication` skip
- `tools/static_android_compile.py` stub APIs used by debug sources
- `tools/capability/` runner, gate, tests
- docs / verification native probe

Release `app/src/main/AndroidManifest.xml` does not contain the POC
Service.

## Maturity

- maturity: `RD_BASELINE_NATIVE_ENFORCEMENT_POC`
- VA Pro equivalent: `NOT_PROVEN`

## Next recommended task

`T57-R03-P0A-03` after this evidence:

1. Do not claim hostile boundary complete.
2. Split Network Boundary (isolated UID net policy / Broker-only net /
   privileged enforcement).
3. Optional FD-delegation design (`NATIVE-ENF-FD-001`).
4. Production isolated-hostile admission only after the network split.
