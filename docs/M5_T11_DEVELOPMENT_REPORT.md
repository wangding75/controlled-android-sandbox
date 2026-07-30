# M5-T11 development report

## Result

- Source status: PASS
- Production status: PARTIAL — typed policy, Binder authority, durable bounded data, common framework query/mutation
  projection and proxy readiness are source-wired; real Android hidden objects, launcher/widget callbacks, UsageStats
  enforcement and Settings/Content Provider behavior remain build/device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 and Android SDK/NDK in the current environment
- Device evidence: 0

## Delivered

1. Added eleven typed Parcelable/AIDL contracts for the aggregate application-environment profile, virtual user,
   launcher, shortcut policy/data, widget policy/data, usage policy/event and settings policy/value.
2. Added deterministic defaults that do not copy Host users, launcher packages, shortcuts, widgets, usage history or
   Secure/System/Global settings.
3. Added bounded atomic persistence keyed by package and virtual user, with size limits, CRC verification, atomic
   replacement, corrupt-file quarantine and optimistic policy-version checks.
4. Added Package-Service management get/set/reset methods, revision-authorized Runtime retrieval and asynchronous
   observer invalidation for profile and mutable data changes.
5. Added UserManager source projection for virtual identity, serial/name/flags, running/unlocked/quiet state,
   restrictions and application restrictions while denying unsupported Host mutations.
6. Added LauncherApps source projection for package visibility, launcher-activity queries, callback quota/policy and
   main-activity launch policy.
7. Added ShortcutManager source projection for list/replace/add/remove, enable/disable, usage reporting, quota queries
   and pin-request policy.
8. Added AppWidgetManager source projection for host-owned ID allocation/deletion, binding, provider metadata,
   bounded updates and listen/query paths.
9. Added UsageStatsManager source projection for bounded event reporting/query, package summaries and standby-bucket
   queries while denying system-wide mutation.
10. Added ContentService observer ownership and notification, fail-closed sync-query surfaces and mutation denial.
11. Extended Settings Provider identity mediation so Secure/System/Global reads, writes and deletes use virtual
   persisted namespaces; Android ID remains governed by the separate device-identity profile.
12. Added fail-closed Guest launch readiness for configured non-HOST User, Launcher, Shortcut, Widget, Usage,
   Content and Settings domains.
13. Added Host regressions for persistence/reload/user isolation/version conflict/corrupt quarantine, quotas,
   cross-package denial, framework object projection, shortcut usage, widget ownership, usage queries, observer
   dispatch, Settings namespace isolation, HOST passthrough and readiness failures.
14. Fixed `reportShortcutUsed(packageName, shortcutId, userId)` parsing so the package name cannot be mistaken for
   the shortcut ID.
15. Renamed new source types that accidentally contained the `VirtualApp` token so the clean-room architecture gate
   cannot confuse product code with the read-only VirtualApp reference namespace.
16. Preserved the frozen 113-category capability matrix; M5-T11 evidence is recorded separately and creates no
   device evidence.

## Reference review

The read-only VA/NBB snapshots were reviewed for UserManager, LauncherApps, Shortcut, AppWidget, UsageStats,
ContentService and Settings proxy architecture. No reference source was imported into product modules. Controlled
Sandbox uses its own typed aggregate policy, Package-Service authority, revision-authorized Runtime access and
bounded persistent mutable data.

## Deferred to Android execution

- Android-version-specific service singleton/cache fields and hidden Binder signatures;
- real framework object construction, ParceledListSlice/UsageEvents wrappers and callback parcel compatibility;
- launcher package callbacks, system launcher integration and start behavior;
- RemoteViews, AppWidget Host/SystemUI rendering and provider lifecycle;
- UsageStats permission/AppOps, standby enforcement and platform event types;
- Settings NameValueCache/ContentProvider variants, observer Binder delivery and cache invalidation;
- sync adapter/account integration, hidden API, SELinux and OEM compatibility/stability claims.
