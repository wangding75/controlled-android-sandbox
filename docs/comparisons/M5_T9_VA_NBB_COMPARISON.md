# M5-T9 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

This comparison uses the vendored read-only snapshots under `ref/upstream`. It compares source architecture and
coverage only. Historical VA/NBB execution does not become evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T9 |
|---|---|---|---|
| Policy ownership | Proxy-centric state and legacy virtual-core ownership | Proxy-centric state varies by fork | Typed aggregate per package + virtual user + immutable revision, owned by Package Service |
| WindowManager | `WindowManagerStub` rewrites package/UID and proxies WindowSession | `IWindowManagerProxy` plus `IWindowSessionProxy` | Reversible identity policy, static metrics, screen-control denial and returned-session wrapping |
| WindowSession | Broad method proxy coverage across legacy Android branches | Dedicated session proxy and package substitution | Session/token ownership, maximum-window limit, secure/system-alert policy and cleanup ledger |
| ActivityClient | Mainly ActivityManager/ActivityTask/Instrumentation-era mediation | Dedicated `IActivityClientProxy` for newer Android | Process-local lifecycle/task-description mediation; real platform transaction matrix deferred |
| Input/IME | `InputMethodManagerStub` package/UID rewriting | `IInputMethodManagerProxy` with version-specific methods | Host catalog hiding, picker policy, EditorInfo rewriting and input-session ownership |
| Display | Limited/branch-specific DisplayManager interception | `IDisplayManagerProxy` and display identity rewriting | Deterministic display IDs/metrics, creation limits and sensitive mutation denial |
| State cleanup | Established through process/client proxy lifecycle | Fork-specific Binder/proxy cleanup | Explicit generation-scoped ledgers for windows, input clients, activities and display callbacks |
| Failure behavior | Historical compatibility often favors passthrough | Fork behavior varies | Explicit `BLOCKED`/`STATIC`/`HOST`; missing required proxy blocks Guest launch |
| Persistence safety | Legacy/project-specific | Fork-specific | Bounded atomic state, CRC, quarantine, optimistic versioning and revision cleanup |
| Android/OEM evidence | Strong historical use but old-version constraints | Broader recent hook set; fork quality varies | Device evidence remains 0 |

## Current comparative judgment

- M5-T9 closes the main source-architecture gap for Window, ActivityClient, Input/IME and Display ownership.
- Controlled Sandbox is stronger in typed policy, virtual-user isolation and revision-authorized access, reversible mutation, explicit limits and
  source/device evidence separation.
- VA/NBB remain stronger in accumulated Android-version/OEM Binder signatures, Window/IME rendering behavior,
  Surface/InputChannel workarounds and real application compatibility evidence.
- Source wiring does not establish parity for multi-window, picture-in-picture, keyboard rendering, Autofill,
  Accessibility, virtual-display surfaces or SystemUI behavior.
