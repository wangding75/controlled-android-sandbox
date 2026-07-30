# M5-T9 — Window, ActivityClient, Input/IME and Display virtualization

## Goal

Expand the repository-owned interaction-service source surface before Android device execution. M5-T9 adds one
package/virtual-user interaction profile exposed only through revision-authorized Runtime sessions, Package-Service-owned persistence, typed Binder
management, process-local ownership ledgers and fail-closed framework proxy readiness for WindowManager,
WindowSession, ActivityClient, InputMethodManager and DisplayManager.

The bounded source domains are:

1. Window and WindowSession: session/token ownership, maximum-window limits, package-name rewriting, secure-flag
   policy, system-alert denial, display metrics and cleanup.
2. ActivityClient: source-side lifecycle observation, task-description state and reversible identity rewriting.
3. Input/IME: input-session ownership, EditorInfo package/user rewriting, host IME catalog hiding, picker policy
   and cleanup.
4. Display: deterministic display IDs and metrics, bounded virtual-display ownership and mutation denial.

## Modes and isolation

Window, Input/IME and Display use explicit `BLOCKED`, `STATIC` and `HOST` modes:

- `BLOCKED`: fail closed or return a neutral bounded value where the API cannot safely throw.
- `STATIC`: project the persisted virtual profile and avoid covered Host queries.
- `HOST`: explicitly use the Host implementation.

Profiles are keyed by package name and virtual-user ID; Runtime access remains bound to the immutable package revision of the active session. Updates use optimistic
version checks, survive Package-Service restart and notify active Runtime sessions. Package/instance deletion
removes the matching scope.

Process-local ownership is separate from persistent policy:

- Window sessions own window tokens.
- Input clients own input sessions.
- Activity tokens own lifecycle records.
- Display callbacks own virtual-display reservations.

All process-local state is closed when the Guest generation is torn down.

## Reference-source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` remain read-only. Their WindowManager,
WindowSession, ActivityClient, InputMethodManager and DisplayManager proxy surfaces are used only for architecture
and compatibility-pressure comparison. Product modules do not import, compile, package or mechanically translate
reference implementation classes.

## Reproducibility maintenance

M5-T9 also repairs the existing baseline-manifest entry point so a fresh Git Bundle restoration does not require a
manually created local `baseline/b3-t5a-upload` branch. The immutable baseline tag remains authoritative; an existing
local or `origin` baseline branch is checked for equality when present.

## Validation boundary

Host/static evidence proves typed contracts, validation, deterministic defaults, persistence, virtual-user isolation and revision-authorized Runtime
access, optimistic concurrency, corrupt-state quarantine, reversible argument rewriting, session ownership,
limit enforcement, lifecycle cleanup, static result projection, HOST passthrough and fail-closed launch readiness.

The following remain Android-execution dependent:

- real `IWindowManager`, `IWindowSession`, `IActivityClientController`, `IInputMethodManager` and
  `IDisplayManager` signatures across Android/API/OEM versions;
- SurfaceControl, InsetsState, InputChannel, WindowContext and Window token semantics;
- actual ActivityClient transactions, configuration delivery, multi-window, picture-in-picture and system Recents;
- IME rendering, InputConnection, focus transitions, keyboard process interaction, Autofill and Accessibility;
- hidden `DisplayInfo` layouts, virtual-display callbacks, MediaProjection and display-surface behavior;
- hidden-API enforcement, SELinux, Binder parcel layouts, Android SystemUI and OEM behavior.

M5-T9 stops at this boundary rather than claim Android compatibility without the locked JDK 17/SDK/NDK build and
Emulator/physical-device evidence.
