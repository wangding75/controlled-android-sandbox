# M5-T4 Comparison with VirtualApp and NewBlackbox

## Scope

This comparison is limited to source-visible Native network, dynamic-loader and diagnostics behavior. It does not infer device compatibility from README claims in this repository, VirtualApp or NewBlackbox.

| Capability | Controlled Sandbox M5-T4 | VirtualApp / NewBlackbox reference position | Remaining gap |
|---|---|---|---|
| Socket interception | Source-wired socket lifecycle, bind/connect, sendto/recvfrom, address projection and sensitive option policy | Mature projects have broader device/API compatibility accumulated through deployed hooks | Android Bionic symbol/API/OEM evidence |
| Network identity | Synthetic interface/index, IPv4/IPv6 and Connectivity metadata; Host interface selection rejected | Mature systems typically combine Java, Binder and Native network mediation | VPN, ConnectivityService, Network Security Config and OEM behavior |
| Loader path and ELF policy | Guest-root/system-allowlist plus ELF class/machine/type and FD-offset validation | Mature sandboxes contain API-specific linker adaptations | Real linker namespace, compressed APK, Split and RELRO behavior |
| `android_dlopen_ext` | Bounded flag, FD, offset, RELRO, reserved-address and namespace validation | VA/NBB-style projects commonly maintain multiple Android linker variants | Android-version matrix and actual ABI loading |
| Native crash evidence | Alternate stack, fatal-signal JSON and real Host SIGSEGV fixture | Mature products usually integrate tombstone/logcat/device crash collection | Android tombstone, DropBox and process-death correlation |
| ANR evidence | Episode state, bounded thread dumps, rotation and SHA-256 manifest | Mature products have system-level ANR and OEM evidence collection | AMS ANR reason, traces and real UI-thread stalls |

## Judgment

M5-T4 closes three previously explicit source-wiring gaps:

- `native.network-hook`
- `native.dynamic-loader-hook`
- `diagnostics.crash-anr-events`

The source evidence is materially stronger than the prior partial implementations because the new boundaries are executable in Host-native fixtures and are enforced by fail-closed policy. VirtualApp and NewBlackbox retain the stronger practical position for Android-version, OEM and third-party application compatibility because this project still has no device evidence.

## Device evidence

Device evidence remains 0. Four-ABI APK production, Android linker behavior, VPN/Connectivity, tombstone/DropBox integration and OEM differences remain unresolved.
