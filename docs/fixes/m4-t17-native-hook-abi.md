# M4-T17 Native Hook and ABI Architecture

## B1 completed scope

- Added Guest-module-only PLT/GOT targets for `openat2`, `statx`, `renameat2`, `faccessat2`, `getdents64`, file-backed `mmap` and `android_dlopen_ext`.
- Reused the authoritative native session/generation policy and extended its identity snapshot with process name, virtual UID, virtual PID and ABI.
- Added cross-confinement rename rejection and leaked Host file-descriptor rejection.
- Added bounded read-only virtual `/proc/self/maps`, `/proc/self/cmdline` and `/proc/self/status` snapshots.
- Added a dynamic-loader policy that resolves Guest sonames inside the immutable native-library root, permits an explicit public system-library list and denies Host/private/unknown paths.
- Preserved Guest-only module scanning; Host and system modules are not patched.

## Security properties

- New path operations fail closed while policy is unconfigured or stale.
- `openat2` kernel resolution flags are preserved except `RESOLVE_BENEATH`/`RESOLVE_IN_ROOT`, whose confinement is enforced before the host syscall on the rewritten absolute path.
- `renameat2` cannot cross the virtual instance/native confinement boundary.
- File-backed `mmap` validates the backing descriptor against the Guest-visible path policy.
- `/proc/self/maps` caps source and output at 2 MiB and redacts non-Guest `/data` and `/storage` mappings.
- `dlopen(NULL)` and foreign `android_dlopen_ext` namespaces are denied.

## Evidence boundary

Host-native tests exercise the wrappers and policies. No Android Emulator or physical device was used, so Android linker namespace, seccomp, syscall availability and OEM behavior remain unverified.
