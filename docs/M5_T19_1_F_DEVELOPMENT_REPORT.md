# M5-T19.1-F Direct Critical-Test Ownership

## Scope

This change closes P1-06 from the M5-T19 global review. It does not add sandbox capabilities.

## Result

- Replaced file-existence ownership mappings with live source analysis.
- Java comments and literal bodies are removed before direct constructor/static-call matching.
- Every mapped owner must expose an executable `main` test and appear exactly once in `tools/static_android_compile.py`.
- Added direct executable tests for `RuntimeGuestConnectionPool`, `PackageManagementSession`, and `PackageRuntimePermissionSession`.
- Retained the direct `PackageVirtualSystemServiceSession` immediate-death regression.
- Added a gate self-test proving comment-only, string-only, and indirect mappings are rejected.
- The generated ownership report records source/test paths, direct-reference status, runner execution count, P1-01 through P1-06 regression evidence, and a SHA-256 digest of scanned inputs.

## Evidence boundary

This is an executable critical-path ownership gate. It does not claim JaCoCo line coverage, branch coverage, Android instrumentation, Emulator, or physical-device evidence.
