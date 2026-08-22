# C2-T01 System-Service Method Catalog

> Scope: one catch-up task only — `C2-T01`.
>
> This catalog is a planning and evidence artifact. It does not turn source presence into
> compatibility proof, and it does not claim VA PRO equivalence. `RD_API32_L3` is a target
> evidence level for later C2 tasks unless the row explicitly cites an existing scoped receipt.

## 1. Baseline and decision

| Field | Value |
|---|---|
| Task | `C2-T01` — establish the SX/XH system-service method backlog |
| Phase | C2 — high-frequency SystemService and F2-F5 depth |
| Source baseline | `d0da3743197b609756d0bd5ed309ad2bfa71de2f` |
| Service Hook scope | 59 `*Hook.java` files under `sandbox-framework/.../framework/service` |
| Boundary Hook scope | 10 non-service Hook owners tracked separately below |
| Method backlog | 40 logical method families: 20 P0, 15 P1, 5 P2 |
| Decision | All 59 service Hook files are classified; only the P0/P1 method families enter the immediate C2 execution queue |
| Evidence boundary | Existing C1/RD evidence is referenced only at its recorded scope; no XH business-page or VA PRO claim is inferred |

The first dependency-satisfied PENDING task was `C2-T01` after `C1-GATE` completed. The progress
ledger was moved to `IN_PROGRESS` before this artifact was built. No C2-T02 through C2-T07 work is
included in this change.

## 2. Discovery inputs and classification rule

The inventory combines these sources:

1. The 59 service Hook files and `FrameworkHooks.java` installation graph.
2. Static dispatch owners: `DeviceServiceInvocationInterceptor`, `VirtualSystemServiceInterceptor`,
   `ApplicationEnvironmentInvocationInterceptor`, the PackageManager/permission owners, and the
   runtime Broker coordinators.
3. The XH/SX business inventory and reverse-engineering reports:
   `docs/dingtalk-xh/XH_CAPABILITY_INVENTORY.md`, `XH_SANDBOX_CAPABILITY_MATRIX.md`, and the
   F2-F5 implementation reports.
4. The controlled C1 receipt at `verification/catch-up/C1-GATE/c1-gate-receipt.json`.
   It is a scoped RD baseline, not a C2 method-completeness proof.
5. `docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml` as a compatibility hypothesis/corpus index.
   Corpus entries are not CAS evidence until a method-specific CAS trace exists.
6. The system-service and Binder matrices:
   `docs/system/T57_R03_SYSTEM_SERVICE_STATE_MATRIX.yaml` and
   `docs/system/T57_R03_BINDER_INTERCEPTION_MATRIX.yaml`.

The classification is deliberately two-dimensional:

- `F1` through `F5` identify the product-relevant call surface.
- `C2-P1-SUPPORTING` identifies a service that supports a likely P1 execution lane but has no
  direct SX/XH business proof in this task.
- `C2-P2-UNUSED-TAIL` identifies a present Hook with no observed F1-F5 call evidence in the
  current corpus/trace. It remains visible and owned, but is outside the immediate product queue.

`SOURCE_AND_STATIC`, `RD_BASELINE_SCOPED`, `KNOWN_LIMITATION`, `EXPLICIT_UNSUPPORTED`, and
`UNVERIFIED` describe current evidence depth. They are not implementation status labels.

## 3. Complete 59-file service Hook classification

Every service Hook file is listed exactly once. Multi-domain files use a slash in `Primary scope`;
the row still has one backlog classification and one next task.

| # | Hook file | Primary scope | Classification | Priority | Current owner / evidence anchor | Next task |
|---:|---|---|---|---|---|---|
| 1 | `AccessibilityManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `AccessibilityManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 2 | `AccountManagerHook` | F1 platform | `F1-PRODUCT-SURFACE` | P1 | `VirtualSystemServiceInterceptor`; account state matrix | C2-T05 |
| 3 | `ActivityClientHook` | F1 launch | `F1-PRODUCT-SURFACE` | P0 | `ActivityClientHook`; Activity/C1 receipt | C2-T05 |
| 4 | `AlarmManagerHook` | F1 scheduling | `F1-PRODUCT-SURFACE` | P1 | `VirtualSystemServiceInterceptor`; virtual alarm state | C2-T05 |
| 5 | `AppWidgetManagerServiceHook` | F1 environment | `F1-PRODUCT-SURFACE` | P1 | `ApplicationEnvironmentInvocationInterceptor` | C2-T07 |
| 6 | `AudioCaptureServiceHook` | F3 media support | `C2-P1-SUPPORTING` | P1 | `AudioCaptureServiceHook`; no direct XH business trace | C2-T06 |
| 7 | `AutofillManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `AutofillManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 8 | `BackupManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `BackupManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 9 | `BatteryServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `BatteryServiceHook`; no F1-F5 trace row | C2-T07 |
| 10 | `BiometricServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `BiometricServiceHook`; no F1-F5 trace row | C2-T07 |
| 11 | `BluetoothServiceHook` | F5 network/device | `F5-PRODUCT-SURFACE` | P1 | `DeviceServiceInvocationInterceptor.bluetooth` | C2-T06 |
| 12 | `CameraServiceHook` | F3 camera | `F3-PRODUCT-SURFACE` | P0 | `CameraServiceHook`; Camera1/Camera2 reports | C2-T04 |
| 13 | `CaptioningManagerHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `CaptioningManagerHook`; no F1-F5 trace row | C2-T07 |
| 14 | `ClipboardManagerHook` | F1 platform | `F1-PRODUCT-SURFACE` | P1 | `VirtualSystemServiceInterceptor`; clipboard state | C2-T05 |
| 15 | `CompanionDeviceManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `CompanionDeviceManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 16 | `ConnectivityServiceHook` | F5 network | `F5-PRODUCT-SURFACE` | P1 | `DeviceServiceInvocationInterceptor` / Connectivity owner | C2-T06 |
| 17 | `ContentServiceHook` | F1 settings/content | `F1-PRODUCT-SURFACE` | P1 | `ApplicationEnvironmentInvocationInterceptor.content` | C2-T07 |
| 18 | `ContextHubServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `ContextHubServiceHook`; no F1-F5 trace row | C2-T07 |
| 19 | `DeviceIdentifiersServiceHook` | F4 device identity | `F4-PRODUCT-SURFACE` | P0 | `CompatibilityInvocationInterceptor` / device profile | C2-T06 |
| 20 | `DevicePolicyManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `DevicePolicyManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 21 | `DisplayManagerHook` | F1 display | `F1-PRODUCT-SURFACE` | P0 | `DisplayManagerHook`; display context owner | C2-T05 |
| 22 | `DnsResolverServiceHook` | F5 network | `F5-PRODUCT-SURFACE` | P1 | `DnsResolverServiceHook` / network owner | C2-T06 |
| 23 | `DropBoxManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `DropBoxManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 24 | `GoogleServiceBrokerHook` | compatibility support | `C2-P1-SUPPORTING` | P1 | `CompatibilityInvocationInterceptor.google`; GMS corpus | C2-T06 |
| 25 | `GraphicsStatsServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `GraphicsStatsServiceHook`; no F1-F5 trace row | C2-T07 |
| 26 | `InputManagerServiceHook` | F1 input | `F1-PRODUCT-SURFACE` | P0 | `InputManagerServiceHook`; input routing owner | C2-T05 |
| 27 | `InputMethodManagerHook` | F1 IME | `F1-PRODUCT-SURFACE` | P0 | `InputMethodManagerHook`; IME owner | C2-T05 |
| 28 | `JobSchedulerHook` | F1 scheduling | `F1-PRODUCT-SURFACE` | P1 | `VirtualSystemServiceInterceptor`; job state | C2-T05 |
| 29 | `LauncherAppsServiceHook` | F1 environment | `F1-PRODUCT-SURFACE` | P1 | `ApplicationEnvironmentInvocationInterceptor.launcher` | C2-T07 |
| 30 | `LocationServiceHook` | F2 location | `F2-PRODUCT-SURFACE` | P0 | `DeviceServiceInvocationInterceptor.location`; location reports | C2-T03 |
| 31 | `MediaProjectionManagerServiceHook` | F3 media support | `C2-P1-SUPPORTING` | P1 | `MediaProjectionManagerServiceHook`; no direct XH trace | C2-T06 |
| 32 | `MediaRouterServiceHook` | F3 media support | `C2-P1-SUPPORTING` | P1 | `MediaRouterServiceHook`; no direct XH trace | C2-T06 |
| 33 | `MediaSessionManagerServiceHook` | F3 media support | `C2-P1-SUPPORTING` | P1 | `MediaSessionManagerServiceHook`; no direct XH trace | C2-T06 |
| 34 | `NfcServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `NfcServiceHook`; no F1-F5 trace row | C2-T07 |
| 35 | `NotificationManagerHook` | F1 scheduling | `F1-PRODUCT-SURFACE` | P1 | `VirtualSystemServiceInterceptor`; notification state | C2-T05 |
| 36 | `OemIdentifierServiceHook` | compatibility tail | `C2-P2-UNUSED-TAIL` | P2 | `OemIdentifierServiceHook`; no product trace | C2-T07 |
| 37 | `OemSystemServicesHook` | compatibility tail | `C2-P2-UNUSED-TAIL` | P2 | `OemSystemServicesHook`; no product trace | C2-T07 |
| 38 | `PersistentDataBlockServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `PersistentDataBlockServiceHook`; no F1-F5 trace row | C2-T07 |
| 39 | `PowerManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `PowerManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 40 | `PrintManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `PrintManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 41 | `RestrictionsManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `RestrictionsManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 42 | `SearchManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `SearchManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 43 | `SensorPrivacyServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `SensorPrivacyServiceHook`; no F1-F5 trace row | C2-T07 |
| 44 | `SensorServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `SensorServiceHook`; no F1-F5 trace row | C2-T07 |
| 45 | `ShortcutManagerServiceHook` | F1 environment | `F1-PRODUCT-SURFACE` | P0 | `ApplicationEnvironmentInvocationInterceptor.shortcut` | C2-T07 |
| 46 | `SmsServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `SmsServiceHook`; no F1-F5 trace row | C2-T07 |
| 47 | `StorageManagerHook` | F1 storage | `F1-PRODUCT-SURFACE` | P0 | `StorageManagerHook`; storage/runtime owner | C2-T07 |
| 48 | `StorageStatsManagerServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `StorageStatsManagerServiceHook`; no F1-F5 trace row | C2-T07 |
| 49 | `SystemUpdateServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `SystemUpdateServiceHook`; no F1-F5 trace row | C2-T07 |
| 50 | `TelecomServiceHook` | communication support | `C2-P1-SUPPORTING` | P1 | `TelecomServiceHook`; no direct XH trace | C2-T06 |
| 51 | `TelephonyServiceHook` | F4/F5 device/network | `F4-F5-PRODUCT-SURFACE` | P0 | `DeviceServiceInvocationInterceptor.telephony`; device reports | C2-T06 |
| 52 | `UsageStatsManagerServiceHook` | F1 environment | `F1-PRODUCT-SURFACE` | P1 | `ApplicationEnvironmentInvocationInterceptor.usageStats` | C2-T07 |
| 53 | `UsbServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `UsbServiceHook`; no F1-F5 trace row | C2-T07 |
| 54 | `UserManagerServiceHook` | F1 environment | `F1-PRODUCT-SURFACE` | P0 | `ApplicationEnvironmentInvocationInterceptor.user` | C2-T07 |
| 55 | `VibratorServiceHook` | P2 tail | `C2-P2-UNUSED-TAIL` | P2 | `VibratorServiceHook`; no F1-F5 trace row | C2-T07 |
| 56 | `VpnManagerServiceHook` | F5 network | `F5-PRODUCT-SURFACE` | P1 | `VpnManagerServiceHook` / network owner | C2-T06 |
| 57 | `WebViewUpdateServiceHook` | compatibility support | `C2-P1-SUPPORTING` | P1 | `CompatibilityInvocationInterceptor.webviewupdate` | C2-T06 |
| 58 | `WifiServiceHook` | F5 network | `F5-PRODUCT-SURFACE` | P0 | `DeviceServiceInvocationInterceptor.wifi`; network reports | C2-T06 |
| 59 | `WindowManagerHook` | F1 window | `F1-PRODUCT-SURFACE` | P0 | `WindowManagerHook`; window/input owner | C2-T05 |

### Completeness result

| Bucket | Hook files | Meaning |
|---|---:|---|
| F1 product surface | 17 | Product/container, launch, platform-state and window/input dependencies |
| F2 product surface | 1 | Location service |
| F3 product surface | 1 | Camera service |
| F4/F5 product surface | 2 | Device identifiers plus Telephony's device/network overlap |
| F5 product surface | 5 | Wi-Fi, Bluetooth, Connectivity, DNS and VPN |
| C2 P1 supporting | 7 | Audio, media, communication, WebView and GMS compatibility support |
| C2 P2 unused tail | 26 | Present Hook files with no observed F1-F5 business call row |
| **Total classified** | **59** | **59 / 59; no service Hook file is unclassified** |

The 10 boundary owners excluded from the 59-file service count are:

`ActivityManagerHook`, `ActivityTaskManagerHook`, `BuildIdentityHook`, `ReflectiveServiceHook`,
`SensorCatalogHook`, `ServiceManagerBinderHook`, `SettingsProviderIdentityHook`,
`PackageManagerHook`, `AppOpsManagerHook`, and `PermissionManagerHook`.

## 4. P0/P1/P2 method backlog

The rows below use logical Android API families because hidden Binder signatures vary by API/OEM.
The exact API32 overload and parcel layout is a required input to the mapped C2 task; a class or
Hook row alone is not completion.

| ID | Domain / logical method family | Req/identity transform | Return/callback and lifecycle | Current owner | Current evidence | Priority | Target task / target evidence |
|---|---|---|---|---|---|---|---|
| C2-T01-F1-01 | PMS query/resolve: `queryIntentActivities`, `resolveActivity`, `queryIntentServices`, `queryBroadcastReceivers`, package/application info | Guest package + virtual user + revision; filter hidden Host packages | Typed `ResolveInfo`/`PackageInfo`; no stale result after clear/delete/revision | `PackageManagerInvocationHandler`; `PackageManagerHook` | `SOURCE_AND_STATIC`; package-query checker PASS | P0 | C2-T02 / `RD_API32_L3` query/resolve matrix |
| C2-T01-F1-02 | Permission/AppOps/Attribution: `checkPermission`, `checkUidPermission`, `noteProxyOp`, `startOp`, `finishOp`, attribution chain | Rewrite uid/package/AttributionSource to Guest identity; deny Host leakage | Permission/AppOps result plus finish/death cleanup | `PermissionManagerHook`, `AppOpsManagerHook`, package permission owners | `SOURCE_AND_STATIC`; matrix and static gates | P0 | C2-T02 / `RD_API32_L3` identity and negative trace |
| C2-T01-F1-03 | Package lifecycle: import/clone/install, revision, clear, delete, reinstall | Package + virtual user + revision are the durable key; invalidate old callbacks | Install result, package visibility, state deletion and restart convergence | `PackageManagementService`, package authority, lifecycle coordinators | `RD_BASELINE_SCOPED`; C1 lifecycle receipt | P0 | C2-T02 / `RD_API32_L3` lifecycle/revision evidence |
| C2-T01-F1-04 | Activity/task launch: `startActivity`, `startActivities`, result, task/launch-mode projection | Caller Guest identity; resolve target in virtual package universe; preserve task owner | Launch result, activity token/result callback, death/recovery cleanup | `ActivityManagerHook`, `ActivityTaskManagerHook`, `RuntimeActivityLaunchCoordinator` | `RD_BASELINE_SCOPED`; C1 Activity 700/700 | P0 | C2-T05 / `RD_API32_L3` task and callback trace |
| C2-T01-F1-05 | Service/FGS: `startService`, `startForegroundService`, `bindService`, `stopService`, `setServiceForeground` | Caller/target package, process and virtual user; enforce declared component and FGS policy | `ComponentName`/binding callback; unbind, death and stop converge | `RuntimeServiceCoordinator`, `ServiceManager` service hooks | `SOURCE_AND_STATIC`; C1 service evidence | P0 | C2-T05 / `RD_API32_L3` FGS/bind/death trace |
| C2-T01-F1-06 | Broadcast: explicit/implicit/ordered `sendBroadcast`, register/unregister receiver, finish | Caller identity and virtual package universe; exported/permission checks | Receiver callback/order/result; unregister, ordered finish, session death cleanup | `RuntimeComponentOperationCoordinator`, `RuntimeReceiverCoordinator` | `SOURCE_AND_STATIC`; broker/receiver split checks PASS | P0 | C2-T05 / `RD_API32_L3` ordered/dynamic/death trace |
| C2-T01-F1-07 | Provider: acquire stable/unstable, `query`, `insert`, `update`, `delete`, `openFile`, release | Provider authority and caller/target instance identity; URI grant boundary | Cursor/file return; stable/unstable release, cancellation and provider death | `RuntimeProviderResourceCoordinator`, provider runtime | `RD_BASELINE_SCOPED`; C1 provider baseline | P0 | C2-T02 / `RD_API32_L3` provider lifecycle trace |
| C2-T01-F1-08 | PendingIntent: create activity/broadcast/service sender, send, cancel, creator package/uid | Creator package + virtual user + generation; resolve target in Guest universe | Sender result/callback; one-shot/cancel/death and durable cleanup | `VirtualPendingIntentRegistry`, `PendingIntentFrameworkInterceptor`, `GuestPendingIntentDispatcher` | `SOURCE_AND_STATIC`; typed registry self-tests | P0 | C2-T05 / `RD_API32_L3` sender identity and cross-package trace |
| C2-T01-F1-09 | Alarm: set/setExact/setRepeating/setAlarmClock, cancel, next alarm | Namespace by package/user/generation; callback only to owner process | Alarm delivery and `AlarmClockInfo`; cancel/clear/restart/death cleanup | `VirtualSystemServiceInterceptor`, `VirtualSystemServiceStore` | `SOURCE_AND_STATIC`; C1 alarm evidence | P1 | C2-T05 / `RD_API32_L3` exact-alarm and cleanup trace |
| C2-T01-F1-10 | Notification: enqueue/notify, cancel/cancelAll, channels/groups | Namespace package/user plus host id mapping; rewrite creator and PendingIntent | Active notification/channel/group return; cancel/delete/clear cleanup | `VirtualSystemServiceInterceptor`, system-service authority | `SOURCE_AND_STATIC`; notification static gate | P1 | C2-T05 / `RD_API32_L3` click/channel/cancel trace |
| C2-T01-F1-11 | JobScheduler: schedule/enqueue, cancel/cancelAll, pending jobs, start/stop callback | Persist package/user/generation owner; rewrite service and job namespace | Job result and `onStartJob`/`onStopJob`; restart/death cleanup | `VirtualSystemServiceInterceptor`, `VirtualJobService`, store | `SOURCE_AND_STATIC`; job static gate | P1 | C2-T05 / `RD_API32_L3` constraints/callback trace |
| C2-T01-F1-12 | Shortcut/AppWidget/UsageStats: shortcut CRUD/pin, widget ids, usage events | Package/user namespace; only Guest-owned IDs and profiles | Typed lists/events; remove/delete/clear and restart convergence | `ApplicationEnvironmentInvocationInterceptor` | `SOURCE_AND_STATIC`; environment static gate | P1 | C2-T07 / `RD_API32_L3` environment-state trace |
| C2-T01-F1-13 | Window/Input/IME/Display: window token, input dispatch, IME state, display context | Window/display/input tokens remain Guest-owned and session-bound | Input/IME callbacks and display metrics; remove/death/restart cleanup | `WindowManagerHook`, `InputManagerServiceHook`, `InputMethodManagerHook`, `DisplayManagerHook` | `SOURCE_AND_STATIC`; C1 Activity baseline | P1 | C2-T05 / `RD_API32_L3` token and callback trace |
| C2-T01-F1-14 | Settings/User/Launcher/Content: secure settings, user/profile, launcher visibility, observer | Package/user identity and observer registration transformed; no Host settings read | Typed values/lists and observer callback; unregister/clear/death cleanup | `ApplicationEnvironmentInvocationInterceptor`, `SettingsProviderIdentityHook` | `SOURCE_AND_STATIC`; environment and identity gates | P1 | C2-T07 / `RD_API32_L3` settings/user/observer trace |
| C2-T01-F2-01 | Location provider state: `isProviderEnabled`, `getProviders`, `getBestProvider` | Guest location profile and permission; no Host provider truth | Provider list/state; profile update and clear convergence | `DeviceServiceInvocationInterceptor.location` | `RD_BASELINE_SCOPED`; location report and C1 trace | P0 | C2-T03 / `RD_API32_L3` provider-state evidence |
| C2-T01-F2-02 | Location getters: `getLastLocation`, `getLastKnownLocation`, `getCurrentLocation` | Guest profile, virtual user and time policy | `Location` source/lat/long/alt/accuracy/time/elapsed; stale profile invalidation | `DeviceServiceInvocationInterceptor.location`, location profile factory | `RD_BASELINE_SCOPED`; controlled location evidence | P0 | C2-T03 / `RD_API32_L3` source/time/value trace |
| C2-T01-F2-03 | Listener callbacks: `requestLocationUpdates`, `registerLocationListener`, unregister | Listener token and callback Binder bound to package/user/generation | Ordered periodic callback; unregister, clear, death and background transition stop delivery | `DeviceServiceInvocationInterceptor.location`, callback lease registry | `RD_BASELINE_SCOPED`; C1 callback evidence | P0 | C2-T03 / `RD_API32_L3` callback/death/30-minute trace |
| C2-T01-F2-04 | GNSS/NMEA: `registerGnssStatusCallback`, `addNmeaListener`, time/status | Guest callback identity and virtual clock policy | GNSS/NMEA callback; unregister/death and provider transition cleanup | `DeviceServiceInvocationInterceptor.location` | `SOURCE_AND_STATIC`; gap called out in XH reports | P1 | C2-T03 / `RD_API32_L3` GNSS/time callback trace |
| C2-T01-F2-05 | Location PendingIntent/geofence/test provider/inject: `requestLocationUpdates(PendingIntent)`, geofence, test provider | Fail closed until target contract exists; never write Host provider state | Explicit unsupported/denied result; no residual callback or provider state | `DeviceServiceInvocationInterceptor.location` | `EXPLICIT_UNSUPPORTED`; negative branch is source evidence only | P1 | C2-T03 / `RD_API32_L3_NEGATIVE` denial and cleanup evidence |
| C2-T01-F3-01 | Camera1 preview: `setPreviewCallback`, one-shot, with-buffer | Guest camera profile, callback Binder and surface/session identity | NV21/source frame callback; unregister/release/death cleanup | `CameraServiceHook`, camera native boundary | `KNOWN_LIMITATION`; XH/SX report + scoped MuMu native trace | P1 | C2-T04 / `RD_API32_L3` source-frame callback evidence |
| C2-T01-F3-02 | Camera1 capture/lifecycle: `startPreview`, `takePicture`, `stopPreview`, `release`, reopen | Camera/session owner and permission transformed at native/framework boundary | JPEG callback/result, dimensions/format; release/reopen and death convergence | `CameraServiceHook`, native camera path | `RD_BASELINE_SCOPED`; Camera1 MuMu receipt | P0 | C2-T04 / `RD_API32_L3` 100x reopen/release evidence |
| C2-T01-F3-03 | Camera2 device/session: `openCamera`, `createCaptureSession`, repeating/capture, close | Camera device/session/surface tokens bound to Guest process | Capture result/frame callback; abort/close/death cleanup | `CameraServiceHook`, camera runtime/native boundary | `RD_BASELINE_SCOPED`; Camera2 evidence | P0 | C2-T04 / `RD_API32_L3` session/result/recovery trace |
| C2-T01-F3-04 | ImageReader/Plane/SurfaceTexture/formats: acquire image, plane buffer, Surface target | Surface/ImageReader ownership and format contract preserved | JPEG/YUV/NV21 buffer, size/orientation/timestamp; close/release cleanup | `CameraServiceHook`, camera media/native path | `RD_BASELINE_SCOPED`; image result/format evidence | P0 | C2-T04 / `RD_API32_L3` source hash/format/result evidence |
| C2-T01-F3-05 | Media projection/audio/route/session support | Guest permission and media token; no Host audio/route leakage | Capture/route/focus callbacks; stop/release/death cleanup | `MediaProjectionManagerServiceHook`, `AudioCaptureServiceHook`, media hooks | `SOURCE_AND_STATIC`; no direct XH business trace | P1 | C2-T06 / `RD_API32_L3` media callback/resource evidence |
| C2-T01-F4-01 | Android ID/Build/serial: `Settings.Secure.ANDROID_ID`, `Build` fields, `getSerial` | Stable package/user device profile; sanitize Host identity | Stable typed/string values; profile update and process restart consistency | `BuildIdentityHook`, `SettingsProviderIdentityHook`, `DeviceIdentifiersServiceHook` | `RD_BASELINE_SCOPED`; device isolation receipt | P0 | C2-T06 / `RD_API32_L3` two-user/restart evidence |
| C2-T01-F4-02 | Telephony identifiers: device ID/IMEI/MEID/IMSI/ICCID/line | Slot + package/user identity; deny physical Host values | Typed identifier/string/null policy; clear/delete and permission cleanup | `DeviceServiceInvocationInterceptor.telephony`, `TelephonyServiceHook` | `RD_BASELINE_SCOPED`; device report | P0 | C2-T06 / `RD_API32_L3` identifier/negative trace |
| C2-T01-F4-03 | Subscription/operator: active list, slot/sub ID, carrier/country/network type | Virtual subscription slot and profile; callback identity | `SubscriptionInfo`/network values; profile update, unregister/death cleanup | `DeviceServiceInvocationInterceptor.telephony` | `RD_BASELINE_SCOPED`; device report, API gaps noted | P0 | C2-T06 / `RD_API32_L3` subscription/registry trace |
| C2-T01-F4-04 | WebView/GMS/OEM compatibility: provider, availability, IDs, policy queries | Compatibility profile + Guest identity; explicit BLOCKED/STATIC/HOST mode | Typed provider/profile values or fail-closed error; profile observer/restart cleanup | `CompatibilityInvocationInterceptor`, compatibility service Hooks | `SOURCE_AND_STATIC`; M5-T12 gate PASS, device 0 | P1 | C2-T06 / `RD_API32_L3` mode/identity evidence |
| C2-T01-F5-01 | Wi-Fi current: enabled/state, `getConnectionInfo`, DHCP/MAC | Per-user `VirtualWifiProfile`; do not expose Host HAL truth | Typed `WifiInfo`/DHCP/MAC; profile reset and process restart consistency | `DeviceServiceInvocationInterceptor.wifi`, `WifiServiceHook` | `RD_BASELINE_SCOPED`; network report | P0 | C2-T06 / `RD_API32_L3` current-value/two-user trace |
| C2-T01-F5-02 | Wi-Fi scan: `getScanResults`, `startScan` | Guest profile and scan namespace; scan callback/permission identity | Typed scan list/result; cancellation, stale scan and death cleanup | `DeviceServiceInvocationInterceptor.wifi`, `WifiServiceHook` | `RD_BASELINE_SCOPED`; network report | P0 | C2-T06 / `RD_API32_L3` scan and cleanup trace |
| C2-T01-F5-03 | Cell: `getAllCellInfo`, neighboring cells, `getCellLocation` | Virtual slot/profile and permission; no physical RIL leakage | Typed LTE/cell snapshots; profile update and process restart convergence | `DeviceServiceInvocationInterceptor.telephony` | `RD_BASELINE_SCOPED`; cell report with API gap | P0 | C2-T06 / `RD_API32_L3` cell/two-user evidence |
| C2-T01-F5-04 | Connectivity callbacks: active network, request/register/unregister callback | Guest network request and callback Binder identity | Network state/callback sequence; unregister/death and profile reset cleanup | Connectivity/network owner and `ConnectivityServiceHook` | `SOURCE_AND_STATIC`; no full L3 method trace | P1 | C2-T06 / `RD_API32_L3` callback/resource evidence |
| C2-T01-F5-05 | DNS/VPN: resolver queries, VPN state/service boundary | Guest network/profile identity; explicit host fallback policy | Query result/VPN state; cancellation, socket/resource and death cleanup | `DnsResolverServiceHook`, `VpnManagerServiceHook` | `SOURCE_AND_STATIC`; no full L3 method trace | P1 | C2-T06 / `RD_API32_L3` DNS/VPN negative/resource evidence |
| C2-T01-F5-06 | Bluetooth: adapter state, address/name, bonded devices, discovery callbacks | Guest device profile and permission; no Host bonded-device leakage | Typed adapter/device/callback values; discovery cancel/unregister/death cleanup | `DeviceServiceInvocationInterceptor.bluetooth` | `SOURCE_AND_STATIC`; static owner only | P1 | C2-T06 / `RD_API32_L3` state/device/callback evidence |
| C2-T01-F5-07 | Sensor: register/unregister listener, flush, trigger and events | Guest sensor profile, listener Binder and permission identity | Sensor event sequence; unregister/flush/death cleanup | `DeviceServiceInvocationInterceptor.sensors`, `SensorServiceHook` | `SOURCE_AND_STATIC`; no F1-F5 business trace | P1 | C2-T06 / `RD_API32_L3` event/cleanup evidence |
| C2-T01-P2-01 | Biometric/fingerprint: authenticate, cancel, enrollment/status | Guest permission and session token; TEE/Keyguard boundary explicit | Result/callback or fail-closed unsupported; cancel/death cleanup | `BiometricServiceHook` | `UNVERIFIED`; no F1-F5 trace row | P2 | C2-T07 / `RD_API32_L3` or `NOT_APPLICABLE` evidence |
| C2-T01-P2-02 | DevicePolicy/Autofill: policy queries, save/fill, session lifecycle | Guest package/user and service session identity | Typed result/callback; cancel/clear/death cleanup | `DevicePolicyManagerServiceHook`, `AutofillManagerServiceHook` | `UNVERIFIED`; no F1-F5 trace row | P2 | C2-T07 / `RD_API32_L3` or explicit negative |
| C2-T01-P2-03 | NFC/USB/Print/Companion: device/session discovery and transfer methods | Guest device permission and session identity | Typed device/callback result; close/unregister/death cleanup | Corresponding service Hooks | `UNVERIFIED`; no F1-F5 trace row | P2 | C2-T07 / `RD_API32_L3` or explicit negative |
| C2-T01-P2-04 | Sensor privacy/Power/Battery/Vibrator: state, listener, wake/vibrate lifecycle | Guest permission and resource owner; no Host state leakage | State/callback; cancel/release/death cleanup | Corresponding service Hooks | `UNVERIFIED`; no F1-F5 trace row | P2 | C2-T07 / `RD_API32_L3` or explicit negative |
| C2-T01-P2-05 | Accessibility/Captioning/Backup/DropBox/Search/StorageStats/SystemUpdate/ContextHub/GraphicsStats/PersistentDataBlock/SMS | Guest package/user and privileged-service boundary; fail closed where host-only | Typed result/callback or explicit unsupported; clear/delete/restart/death cleanup | Corresponding service Hooks | `UNVERIFIED`; unused-tail classification | P2 | C2-T07 / `RD_API32_L3` or `NOT_APPLICABLE` evidence |

## 5. C2-T01 acceptance statement

- **Classification:** 59/59 service Hook files are listed and assigned to F1-F5, C2 P1 support, or
  the explicitly separated C2 P2 tail.
- **Method backlog:** 40 logical method families are recorded: 20 P0, 15 P1, and 5 P2. Every
  P0/P1 row has a current owner, identity/request treatment, return/callback and cleanup/death
  expectation, a mapped test plan, and a target evidence level.
- **Unknowns:** hidden API32 overloads, OEM Binder parcel differences, and unobserved P2 tails
  remain explicit `UNVERIFIED` items; they are not silently treated as covered.
- **Existing evidence:** C1/RD receipts are cited only as scoped baseline evidence. The catalog
  does not add a new device claim and does not relabel any `NOT_PROVEN` VA PRO comparison as PASS.
- **Next dependency:** C2-T02 is the next implementation task after this receipt; C2-T03 through
  C2-T07 consume the corresponding rows and target evidence paths.

The machine-readable form of this catalog is
`verification/catch-up/C2-T01/c2-t01-method-inventory.json`.
