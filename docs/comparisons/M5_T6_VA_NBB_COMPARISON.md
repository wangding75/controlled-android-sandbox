# M5-T6 Comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

## Reference boundary

The comparison uses the preserved read-only snapshots under `ref/upstream`. No upstream code is copied into product modules.

## Source observations

| Area | VirtualApp snapshot | NewBlackbox snapshot | Controlled Sandbox M5-T6 |
|---|---|---|---|
| Process record | `server/am/ProcessRecord` tracks vuid/vpid/process/client | `core/system/ProcessRecord` and process manager track B-process state | Immutable Session/generation/slot records with explicit recovery states |
| Stub capacity | Virtual Stub process model | `ProxyManifest` exposes ordinary `:pN` proxy processes | Eight ordinary slots plus a separate four isolated slots |
| Android isolated worker | No explicit `android:isolatedProcess` worker route found in the preserved snapshot | No explicit `android:isolatedProcess` worker route found in the preserved snapshot | Four predeclared `android:isolatedProcess=true` Service workers |
| Component scope | General virtual component routing | General proxy component routing | Isolated route intentionally limited to Service; other types fail closed |
| Binder identity | Mature virtual client/app-thread channel | Mature BActivityThread/system channel | Typed Session/generation/component/revision plus per-lease capability token |
| Death recovery | Long-lived process management model | Process-manager and proxy lifecycle model | Explicit Binder-death transition to RECOVERING and generation advancement |
| Practical compatibility | Substantial historical Android app execution experience | Substantial project-specific compatibility work | Device evidence remains 0 |

## Interpretation

VA and NBB ordinary Stub process slots are useful references for process records, allocation and death cleanup, but an ordinary Stub process is not equivalent to a platform-assigned isolated UID. M5-T6 therefore keeps isolated workers in a distinct manifest and registry path rather than treating a normal Guest slot as isolated.

Controlled Sandbox is stronger in explicit typed identity, capability scoping, fail-closed component restrictions and source evidence. VA/NBB remain stronger in accumulated Android version, framework-hook and third-party application execution experience.

## Remaining gap

M5-T6 still requires a real Android build and Emulator/device run to prove UID separation, SELinux access, APK/data/native loading and AMS lifecycle. Device evidence remains 0, so no practical compatibility superiority is claimed.
