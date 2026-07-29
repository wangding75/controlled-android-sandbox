# M4-T17 B3 — Native ABI companion architecture

## Decision

The Host APK remains 64-bit-only for native runtime code:

- `arm64-v8a`
- `x86_64`

A separately installed companion APK contains the 32-bit native runtime:

- `armeabi-v7a`
- `x86`

Android does not support switching a running process between 32-bit and 64-bit. Keeping the 32-bit runtime in an independent package/process makes the width boundary explicit and prevents accidental loading of mismatched ELF binaries.

## Contract and trust boundary

`sandbox-contract` exposes `INativeAbiCompanion` with typed Parcelable request/result models. The request carries protocol version, session ID, generation, virtual user, package name, APK revision, requested ABI, operation and a 16–64 byte one-time capability nonce. No `Bundle` is used.

The companion service is exported only behind `com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION`, declared with `signature` protection by the Host. The Host binds an explicit package and component. The service rejects:

- unsupported protocol versions;
- replayed nonces;
- non-32-bit processes;
- ABI mismatch;
- stale generations;
- generation reuse with a different session, APK revision or ABI.

Both nonce and generation registries are bounded to avoid unbounded process memory growth.

## Build layout

- `sandbox-native`: Android library with `arm64-v8a` and `x86_64` only.
- `sandbox-companion32`: independent Android application with `armeabi-v7a` and `x86` only.
- `controlled_sandbox_native32`: independent shared library target built from the clean-room native policy/hook sources.
- The Host application has no Gradle dependency on the companion application; deployment remains two APKs signed by the same key.

## Runtime routing

`NativeAbiRoutePlanner` maps Java-only, `arm64-v8a` and `x86_64` Guests to the 64-bit Host. `armeabi-v7a` and `x86` require the explicit companion. Legacy or unsupported ABI metadata is rejected.

M4-T17 only establishes the source/build/contract architecture. A successful companion probe does not cause the Host Broker to execute a 32-bit Guest inside its 64-bit process. The current Runtime path fails closed with `NATIVE_COMPANION_CROSS_WIDTH_EXECUTION_NOT_WIRED` until the device-tested lifecycle transport is implemented.

## Evidence boundary

Host Java stubs compile the companion application and typed AIDL boundary. Host C++ checks compile the companion JNI source. Structural checks verify the four ABI split and no silent fallback. Android SDK packaging, same-signature installation, cross-package Binder on a device, and full 32-bit Guest lifecycle execution remain unverified.
