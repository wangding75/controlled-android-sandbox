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
- Native rebinding is scoped to modules loaded from the configured Guest native directory and is classified as best-effort compatibility, not security isolation.
- Packaged ELF/native payloads are denied by default unless the install authority persists an explicit trusted-Native decision; the decision is rechecked at package-authority, Broker and Guest startup boundaries.
- Hook-mediated Native network receives stage payload, address and ancillary data in bounded temporary buffers. Denied datagrams are detected with `MSG_PEEK` and remain queued; rejected stream peers are checked before bytes are consumed.
- Framework hooks are independently installed, diagnosed and rolled back in reverse order.
- Release packaging rejects missing Emulator evidence.

## Known gaps before device testing

- Hidden Android framework fields and Binder signatures are only statically compiled against local API stubs.
- AMS/ATMS do not yet virtualize every Intent, task, result and callback path.
- Direct syscalls and inline assembly bypass Native PLT/GOT interception. Explicitly trusted Native Guests therefore remain outside a hostile-code security guarantee.
- APK-time ELF scanning cannot prove the absence of downloaded, generated or custom-loaded native code; arbitrary untrusted Native execution requires a separate UID/isolated execution architecture.
- Native PLT/GOT changes have Host tests but no Android linker/RELRO evidence.
- Direct socket syscalls, io_uring and other unhooked I/O aliases remain outside the Native network adapter. Rejected `accept/accept4` calls consume and close the accepted backlog entry even though no peer data is exposed.
- WebView profile isolation has source tests but no Renderer/GPU/utility-process evidence.
- Guest bytecode and native code remain untrusted code inside host-owned proxy processes; this is containment, not a hardware security boundary.
- OEM framework differences, anti-virtualization checks, packed applications and broad third-party compatibility are untested.
