# M4-T17 Development Plan — Native Hook and ABI Architecture

## Baseline

- Formal starting point: `82148251767a29ef810ccabd0c359ac684a0e36e` (`m4-t16-source-pass`).
- Delivery rule: each batch must pass its scoped gates, fast-forward into local `main`, and produce a complete source ZIP, a full Git bundle, a patch, a report, a verification log, and SHA-256 checksums.
- Device/emulator execution is outside M4-T17. Source, host-native, contract, architecture, and reproducible-package evidence are required. Device evidence remains explicitly zero.

## ABI decision

M4-T17 establishes source/build support for all four Android ABIs:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`
- `x86`

The main Host APK carries 64-bit native runtime libraries (`arm64-v8a`, `x86_64`). A separate companion APK carries 32-bit native runtime libraries (`armeabi-v7a`, `x86`) and communicates through a typed, permission-protected Binder contract. This avoids claiming unsupported per-process mixed ABI behavior inside one APK.

## B1 — Native filesystem and dynamic loader hardening

**Execution status: PASS (`594f81d`)**

### Scope

- Add native interception and policy for:
  - `openat2`
  - `statx`
  - `renameat2`
  - `faccessat2`
  - `getdents64`
  - file-backed `mmap`
- Preserve confinement and reject cross-root rename/mapping.
- Virtualize `/proc/self/maps`, `/proc/self/cmdline`, and `/proc/self/status` without exposing Host private paths or Host identity.
- Add controlled `dlopen` and `android_dlopen_ext` path resolution.
- Restrict bare sonames to Guest native libraries and an explicit system-library allowlist.
- Model linker-namespace policy without claiming device-proven Android linker namespace injection.
- Keep Split APK native-library extraction conflict-safe.

### Acceptance

- Host-native wrappers and policy tests PASS.
- Existing native filesystem and hook tests remain PASS.
- New proc and loader tests PASS.
- Static architecture and reproducible source package gates PASS.

## B2 — Network identity and audio capture lifecycle

**Execution status: PASS (`336889e`)**

### Scope

- Expand network interception and policy for:
  - IPv4 and IPv6 connect
  - DNS resolution and reverse lookup
  - socket creation and selected socket options
  - proxy and network-security metadata virtualization
  - network-interface and Host identity redaction
- Add typed Native network identity snapshot and fail-closed policy.
- Add Java/Binder audio capture authorization model.
- Add native AudioRecord/AAudio/OpenSL/MediaRecorder entry policy where symbols are available.
- Stop active capture leases when RECORD_AUDIO permission or generation is revoked.

### Acceptance

- Network policy tests cover IPv4, IPv6, DNS, proxy and interface redaction.
- Audio lease tests cover grant, revoke, generation change and native gate behavior.
- Existing Runtime permission and AppOps gates remain PASS.

## B3 — Four-ABI build architecture and 32-bit companion

### Scope

- Keep Host native runtime restricted to 64-bit ABIs.
- Add a separate 32-bit companion APK/module for `armeabi-v7a` and `x86`.
- Add typed cross-width Binder contract with protocol, session, generation, virtual user, package revision and capability nonce.
- Add signature-level permission and explicit package/component binding contract.
- Add independent 32-bit native hook library and status endpoint.
- Add ABI selection DTO and routing policy for 64-bit in-process versus 32-bit companion execution.
- Add build/source checks for all four ABI artifacts and no silent fallback to a mismatched ABI.

### Acceptance

- Contract, manifest, module boundary and ABI policy checks PASS.
- Host/static Java compilation PASS.
- Native source compiles in host verification; Android ABI packaging is source/build configured but not device-proven.
- M4-T17 final stage report and VA/NBB comparison distinguish source evidence from device evidence.

## Final M4-T17 gate

- Full repository review for M4-T17 changes.
- All existing M4-T14, M4-T15 and M4-T16 regression gates PASS.
- Native and JNI tests PASS.
- Reproducible source packaging PASS.
- Local `main` is clean and tagged `m4-t17-source-pass`.
- Formal M4-T17 complete source ZIP and Git bundle are generated and independently verified.
