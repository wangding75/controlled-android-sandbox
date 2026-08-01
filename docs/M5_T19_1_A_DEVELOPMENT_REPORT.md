# M5-T19.1-A Development Report — Native Guest Trust Boundary

## Scope

- Baseline commit: `2071974236f55d3a94aac40bb70d834cea590218`
- Baseline tree: `3e3ab0f3a10df53c5e42a7ffcd70e5ab84aef855`
- Branch: `fix/m5-t19-1-native-trust-boundary`
- Review finding: P1-01, direct Linux syscalls bypass Guest-library PLT/GOT rebinding.
- Capability matrix expansion: none.
- `ref/upstream` changes: none.

## Disposition

The finding is closed at the product admission boundary, not by claiming that symbol rebinding can mediate direct syscalls.

Packaged Native Guests now fail closed by default. A package containing a standard `lib/<abi>/*.so` entry or an ELF payload in another APK entry cannot be published unless its typed install session records `EXPLICITLY_TRUSTED`. Explicit Native trust also requires `USER_ACTION_REQUIRED`. Legacy package records with Native libraries and no trust metadata deserialize as `UNTRUSTED` and cannot start.

An explicitly trusted Native Guest is labelled `BEST_EFFORT_COMPATIBILITY`. It remains in the Host application UID on the ordinary route. PLT/GOT interception continues to provide compatibility and redirection only.

## Implementation

### Install authority

- `InstallSessionParamsSnapshot` adds the bounded Native trust values `UNTRUSTED` and `EXPLICITLY_TRUSTED`.
- Install-session persistence advances to schema 3 and migrates schema 1/2 sessions to `UNTRUSTED`.
- `ApkImportManager` scans all base/split artifacts for standard Native-library paths and ELF magic outside `lib/`.
- Direct import APIs retain their previous shape and default to `UNTRUSTED`.
- Native payload publication throws the stable code `UNTRUSTED_NATIVE_GUEST_DENIED` unless the install session is explicitly trusted.

### Persisted and typed metadata

- `SandboxRecord` and `PackageRecordSnapshot` carry:
  - `containsNativeCode`;
  - `nativeGuestTrust`;
  - `nativeExecutionMode`.
- Package JSON, install-session state and Binder parcel round trips preserve the trust decision.

### Runtime enforcement

The decision is rechecked at independent boundaries:

1. `RuntimeClient` before constructing a Runtime request.
2. `RuntimeBrokerService` while validating the request contract.
3. `GuestPackageSpec` before Guest environment bootstrap.
4. `PackageManagementService` against the authoritative installed record and requested package revision before issuing a generation-scoped virtual system-service capability.
5. UI launch controls disable records that fail the policy.

### Honest Native boundary

- Native headers, README, threat model and architecture documentation explicitly state that direct syscalls and inline assembly bypass PLT/GOT interception.
- No strong same-UID Native isolation claim is made.

## Regression evidence

### Host Java/runtime

- Untrusted packaged Native admission is denied with the stable error code.
- Explicitly trusted Native admission succeeds.
- Silent explicit trust without required user action is rejected.
- A standard `lib/<abi>/*.so` entry is detected.
- ELF magic outside `lib/` is detected.
- Legacy Native records fail closed.
- Package/install-session Parcelable and persistence round trips retain Native metadata.
- Guest specification rejects untrusted Native payloads.

### Host Native

`native_syscall_boundary_self_test.cpp` records the architectural limitation directly:

- `SYS_openat` bypasses imported-symbol file interception in the same UID.
- `SYS_connect` bypasses imported-symbol network interception.
- `SYS_sendto` bypasses imported-symbol network interception.

The test is a characterization regression. It must continue to pass because the product fix is default denial/explicit trust, not a false claim that PLT/GOT hooks intercept direct syscalls.

## Verification

- M5-T19.1-A dedicated gate: PASS.
- Static Android-source compilation and all registered Host Java/Runtime/Framework self-tests: PASS.
- Native main suite and JNI/Companion32 compile checks: PASS.
- M5-T2 through M5-T19 historical gates: PASS.
- Strict M3 gate: PASS.
- Reproducible source ZIP byte comparison: PASS.
- Shell, Python and PowerShell structural checks: PASS.
- Android Gradle/APK build, Emulator and physical-device evidence: not produced.

The monolithic verifier exceeded the execution environment's five-minute command window after all checks through Runtime Permission had passed. The remaining commands were continued in their original order. A second continuation was required during the long Native/static segment. All continued gates passed; no test failure was hidden by the segmentation.

## Residual risk

This task does not establish strong isolation for arbitrary hostile Native execution. APK-time scanning cannot prove the absence of:

- native code downloaded after installation;
- generated or decrypted ELF payloads;
- a custom user-space loader;
- direct syscall or inline-assembly behavior in an explicitly trusted Native Guest.

Closing those cases requires a separate-UID/isolated execution architecture and Broker-only access to Host files and network capabilities. Until then, explicitly trusted Native execution remains `BEST_EFFORT_COMPATIBILITY`.

## Status

- Source fix: PASS.
- Product admission policy for packaged/legacy Native Guests: PASS.
- Strong hostile-Native isolation: not claimed.
- Production/device status: PARTIAL, device evidence 0.
