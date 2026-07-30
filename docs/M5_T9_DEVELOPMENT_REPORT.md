# M5-T9 development report

## Result

- Source status: PASS
- Production status: PARTIAL — policy, Binder authority, ownership state, proxy installation and common query/
  lifecycle paths are source-wired; real Android transaction and rendering behavior remains device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 and Android SDK/NDK in the current environment
- Device evidence: 0

## Delivered

1. Added five typed Parcelable/AIDL contracts for Window policy, Input/IME profile, Display descriptors,
   Display profile and the aggregate interaction profile.
2. Added deterministic defaults and bounded atomic persistence keyed by package and virtual user, with Runtime access authorized against the active immutable
   package revision. Persistence includes size limits, CRC verification, atomic replacement, corrupt-file
   quarantine and optimistic version checks.
3. Added typed management get/set/reset methods, Runtime session retrieval and asynchronous observer invalidation.
4. Added process-local WindowSession/window-token, input-client, ActivityClient lifecycle and virtual-display
   ownership ledgers with limits and generation cleanup.
5. Added reversible LayoutParams-like and EditorInfo-like identity rewriting. Product code restores every modified
   argument after the Host call, including exceptional paths.
6. Added WindowManager/WindowSession projection for session wrapping, package identity, secure/system-alert policy,
   display size/rotation and token ownership.
7. Added ActivityClient source mediation for resumed/paused/stopped/destroyed/finish/top-resumed state and bounded
   task-description capture.
8. Added InputMethodManager source mediation for host-catalog hiding, picker denial, EditorInfo identity, input
   session ownership and cleanup.
9. Added DisplayManager source mediation for display IDs/metrics, virtual-display policy/limits and brightness/color
   mutation denial.
10. Added fail-closed Guest launch readiness for configured non-HOST Window, ActivityClient, InputMethod and Display
    domains.
11. Added Host regressions for persistence/reload/user isolation/version conflict/corrupt quarantine, session wrapping,
    argument restoration, token ownership, alert denial, ActivityClient cleanup, IME isolation, Display projection,
    HOST passthrough and readiness failures.
12. Preserved the frozen 113-category capability matrix; M5-T9 evidence is recorded separately and does not create
    device evidence.
13. Fixed frozen-baseline verification portability: a Bundle/source restoration now resolves the local baseline
    branch, its `origin` remote-tracking reference or the immutable tag without requiring a manually created branch.

## Reference review

The read-only VA/NBB snapshots were reviewed for WindowManager/WindowSession, ActivityClient,
InputMethodManager and DisplayManager proxy architecture. No reference source was imported into product modules.
Controlled Sandbox uses its own typed policy, Package-Service authority, revision-authorized Runtime access and ownership ledgers.

## Deferred to Android execution

- Android-version-specific service singleton fields and hidden Binder signatures;
- real WindowSession `add`/`relayout`/`remove`, Insets, InputChannel and Surface behavior;
- ActivityClient transaction ordering, configuration, multi-window, picture-in-picture and Recents behavior;
- real IME focus/InputConnection/keyboard rendering and Android 12–16 signature variants;
- real `DisplayInfo`, DisplayManager callbacks and virtual-display surface creation;
- hidden API, SELinux, SystemUI and OEM compatibility/stability claims.
