# M5-T2 Development Report — 32-bit Guest Runtime and Remote/Isolated Process

## Result

**PASS for source implementation and locally executable verification.**

M5-T2 replaces the previous Companion probe-only route with an actual production Runtime Broker path for `armeabi-v7a` and `x86` guests. It does not claim a successful Android APK build, emulator run or physical-device run.

## Baseline

- Base: M5-T1 commit `5555afdd2a518e4921dd13949ad56caea2726f1c`
- Branch: `feature/m5-t2-cross-width-runtime`
- Device evidence: 0
- Android build status in this environment: blocked by JDK 21 and missing locked SDK/NDK

## Implemented

### Cross-width artifact transport

- Added typed `INativeCompanionArtifactService` AIDL.
- Added bounded `NativeCompanionArtifactRequest` and `NativeCompanionArtifactResult` Parcelable contracts.
- Transfers Base APK, Split APKs and selected `.so` files through read-only `ParcelFileDescriptor` handles.
- Verifies exact byte count and SHA-256 before atomic replacement.
- Enforces canonical path containment, protocol 1, 512 artifacts per workspace, 64 workspaces, 512 MiB per artifact and 1 GiB per workspace.
- Scopes workspaces by package, virtual user, APK revision and 32-bit ABI.

### Production 32-bit runtime route

- Companion32 now embeds the production `sandbox-runtime` module.
- Companion Runtime Broker is exposed only through the signature permission and an explicit component.
- Host routes `prepareGuest`, Activity launch, Service, Receiver, Provider and stop operations to Companion Runtime Broker for `armeabi-v7a` and `x86`.
- `arm64-v8a` and `x86_64` remain on the Host Runtime Broker.
- Unknown or missing ABI metadata fails closed.
- Companion Binder clients recover from service disconnection or Binder death and can bind again.

### Package authority and virtual services

- Host PackageManagementService is exported only under the signature-level Companion permission.
- Cross-package capability sessions additionally validate caller PID, UID and the exact Companion Broker process name.
- Companion Runtime permission and virtual-system-service clients bind explicitly to the Host package authority.

### Remote and isolated process handling

- Existing declared remote process names continue through process-name keyed sessions and eight process slots in both Host and Companion runtimes.
- Isolated components are rejected by the Broker before an ordinary Guest process is prepared or allocated.
- The existing Guest-side isolated policy remains as a second defense.
- Dedicated Android isolated UID/SELinux transport is still blocked because it requires a separate execution architecture and real platform validation.

### ABI build structure

- Host app explicitly limits packaged ABIs to `arm64-v8a` and `x86_64`.
- Companion app remains limited to `armeabi-v7a` and `x86`.
- Shared native policy sources build for all four ABIs; Companion retains the separate `controlled_sandbox_native32` bridge.
- `NativePolicy` loads the standard policy library first and falls back to the Companion-specific bridge.

## Verification

PASS:

- M5-T2 cross-width runtime source gate.
- Typed AIDL and Parcelable contract checks.
- Architecture and package-boundary checks.
- Package Service signature/caller boundary checks.
- Static Android compilation with local stubs.
- All Host self-tests, including new Broker isolated-route test.
- M4-T14 through M4-T18 regression gates.
- M5-T1 build-contract regression.
- Native filesystem, procfs, loader, network, audio, PLT and crash tests.
- Host and Companion JNI source boundary compilation.
- Strict M3 evidence gate.
- Reproducible source ZIP byte comparison.
- Shell, Python and PowerShell structural checks.

The monolithic verifier was split only because of the execution platform's single-command time limit. The first segment reached the capability matrix with no failure; continuation segments completed static Android, Native, strict evidence, reproducible packaging and script checks.

## Capability accounting

- `runtime.native-abi-routing`: upgraded from `partial` to `wired` based on source production routing.
- `native.four-abi-build-architecture`: remains `partial`; real APK packaging is not proven.
- `process.declared-isolated-planning`: remains `blocked`; early fail-closed routing is implemented, dedicated isolated UID execution is not.
- Device verified capabilities remain 0.

## Remaining gaps

1. Locked Android build producing and validating Host, Fixture and Companion APKs.
2. Same-signature Host/Companion installation and cross-package Binder test.
3. Real x86_64 Host plus x86 Companion component lifecycle test.
4. Real arm64 Host plus armeabi-v7a Companion component lifecycle test.
5. Dedicated isolated-process UID/SELinux runtime.
6. Binder death and process-restart behavior under Android LMK/OEM conditions.
7. Twenty-minute zero-crash/zero-ANR stability evidence.
