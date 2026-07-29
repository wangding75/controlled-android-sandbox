# M5-T4 Development Plan — Native Network, Dynamic Loader and Crash/ANR Closure

## Baseline

- Source baseline: `07b3c3164fc8dc0e1c67dcc707e2e0495a6aac7f`
- Branch: `feature/m5-t4-native-diagnostics`
- Real Android build and device execution remain outside the source PASS boundary while the current environment lacks the locked JDK 17 and Android SDK/NDK.

## Frozen scope

### Native network

- Extend Guest-only interception to socket close, bind, sendto, recvfrom, local/peer address queries and relevant socket options.
- Keep a bounded socket registry and fail closed at capacity.
- Project Guest-local interface name/index, addresses and connectivity metadata without leaking Host interfaces.
- Enforce IPv4/IPv6 endpoint policy, cleartext policy and sensitive socket-option denial.
- Preserve DNS, hostname, proxy and virtual network identity from M4-T17.

### Dynamic loader

- Validate Guest ELF magic, class, byte order, shared-object type and machine against the selected ABI.
- Validate `android_dlopen_ext` flags, library FD and offset, RELRO descriptors, reserved-address alignment and namespace ownership.
- Continue restricting libraries to the Guest native root or the explicit public system-library allowlist.
- Expose bounded loader audit status without weakening load failures.

### Crash and ANR diagnostics

- Install native fatal-signal handlers on an alternate signal stack.
- Record bounded PID/TID/signal/code/address plus virtual Session, generation, process and ABI evidence.
- Exercise a real fatal signal in a child-process fixture.
- Track ANR episode start, continuation and recovery rather than emitting unrelated watchdog samples.
- Export bounded thread dumps, rotated diagnostics and a SHA-256 manifest.

## Validation

- Native self-tests for socket policy, ELF/loader policy and real fatal-signal evidence.
- Static Android/Host tests for ANR episode state and evidence manifests.
- Existing M4-T14 through M5-T3 regression gates.
- Static Android compilation, Native/JNI compilation, strict evidence gates and reproducible source-package comparison.
- Real APK build is attempted but may remain blocked by the missing JDK 17 / Android SDK environment.

## Delivery

After PASS:

- fast-forward merge to local `main`;
- complete source ZIP;
- complete Git bundle;
- M5-T3 to M5-T4 patch;
- cumulative baseline patch;
- plan, development report, VA/NBB comparison, verification log and SHA-256 manifests.

## Execution result

**Execution status: PASS**

The frozen source scope is implemented. Full repository validation and artifact generation are recorded in the formal verification log and delivery package. Android build/device status remains separately blocked/not-tested.
