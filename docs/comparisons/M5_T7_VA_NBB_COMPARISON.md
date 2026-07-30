# M5-T7 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T7 |
|---|---|---|---|
| Manifest library metadata | Legacy parser-oriented retention | Package parser/proxy retention | Typed Java/native/SDK/static requirements with required/version/certificate fields |
| Required-library failure | Depends on branch/platform behavior | Depends on parser/runtime behavior | Deterministic fail-closed import and runtime validation |
| Optional library | Framework-compatible behavior varies | Framework-compatible behavior varies | Missing optional dependency retained without blocking |
| SharedLibraryInfo | Mature Android proxy history | Proxy/reflection-oriented | Version-tolerant factory; device constructor proof pending |
| Instrumentation metadata | Mature package model support | Package model support varies | Typed revision-bound declaration and PackageManager queries |
| Install sessions | Project-specific install path | Project-specific install path | Persisted typed state, progress, failure evidence and explicit retry |
| Inherit/rollback | Branch-dependent | Branch-dependent | Explicitly rejected until semantics are independently implemented |
| Android/OEM evidence | Strong historical execution | Stronger than current project | Device evidence remains 0 |

The reference trees were used only to compare capability boundaries and naming. Product implementation is independently authored against Android contracts. VirtualApp licensing in the uploaded snapshot remains unresolved, so no code was copied or mechanically translated.
