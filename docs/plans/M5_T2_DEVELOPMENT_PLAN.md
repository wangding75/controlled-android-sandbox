# M5-T2 Development Plan — 32-bit Guest Runtime and Remote/Isolated Process

## Baseline

- Base commit: `5555afdd2a518e4921dd13949ad56caea2726f1c`
- Source baseline: M5-T1 build-pipeline source PASS
- Device evidence remains outside source acceptance.

## Scope

1. Run the existing Guest Runtime inside the independent 32-bit Companion APK.
2. Transfer base APK, split APKs, and selected native libraries through a signature-protected, bounded, typed Binder service.
3. Route prepare, Activity launch, Service, Receiver, Provider, status, and shutdown operations to the Companion Runtime Broker for `armeabi-v7a` and `x86` guests.
4. Preserve `arm64-v8a` and `x86_64` routing to the 64-bit Host Runtime Broker.
5. Support declared remote processes through the existing per-process session/slot model.
6. Add an isolated-process route model that never silently executes isolated components as ordinary Guest processes.
7. Bind all cross-package calls to protocol, package, virtual user, APK revision, process name, generation, ABI, and signature permission.
8. Add capacity, size, hash, traversal, replay, death, revision, and cleanup controls.

## Acceptance

- No silent 32-bit-to-64-bit fallback.
- Companion APK depends on the production Runtime and Framework modules.
- Host APK packages only 64-bit native libraries; Companion APK packages only 32-bit native libraries.
- Artifact staging rejects oversized, mismatched, duplicated, unsafe, or stale artifacts.
- Companion Runtime Broker is exported only under the signature permission.
- Host Package Service accepts only the signed Companion Runtime Broker process for cross-package capability sessions.
- Static Android compile, Host tests, Native tests, M4 regression gates, M5 gates, and reproducible source packaging pass.
- Real APK build and emulator/device execution remain separately reported when the locked Android toolchain is unavailable.

## Execution Result

**Execution status: PASS (source and locally executable gates)**

- 32-bit Activity, Service, Receiver and Provider requests now route to the production Runtime Broker embedded in Companion32.
- Base APK, Split APK and selected native libraries are transferred through a bounded typed Binder file channel into Companion-private storage.
- Declared remote process names continue through the existing process-name/session/slot allocation model in both Host and Companion runtimes.
- Declared isolated components are rejected before ordinary process-slot allocation. Dedicated Android isolated-UID execution remains blocked and is not represented as complete.
- Real APK build remains blocked by the current environment's JDK 21 and missing Android SDK/NDK. Device evidence remains zero.
