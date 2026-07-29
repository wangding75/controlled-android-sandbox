# M5-T4 Development Report — Native Network, Dynamic Loader and Crash/ANR Closure

## Status

- Source status: PASS
- Production source wiring: PASS
- Android APK build: BLOCKED by the current toolchain environment
- Device evidence: 0
- Base commit: `07b3c3164fc8dc0e1c67dcc707e2e0495a6aac7f`

## Delivered source capabilities

### Native network

- Tracks Guest sockets in a bounded 2,048-entry registry and removes ownership on `close`.
- Intercepts socket creation, bind/connect, sendto/recvfrom, local/peer address queries and relevant socket options.
- Projects virtual local IPv4/IPv6 identity while rejecting foreign local-address binding.
- Enforces endpoint and known cleartext-port policy for IPv4 and IPv6.
- Virtualizes interface name/index and prevents Host-interface selection through `SO_BINDTODEVICE`.
- Denies sensitive Host-network mutation such as socket marks and transparent proxy options.
- Exposes bounded audit counters and virtual Connectivity metadata: network ID, transport, VPN, metered, validated, MTU, proxy, private DNS and DNS count.

### Dynamic loader

- Verifies ELF magic, class, byte order, shared-object type and machine for arm64-v8a, armeabi-v7a, x86_64 and x86.
- Validates library-FD offsets and regular-file boundaries before loading.
- Restricts `android_dlopen_ext` flags to the supported set.
- Validates reserved-address alignment and incompatible flag combinations.
- Validates RELRO read/write descriptor policy and rejects foreign linker namespaces.
- Preserves Guest-root and public-system-soname resolution boundaries.
- Exposes allowed/denied loader audit status.

### Crash and ANR diagnostics

- Installs native fatal handlers for SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV and SIGTRAP on a 128 KiB alternate stack.
- Writes bounded JSON evidence containing timestamp, PID, TID, signal, `si_code`, address, Session, generation, process and ABI.
- Rotates native evidence and exercises a real SIGSEGV in a forked Host fixture.
- Captures bounded Java uncaught-exception evidence.
- Tracks ANR episode start, continuation and recovery with episode ID, max delay, sample count and duration.
- Emits bounded thread dumps and rotated diagnostics with a SHA-256 export manifest.
- Publishes native network and loader status through Guest runtime diagnostics.

## Source architecture

- `native_network.cpp` owns bounded socket identity and endpoint policy.
- `native_loader.cpp` owns path, ELF and `android_dlopen_ext` validation.
- `native_interceptors.cpp` remains the PLT/GOT adapter and delegates policy decisions.
- `native_crash.cpp` owns async fatal-signal evidence.
- `AnrEpisodeTracker` owns Android-independent ANR episode transitions.
- `RuntimeDiagnostics` owns Java evidence rotation and export.

No Android system-service or Host-network fallback was added.

## Verification

New and updated tests cover:

- IPv4/IPv6 endpoint policy, bind projection, socket options, interface identity and audit counts;
- ELF ABI mismatch, library-FD offset, RELRO, reserved-address, extension-flag and namespace rejection;
- real child-process SIGSEGV evidence;
- ANR episode transitions and evidence-manifest hashing;
- JNI status entry points and the expanded native hook symbol set.

All existing M4-T14 through M5-T3 source/Host regression gates remain required by `scripts/verify-all.sh`.

## Evidence boundary

This report proves source structure, Host-native execution, static Android compilation and Host-test behavior. It does not prove Android Bionic/linker/API-level behavior, VPN/ConnectivityService integration, OEM socket policy, tombstone/DropBox correlation, AMS ANR classification or real APK execution.

The current execution environment still has JDK 21 instead of the locked JDK 17 and lacks the Android SDK/NDK, so real APK build remains separately blocked.
