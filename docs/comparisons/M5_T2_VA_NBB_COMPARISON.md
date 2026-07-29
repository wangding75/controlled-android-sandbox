# M5-T2 Comparison with VA and NBB

## Comparison basis

This report compares repository source capabilities. Device evidence remains 0. No claim is made about device compatibility, APK launch rate, OEM coverage or stability parity because M5-T2 has no Android build or device evidence.

| Capability | Controlled Sandbox M5-T2 | VA/NBB reference level | Current gap |
|---|---|---|---|
| 64-bit Guest routing | Production Host Runtime Broker route | Mature product/fork implementations | Device and API-version evidence missing |
| 32-bit architecture | Separate Companion APK and typed cross-package contracts | Established multi-process/ABI techniques | Real APK build and component execution not verified |
| 32-bit Activity/Service/Receiver/Provider | Source-wired to the production Companion Runtime Broker | Generally expected in mature virtualization bases | Android Binder, class loading, native loading and lifecycle order unverified |
| Artifact transfer | Bounded PFD transfer, SHA-256, atomic replacement, revision-scoped private workspace | Implementation-specific | Performance and large/split APK behavior unverified |
| Declared remote process | Existing process-name sessions and eight slots reused in Companion | Mature process mapping and slot management | Slot pressure and Android process recreation unverified |
| Isolated process | Early Broker and Guest fail-closed rejection | More mature implementations may provide broader process handling | Dedicated isolated UID/SELinux transport absent |
| Four ABI packaging | Source configuration present | Common target in mature Android virtualization projects | No real four-ABI APK artifact evidence |
| Security boundary | Signature permission, explicit components, PID/UID/process checks, revision and ABI scope | Varies by implementation | Device signature, package visibility and OEM behavior unverified |

## Judgment

M5-T2 closes the largest source-level gap left by M4-T17: 32-bit requests no longer stop at a probe-only blocker. The actual production Runtime Broker is now reachable in Companion32 and all four principal Android component classes use the same routing model.

VA/NBB still have stronger practical evidence because they have accumulated Android-version, device, application and linker compatibility work. Controlled Sandbox cannot be considered equivalent until real four-ABI builds, cross-package Binder execution, representative APK tests and stability runs pass.
