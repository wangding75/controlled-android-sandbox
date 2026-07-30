# M5-T11 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

This comparison uses the vendored read-only snapshots under `ref/upstream`. It compares source architecture and
coverage only. Historical VA/NBB execution does not become evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T11 |
|---|---|---|---|
| Policy ownership | Virtual user service plus proxy-specific rewriting | BUserManager and fork-specific proxy state | Typed aggregate per package + virtual user + immutable revision, owned by Package Service |
| UserManager | `VUserManagerService` and `UserManagerStub` | `BUserManagerService` and `IUserManagerProxy` | Identity, serial/name/flags, running/unlocked/quiet state and restrictions |
| LauncherApps | Identity rewriting and package query mediation | `ILauncherAppsProxy` with broader modern methods | Package visibility, launcher queries, callback quota and start policy |
| Shortcut | `ShortcutServiceStub`, shortcut forwarding/rewriting | `IShortcutManagerProxy` and application shortcut utilities | Durable virtual shortcuts, quotas, enable/disable, usage and pin policy |
| AppWidget | `AppWidgetManagerStub` with Host service mediation | `IAppWidgetManagerProxy` | Host-owned virtual widget IDs, bind/update/query policy; no rendering claim |
| UsageStats | `UsageStatsManagerStub` identity mediation | `IUsageStatsManager` mirror/proxy coverage varies | Durable bounded events, package summaries and standby-bucket projection |
| Content/Settings | `VContentService`, `ContentServiceStub`, `SettingsProviderHook` | `ContentServiceStub`, `ISettingsProviderProxy`, system/OEM settings proxies | Observer ownership plus isolated Secure/System/Global virtual namespaces |
| State cleanup | Mature virtual-process/package lifecycle | Fork-specific Binder/service cleanup | Explicit package/user scope deletion, Runtime observer refresh and bounded ownership |
| Failure behavior | Compatibility often favors passthrough | Fork behavior varies | Explicit `BLOCKED`/`STATIC`/`HOST`; missing required proxy blocks Guest launch |
| Persistence safety | Legacy/project-specific | Fork-specific | Bounded atomic state, CRC, quarantine, optimistic versioning and revision authorization |
| Android/OEM evidence | Strong historical use but old-version constraints | Broader recent proxy set; fork quality varies | Device evidence remains 0 |

## Current comparative judgment

- M5-T11 closes the repository-owned source-architecture gap for common User, Launcher, Shortcut, Widget, Usage and
  Settings/Content policy and data paths.
- Controlled Sandbox is stronger in typed aggregate policy, per-virtual-user isolation, revision-authorized access,
  bounded persistence, explicit failure modes and source/device evidence separation.
- VA/NBB remain stronger in accumulated Android-version/OEM Binder signatures, concrete framework wrapper layouts,
  launcher/widget callbacks and real application evidence.
- Source wiring does not establish Android compatibility. Device evidence remains 0 until the locked build and
  Emulator/physical-device gates run.
