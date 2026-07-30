# Engineering roadmap

Status legend: `DONE` means locally executable source implementation/tests exist; `PARTIAL` means the source path exists but does not yet cover the complete Android framework behavior; `DEVICE` requires real Android evidence; `OPEN` is not implemented.

## Foundation and import

- DONE — clean-room repository and license policy.
- DONE — bounded binary Android manifest parser.
- DONE — private transactional APK import, digest and immutable SHA-256 revision publication.
- DONE — signer continuity and downgrade rejection.
- DONE — ABI-native extraction protections.
- DONE — one validated atomic package/virtual-instance catalog with legacy migration and post-commit orphan cleanup.
- DONE — package lifecycle mutations are serialized by a dedicated Binder-owned package service with a PID/UID-bound management capability.
- DONE — staged Binder-owned install sessions, Base/Feature/Configuration Split validation, immutable multi-artifact revisions and dependency-ordered product import/runtime paths.
- PARTIAL — shared-library resolution, dex/oat cache ownership, install rollback history and PackageInstaller parity remain open.

## Runtime control plane

- DONE — versioned Binder protocol.
- DONE — explicit session lifecycle, generation checks and immutable APK-revision binding with stale-Session replacement.
- DONE — eight retained Guest process bindings and Binder-death handling.
- DONE — one-time expiring Activity route authority.
- DONE — separate virtual-user roots and virtual UIDs.
- DONE — per-declared-process session keys and component process propagation.
- DEVICE — process lifetime/recovery behavior on Android.

## Guest application and Activity

- DONE — child-first Guest class loader with parent-first platform/contract namespaces and explicit denial of host implementation classes.
- PARTIAL — Guest resources, Application bootstrap and principal Context storage redirection; device-protected storage and broader system-service identity remain open.
- DONE — Stub Activity route consumption and lifecycle forwarding.
- DONE — explicit Activity task/token/state model used by the runtime controller.
- PARTIAL — AMS/ATMS mediation currently performs reversible identity proxying but does not yet virtualize every start/result/task callback signature.
- DEVICE — reflected Activity field, Window, task/back-stack and launch-mode compatibility per API level.

## Other components

- DONE — started and bound Service lifecycle, Binder token, connection ownership, unbind/rebind and sticky recovery source paths.
- DONE — dynamic Receiver ownership plus explicit/implicit manifest Receiver indexing, action/category/data/MIME matching, exported/permission checks, deterministic on-demand process activation, ordered result source policy and generation cleanup paths.
- OPEN — implicit/ordered broadcasts, protected system broadcasts and platform background-delivery behavior.
- DONE — Provider attach/create and CRUD transport.
- DONE — Broker-issued Cursor leases, sequential anti-replay paging, cancellation, expiry, Session cleanup and bounded large-result transport.
- DONE — Provider authority namespace is Broker-owned, transactionally reserved and bound to Session/generation; URI read/write grant lifecycle is wired.
- DONE — Provider Authority, Observer, URI Grant, Cursor and File resources share unified disconnect/recovery/stop/expiry cleanup with cross-resource leak tests.
- DONE — declared remote-process and isolated-process planning/source routing.
- DEVICE — real Binder callback, Cursor, URI grant and process behavior.

## Framework policy

- DONE — Binder-issued, revision-bound virtual PackageManager application/package/component/query snapshot.
- DONE — typed Intent-filter snapshots and bounded Guest-local Activity/Service/Receiver/Provider query and resolve semantics with per-user package/component enabled overrides, install timestamps and installer metadata.
- PARTIAL — exact Android IntentFilter scoring, visibility rules, system/virtual cross-package list merging, SharedLibraryInfo resolution and all PackageManager Binder signature variants remain open.
- DONE — per-package/per-virtual-user permission and bounded AppOps decisions persist atomically and are consumed by PackageManager/PermissionManager/AppOps proxies.
- PARTIAL — bounded host-capability checks and runtime-consent state are wired; roles, special access, one-time grants, permission groups and signature/privileged handling remain open.
- PARTIAL — AppOps coverage is limited to bounded integer/boolean check/note/start-style calls.
- DONE — reversible notification, JobScheduler and storage identity proxies with per-service diagnostics.
- DONE — ActivityManager/ActivityTaskManager source mediation uses an atomic proxy pair, exact API-signature policies, callback/result task models and reversible rollback.
- DONE — Package-Service-owned typed Binder authority shares Clipboard, basic Account and persistent Notification/Job namespaces per package/virtual user; Alarm ownership is bound to virtual process and Runtime generation with bounded recovery.
- PARTIAL — AlarmManager power/reboot semantics, Account authenticators, Notification callbacks/channels, Job work-item APIs and Android-version Job constraints remain open.
- DEVICE — hidden API and Binder signature matrix on Android 12–16 and OEM variants.

## Native and WebView

- DONE — C++ path/network policy engine and JNI boundary.
- DONE — Guest-module-scoped ELF PLT/GOT rebinding for file, DNS, direct-IP and late `dlopen` refresh paths.
- DONE — async-signal-safe native crash evidence writer.
- DONE — per-user/package/process WebView profile suffix and directory isolation source path.
- DEVICE — linker/RELRO compatibility, WebView renderer/GPU/utility processes and real Guest native libraries.

## Diagnostics and fixtures

- DONE — Java crash, ANR watchdog, native crash and runtime-event evidence export with SHA-256 manifest.
- DONE — Fixture matrix for multi-Activity, started/bound/remote/isolated Service, manifest/dynamic Receiver, Provider CRUD, WebView and JNI.
- DEVICE — execute and validate the complete Fixture matrix on Emulator.

## Milestone 3 gates

- LOCAL — evidence matrix is generated from `verification/m3-source-capability-matrix.json`; source, production wiring and device evidence are reported independently.
- DONE — ActivityManager and ActivityTaskManager source and production wiring gate; Android Binder/device execution remains pending.
- DEVICE — real AGP/NDK build, two isolated virtual instances, full component evidence and 20-minute stability run.

## M4-T12 source baseline

Completed in source/host evidence: persistent owned Notification/Channel lifecycle, durable Job spec
state, safe scoped Notification/Job `cancelAll`, trusted host Job callback routing with explicit Guest
acknowledgement, and Provider resource cleanup extraction. Remaining priority: version-safe Guest
`JobParameters` execution, Notification/Job Android-version adapters and continued Broker
decomposition. Device validation remains intentionally deferred.

## M4-T13 source baseline

Completed in source/host evidence: typed JobParameters transport, trusted Host JobService dispatch, owning Guest JobService `onStartJob`/`onStopJob`, one-shot scoped `jobFinished`, timeout and Binder-death rescheduling, and stale-generation rejection. Remaining priority: Android-version validation, Job work-item APIs, Service coordinator extraction and complete Service lifecycle semantics. Device validation remains intentionally deferred.

## M4-T14 source baseline

Completed in source/host evidence: dedicated Service coordinator ownership, started/bound multi-client state, Binder-death cleanup, latest-start-ID stop semantics, foreground state, and sticky/redeliver Guest-generation recovery. Remaining priority: complete Android IServiceConnection/foreground enforcement, Broker-process persistence, Activity/Task launch modes and device validation.

## M4-T15 source baseline

- DONE — bounded Activity launch-flag policy covers forward-result, no-history, document and recent-task controls in the source task model.
- DONE — Runtime Broker owns package/user-isolated running/recent task query, move-to-front and remove-task operations.
- DONE — versioned, atomic and CRC-protected Activity/Task checkpoints restore bounded task state after Broker restart while dropping dead transient route/result authority.
- PARTIAL — Guest-facing `ActivityManager`/`ActivityTaskManager` task-object adapters, Window transitions, system Recents UI and complete compound flag parity remain open.
- DEVICE — Activity/task restoration and Recents behavior across Android API levels and OEM variants.

## M4-T16 source baseline

- DONE — durable, revision-bound PendingIntent identity and typed sender semantics.
- DONE — persistent Alarm, Notification and Job policy/recovery source paths.
- DEVICE — Android timing, quota, SystemUI and OEM behavior.

## M4-T17 source baseline

- DONE — modern filesystem/procfs/loader/network/audio Native policy and Host-native tests.
- DONE — explicit ABI metadata and 64-bit Host + 32-bit Companion architecture.
- PARTIAL — complete 32-bit Guest runtime and four-ABI Android packaging.
- DEVICE — Bionic/Linker/SELinux/OEM and real ABI execution.

## M4-T18 source baseline

- DONE — persistence/God-Class split, AIDL Bundle freeze and production source thresholds.
- DONE — machine-readable capacity, rollback, death-cleanup, Revision-cleanup and Host-fallback audit.
- DONE — final device-preflight manifest, unresolved capability list and reproducible source freeze.
- DEVICE — locked Android build, complete Fixture matrix, four ABI evidence and 20-minute stability gate.

## M5-T1 real Android build baseline

- DONE — exact Host, Fixture and Companion32 debug APK contract is machine-readable.
- DONE — cross-platform locked SDK installation and three-APK build entry points.
- DONE — fail-closed APK ABI/native-library verification and artifact manifest.
- BLOCKED — actual APK production in the current execution container because JDK 17, Android SDK and external dependency access are unavailable.
- NEXT — run the locked build on a prepared workstation, then begin x86_64 Host + x86 Companion Emulator execution.

## M5-T2 cross-width runtime source baseline

- DONE — 32-bit Activity, Service, Receiver and Provider operations route through the Companion Runtime Broker.
- DONE — bounded, SHA-256 verified Base/Split/native artifact transfer into Companion-private storage.
- BLOCKED — dedicated Android isolated UID transport.
- DEVICE — real four-ABI APK build and cross-package Binder execution.

## M5-T3 ordered broadcast and foreground-service source baseline

- DONE — ordered result propagation, receiver/policy abort distinction and bounded chain-wide deadline.
- DONE — Guest PendingResult one-shot finish, Binder-death cleanup and bounded result payload validation.
- DONE — pending foreground promotion, background-start policy, type-mask validation, notification ownership, demotion, timeout and process recovery.
- DEVICE — Android AMS/SystemUI enforcement, hidden PendingResult compatibility and OEM behavior.

## M5-T4 native network, loader and diagnostics source baseline

- DONE — bounded socket lifecycle, IPv4/IPv6 endpoint policy, address/interface projection and sensitive socket-option controls.
- DONE — ELF ABI/type validation plus library-FD, offset, RELRO, reserved-address and linker-namespace policy.
- DONE — alternate-stack native fatal evidence, real Host SIGSEGV fixture, ANR episode state, bounded thread dumps and SHA-256 export.
- DEVICE — Android Bionic/linker, VPN/Connectivity, tombstone/AMS ANR and OEM behavior.

## M5-T5 locked four-APK device-lab source baseline

- DONE — independent 32-bit Fixture and shared package-neutral component/native probes.
- DONE — exact Host, Fixture64, Fixture32 and Companion32 artifact/ABI/native-library contract.
- DONE — offline-first checksummed Android toolchain bootstrap for Windows and Linux.
- DONE — deterministic AVD runner, two-user 64/32-bit component suites and independent formal evidence gate.
- BLOCKED — actual APK build and official Emulator execution in the current environment because JDK 17 and Android SDK/NDK/Emulator are unavailable.
- DEVICE — first formal x86_64+x86 run, 20-minute stability, then ARM64/ARM32 and OEM validation.

## M5-T6 dedicated isolated Service source baseline

- DONE — four predeclared Android isolated Service workers and independent four-slot ownership.
- DONE — typed Session/generation/slot/component/revision/capability Binder transport.
- DONE — Service-only route, non-Service rejection, outer/payload identity equality and ordinary Broker Binder removal.
- DONE — Binder-death recovery, generation advancement, package-stop cleanup and combined status metrics.
- PARTIAL — production route is source-wired but real Guest APK/data/native access under isolated UID and SELinux is unverified.
- DEVICE — locked Android build, isolated UID evidence, AMS lifecycle, restart/rebind and OEM validation.

## M5-T7 PackageManager, PackageInstaller, Shared Library and Instrumentation baseline

- DONE — typed Java/native/SDK/static shared-library requirements, required/optional semantics, version and certificate matching.
- DONE — import-time and runtime-state deterministic resolution with required dependencies failing closed.
- DONE — typed Instrumentation metadata and Guest PackageManager query projection.
- DONE — persisted PackageInstaller-style OPEN/SEALED/COMMITTING/FAILED sessions, progress, failure evidence and retry.
- PARTIAL — inherit-existing, rollback and nonzero install flags are represented but intentionally rejected at commit.
- DEVICE — Android library catalog/ART loading, SharedLibraryInfo constructors, Instrumentation execution and PackageInstaller callbacks.

## M5-T8 Location, device identity, Telephony, Wi-Fi, Bluetooth and Sensor baseline

- DONE — typed, revision-bound per-package/per-virtual-user profiles with BLOCKED/STATIC/HOST modes.
- DONE — bounded atomic persistence, CRC validation, corrupt-state quarantine, optimistic updates and Runtime observer refresh.
- DONE — source-wired Location, Build/Android ID, Telephony/Registry/Subscription, Wi-Fi/Scanner, Bluetooth and Sensor catalog projection.
- DONE — fail-closed Guest startup when a configured non-HOST domain cannot install its required framework hooks.
- PARTIAL — device-identifiers policy/GMS identity, BLE callback transport and native Sensor event delivery.
- DEVICE — Android hidden fields/constructors/Binder signatures, real callbacks, permission behavior and OEM validation.

## M5-T9 Window, ActivityClient, Input/IME and Display baseline

- DONE — typed package/virtual-user interaction profiles with BLOCKED/STATIC/HOST modes and Package-Service ownership.
- DONE — bounded atomic persistence, CRC validation, corrupt-state quarantine, optimistic updates and Runtime observer refresh.
- DONE — WindowSession/window-token, input-client, Activity lifecycle and virtual-display generation-scoped ownership ledgers.
- DONE — source-wired WindowManager/WindowSession, ActivityClient, InputMethodManager and DisplayManager projection with reversible identity rewriting.
- DONE — fail-closed Guest startup when a configured non-HOST interaction domain lacks a required hook.
- PARTIAL — real Surface/InputChannel/Insets, ActivityClient transaction matrix, IME rendering/InputConnection and virtual-display surfaces.
- DEVICE — Android hidden Binder signatures, SystemUI, multi-window/PiP, keyboard, display callbacks and OEM validation.

## M5-T10 Connectivity, DNS, Proxy/VPN and Java network-services baseline

- DONE — typed package/virtual-user network profiles with BLOCKED/STATIC/HOST modes and Package-Service ownership.
- DONE — bounded atomic persistence, CRC validation, corrupt-state quarantine, optimistic updates and Runtime observer refresh.
- DONE — source-wired Connectivity active/all network, capabilities/link properties, NetworkInfo, metering/background and proxy projection.
- DONE — deterministic DNS records/NXDOMAIN, raw-query denial and resolver policy source surface.
- DONE — bounded Connectivity callback and VPN-session ownership with explicit release and generation cleanup.
- DONE — fail-closed Guest startup when a configured non-HOST network domain lacks a required hook.
- PARTIAL — real hidden framework objects, callback Binder/PendingIntent transport, resolver/netd/private-DNS and PAC behavior.
- DEVICE — VPN consent/TUN/routing, per-UID enforcement, SELinux, Doze, captive portal, SystemUI and OEM network validation.

## M5-T11 User, launcher, shortcut, widget, usage and settings baseline

- DONE — typed package/virtual-user application-environment profiles with BLOCKED/STATIC/HOST modes and Package-Service ownership.
- DONE — bounded atomic shortcut, widget, usage-event and Secure/System/Global settings persistence with CRC, quarantine and optimistic updates.
- DONE — source-wired UserManager, LauncherApps, ShortcutManager, AppWidgetManager, UsageStatsManager and ContentService/Settings projection.
- DONE — callback/observer ownership, package visibility, shortcut/widget quotas and cross-package usage denial.
- DONE — fail-closed Guest startup when a configured non-HOST application-environment domain lacks a required hook.
- PARTIAL — real hidden framework wrappers, launcher callbacks, RemoteViews/widget rendering, UsageStats enforcement and Settings Provider cache variants.
- DEVICE — Android Binder signatures, System Launcher/SystemUI, AppOps, observer transport, sync adapters and OEM validation.

## M5-T12 WebView, GMS, OEM and detection baseline

- Added typed, durable per-package/virtual-user WebView, Google services, OEM and detection profiles.
- Added WebView provider/data-directory projection and bounded renderer ownership.
- Added deterministic Google/OEM identity, optional GMS/OEM Binder hooks and explicit fail-closed readiness.
- Added policy-driven hidden package/class handling and native `/proc` policy requirement.
- Source status is PASS; production status is PARTIAL; Android build and device evidence remain blocked/0.
- Next source candidate: power/media/biometric/accessibility/autofill and remaining system-service breadth, unless the
  locked Android build is available first.
