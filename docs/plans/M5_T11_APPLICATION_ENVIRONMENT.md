# M5-T11 — User, launcher, shortcut, widget, usage and settings virtualization

## Goal

Expand repository-owned application-environment virtualization before Android execution. M5-T11 adds one typed,
Package-Service-owned application-environment profile per package and virtual user, revision-authorized Runtime access,
bounded mutable data for shortcuts/widgets/usage/settings, and fail-closed framework proxy readiness for UserManager,
LauncherApps, ShortcutManager, AppWidgetManager, UsageStatsManager, ContentService and Settings Provider caches.

The bounded source domains are:

1. User: virtual user identity, serial number, name, flags, running/unlocked/quiet state and restrictions.
2. Launcher: package visibility, launcher-activity queries, callback policy and main-activity launch policy.
3. Shortcut: dynamic/pinned/manifest metadata, mutation, enable/disable, usage reporting and quotas.
4. AppWidget: host-scoped ID allocation, ownership, binding, options metadata and bounded updates.
5. Usage: bounded usage events, query windows, package isolation and standby-bucket projection.
6. Settings/Content: Secure/System/Global virtual namespaces, observer ownership and fail-closed sync surfaces.

## Modes and isolation

Every domain uses explicit `BLOCKED`, `STATIC` and `HOST` modes:

- `BLOCKED`: reject covered operations or return a neutral value where the API cannot safely throw.
- `STATIC`: project the persisted virtual profile and bounded virtual data without reading covered Host state.
- `HOST`: explicitly use the Host implementation.

Profiles and mutable data are keyed by package name and virtual-user ID. Runtime reads remain bound to the immutable
package revision of the active session. Updates use optimistic version checks, survive Package-Service restart and
notify active Runtime sessions. Package/instance deletion removes the matching scope.

Persistent data is bounded separately:

- shortcuts are constrained by per-activity and dynamic-shortcut limits;
- widget IDs and host counts are bounded and ownership checked;
- usage events have retention and count limits and reject cross-package reporting by default;
- settings entries are namespace/key bounded, block sensitive keys and enforce namespace-specific write policy.

## Reference-source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` remain read-only. Their UserManager, LauncherApps,
Shortcut, AppWidget, UsageStats, ContentService and Settings proxy surfaces are used only for architecture and
compatibility-pressure comparison. Product modules do not import, compile, package or mechanically translate
reference implementation classes.

## Validation boundary

Host/static evidence proves typed contracts, validation, deterministic defaults, persistence, virtual-user isolation,
revision-authorized Runtime access, optimistic concurrency, corrupt-state quarantine, quotas, shortcut/widget/usage/
settings lifecycle, reversible identity rewriting, observer ownership, HOST passthrough and fail-closed launch readiness.

The following remain Android-execution dependent:

- real hidden Binder signatures and singleton/cache fields across Android/API/OEM versions;
- concrete `UserInfo`, `LauncherActivityInfo`, `ShortcutInfo`, `AppWidgetProviderInfo`, `UsageEvents` and wrapper layouts;
- launcher callback ordering, package-change delivery and system launcher behavior;
- `RemoteViews`, Widget Host/SystemUI rendering and provider callbacks;
- real UsageStats permission/AppOps, event semantics and standby-bucket enforcement;
- Settings `NameValueCache`/Provider method variants, observer Binder transport and ContentResolver cache invalidation;
- sync adapter execution, account authority integration, hidden API, SELinux and OEM Settings/Launcher behavior.

M5-T11 stops at this boundary rather than claim Android application-environment compatibility without the locked
JDK 17/SDK/NDK build and Emulator/physical-device evidence.
