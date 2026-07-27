# Threat model

## Protected assets

- Host application private files and signing identity.
- Other imported Guest packages and virtual-user data.
- Device credentials and system services.
- Runtime control-plane authority and diagnostic evidence integrity.

## Current controls

- Runtime service, provider, Stub components and Guest process services are not exported.
- Same-application Binder caller validation.
- APK size limits, ZIP-entry count limits and binary-manifest bounds.
- ZIP path-traversal and duplicate native-entry protection.
- Canonical private-storage roots and per-user/package virtual paths.
- Atomic package and instance metadata writes.
- Session IDs, monotonic generations and one-time expiring route tokens.
- Per-process Guest class loader with parent-first platform/sandbox namespaces.
- Provider authority ownership, Cursor lease ownership and URI grant expiry/revocation.
- Native rebinding is scoped to modules loaded from the configured Guest native directory.
- Framework hooks are independently installed, diagnosed and rolled back in reverse order.
- Release packaging rejects missing Emulator evidence.

## Known gaps before device testing

- Hidden Android framework fields and Binder signatures are only statically compiled against local API stubs.
- AMS/ATMS do not yet virtualize every Intent, task, result and callback path.
- Native PLT/GOT changes have host tests but no Android linker/RELRO evidence.
- WebView profile isolation has source tests but no Renderer/GPU/utility-process evidence.
- Guest bytecode and native code remain untrusted code inside host-owned proxy processes; this is containment, not a hardware security boundary.
- OEM framework differences, anti-virtualization checks, packed applications and broad third-party compatibility are untested.
