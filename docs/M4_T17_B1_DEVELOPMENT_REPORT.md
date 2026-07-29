# M4-T17 B1 Development Report — Native Filesystem and Loader

## Result

PASS

## Baseline and commit

- Starting baseline: `82148251767a29ef810ccabd0c359ac684a0e36e`
- Functional commit: `594f81d07d5b0e32e23f6f0bc1ca8577062db021`
- Scope: M4-T17 B1 only
- Device/emulator testing: not performed; device evidence remains zero

## Delivered source capabilities

### Modern filesystem interception

The native interception layer now covers `openat2`, `statx`, `renameat2`, `faccessat2`, `getdents64`, and file-backed `mmap`. File-descriptor based paths are resolved through the sandbox policy. Cross-confinement rename is rejected and Host-private mappings fail closed.

The hook scanner remains in `native_hook.cpp`; syscall replacement and policy dispatch moved into `native_interceptors.cpp`. This reduced the scanner file to approximately 291 lines and avoids adding another native God Class.

### `/proc/self` virtualization

`/proc/self/maps`, `/proc/self/cmdline`, and `/proc/self/status` are generated as bounded read-only snapshots under the Guest instance runtime directory. Guest APK, native-library and data paths receive Guest-visible aliases. Other Host-private data and storage paths are redacted. Virtual process name, PID and UID are projected into the snapshots.

### Dynamic loader policy

`dlopen` and `android_dlopen_ext` now use a dedicated loader policy. Guest bare sonames resolve only beneath the immutable Guest native-library root. System sonames and paths require explicit allowlisting. Main-program handles, traversal, Host-private paths, unknown sonames and foreign linker namespaces are rejected.

### Explicit ABI metadata

The selected APK ABI is now stored explicitly in the package catalog, typed package Binder snapshot and Runtime launch contract. Runtime no longer infers ABI from the final native-library directory name, because the immutable package layout stores libraries under a directory named `lib`. Native records lacking ABI metadata are marked `legacy-unknown` and fail closed at Guest launch until re-imported.

## Verification

PASS results include:

- architecture boundary checks
- typed contract checks
- native filesystem hook architecture checks
- M4-T17 B1 source gate
- static Android-source compilation and all Host self-tests
- native filesystem resolver self-test
- native policy self-test
- procfs virtualization self-test
- dynamic loader policy self-test
- extended PLT hook self-test
- native crash recorder self-test
- JNI boundary compilation
- strict M3 evidence gate

## Limitations

- No Android device or emulator execution occurred.
- `openat2` and newer syscall availability still depends on Android kernel/API behavior at runtime.
- Android linker namespace injection is represented as a restrictive policy boundary; device-specific linker namespace behavior is not proven.
- Existing persisted Native package records without explicit ABI metadata require package re-import before Guest launch.
- B2 network identity and audio capture work is not included in this batch.
- The 32-bit Companion APK and cross-width Binder contract are deferred to B3 as frozen.
