# Controlled Sandbox — Clean-room Development 0.3

A from-scratch Android application-virtualization research project. Production source is intentionally separated into domain, Binder contract, framework adapter, native policy, runtime, product app and test-fixture modules. Product modules contain no VirtualApp, NewBlackbox, Twoyi or fork source. Immutable VirtualApp and NewBlackbox snapshots are preserved only under `ref/` for clean-room architecture and behavior comparison and are excluded from the build.

## Implemented in the current development snapshot

- Defensive single- and multi-APK import into app-private storage with staged Binder-owned install sessions, Base/Feature/Configuration Split validation, dependency ordering, per-artifact SHA-256, signer continuity and downgrade checks.
- Bounds-checked binary `AndroidManifest.xml` parsing for application, activities, services, receivers, providers, intent-filter priority/categories/data/MIME, authorities, permissions, process names and isolated services.
- One Binder-owned package authority in a dedicated process, with a typed PID/UID-bound management capability, atomic package/virtual-instance/policy catalog, legacy migration, immutable SHA-256 APK/native revision publication and orphan cleanup reporting.
- Multiple virtual users/instances with independent data roots and deterministic virtual UIDs.
- Versioned AIDL protocol, SHA-256-bound immutable APK revisions, explicit session state machine, eight process slots, retained Binder connections, death detection, generation recovery and one-time Activity route tokens.
- Guest `DexClassLoader` with host-internal deny rules, dependency-ordered Base/Split class and resource paths, split-aware `ApplicationInfo`/`Context`, instance-scoped storage redirection and host-unwrapping denial, `Application` bootstrap and custom atomic `SharedPreferences`.
- Broker-authoritative Activity, started/bound Service, dynamic Receiver, explicit/implicit manifest Receiver and Provider authority/CRUD/Call/Batch/Cursor/FileDescriptor routing, ordered-broadcast source policy, broker-owned ContentObserver callbacks, Session-bound TTL/one-time URI Grants and unified Provider resource lifecycle cleanup with Guest component bridges.
- Binder-issued, APK-revision-bound virtual package/component snapshot plus per-virtual-user permission, AppOps, package-enabled and component-enabled policy consumed by PackageManager, PermissionManager and bounded AppOps hooks.
- Typed virtual Intent filters and deterministic Guest-local PackageManager query/resolve for Activity, Service, Receiver and Provider metadata, including action/category/data/MIME, default-only, disabled-component, install-source and install-time semantics.
- Catalog-v5 runtime-permission requests and audit, a Runtime-Broker-only typed Binder capability, host-capability-backed effective grants, Activity callback bridging and same-generation permission/AppOps/camera-location service-gate refresh.
- Method-level Camera/Location and bounded AudioManager capture proxies, fail-closed proxy readiness, Attribution-aware AppOps, live cleanup of recognized capability resources and per-generation capability-call audit.
- Durable PendingIntent identity and Broker routing, plus a Package-Service-owned scoped Binder authority for cross-process Clipboard and basic Account state. Revision-scoped exact/repeating Alarm scheduling, offline PendingIntent recovery, Notification Channel/Group/foreground-service/interaction lifecycle and typed JobScheduler constraints, periodic/latency/deadline/expedited/persisted/backoff policy survive Package Service recreation. Android-version device validation remains pending. Receiver and Provider cleanup authorities are extracted from the central Runtime Broker.
- WebView data-directory suffix per virtual user/process slot.
- Native C++ Guest-library PLT/GOT compatibility interception with filesystem/path redirection, virtual `/proc/self` identity files, controlled dynamic-library loading and Host-native tests. Direct syscalls bypass imported-symbol hooks, so untrusted APKs containing packaged ELF/native code are denied by default; explicitly trusted Native Guests run only with a `BEST_EFFORT_COMPATIBILITY` label. Android device/ABI evidence remains pending.
- Structured JSONL runtime diagnostics, uncaught-exception capture and main-thread liveness watchdog.
- A debug-only ADB command surface, comprehensive Fixture APK and strict 20-minute Emulator gate.
- Architecture-boundary checks and generated SBOM.

## Third-milestone status

**Not complete.** The repository intentionally refuses to create an M3 release ZIP unless a real Android build and Emulator evidence bundle passes `scripts/check-m3-release-gate.sh`.

The following remain device-gated or incomplete:

- Real Android Gradle Plugin/NDK build in the current execution environment.
- API-level validation of hidden/reflected framework fields and PackageManager Binder signatures.
- Device validation of bound services, dynamic receivers, Provider transport/query routing, URI grants and PendingIntent ownership.
- Dedicated Android `isolatedProcess` UID/SELinux execution; declared remote process names are source-routed but remain device-unverified.
- Activity task/back-stack fidelity across API levels.
- Android-version validation of Guest native-library interception, linker namespace behavior and modern syscall availability across all target ABIs.
- WebView renderer-process isolation evidence.
- A 20-minute zero-crash/zero-ANR Emulator report.

See `docs/M3_GATE.md` and `docs/TEST_REPORT.md`.

## Reference-source baseline

`ref/` preserves the exact uploaded public NewBlackbox and VirtualApp source archives, extracted trees, source URLs, archive commit comments and per-file SHA-256 manifests. These files are reference-only and are not Gradle modules. NewBlackbox declares Apache-2.0 at its root. The uploaded VirtualApp root archive contains no root license and its README states commercial authorization restrictions, so it must not be copied into product modules or publicly redistributed without separate rights review. See `ref/README.md` and run `python3 tools/reference_sources.py verify`.

## Local verification

```bash
./scripts/verify-all.sh
```

This runs every gate available without Android SDK/Emulator access. It does **not** substitute for the device gate.

## Real Emulator gate on Windows

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\run-emulator-m3.ps1
```

The script builds and installs the host and Fixture APKs, runs two virtual instances, exercises components/JNI, performs the requested stability loop and writes evidence under `artifacts\m3-emulator-*`.

## Build prerequisites

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 or newer
- Android NDK and CMake 3.22.1
- Gradle/Maven dependency access or a prepared cache

```bash
./gradlew clean check :fixture-basic:assembleDebug :app:assembleDebug
```

## License

Original project code is released under Apache License 2.0. Imported APKs retain their own licenses and distribution restrictions. See `LICENSE`, `NOTICE`, `THIRD_PARTY_LICENSES.md` and `docs/CLEAN_ROOM_POLICY.md`.

## Frozen baseline and reproducible build

The uploaded B3-T5A source is frozen by the `baseline-b3-t5a-upload` tag. Toolchain values and the uploaded archive identity are recorded in `build-environment.lock.json` and `docs/BASELINE.md`.

Host-only gates:

```bash
./scripts/verify-all.sh
```

Locked Android cache bootstrap on Windows:

```powershell
.\scripts\bootstrap-build-cache.ps1
```

Offline two-pass reproducibility check:

```powershell
.\scripts\reproducible-build.ps1 -VerifyTwice
```

The two-pass command builds unsigned release APKs twice with a clean task graph, no Gradle build cache and no parallel execution, then compares their bytes. It intentionally does not run an Emulator or physical-device test.

## M4-T13 source baseline

M4-T13 adds a typed Guest JobService execution bridge. Trusted Host Job callbacks are reduced to bounded `VirtualJobParametersSnapshot` data, Package Service owns the `SCHEDULED → DISPATCHING → RUNNING` state machine, and Guest `jobFinished` is a one-shot package/user/process/generation/dispatch-token capability. This is source/host evidence only; Android-version and OEM JobScheduler behavior remains device-gated.

## M4-T14 source baseline

M4-T14 moves Service ownership behind `RuntimeServiceCoordinator`. Started, bound and foreground state is generation-aware; bound clients may provide death-linked Binder tokens; stale start IDs cannot stop newer work; and sticky/redeliver Services are recreated after Guest process recovery. Foreground notification/type enforcement and Android/OEM lifecycle behavior remain device-gated.

## M4-T15 source baseline

M4-T15 makes the Broker-owned Activity/Task model recoverable, queryable and reachable from Guest `ActivityManager`/`ActivityTaskManager` calls. It adds bounded launch-flag/result semantics, package/user/revision-isolated running and recent task projections, local `IAppTask` Binder handles, owner-checked task mutations, and an atomic CRC-protected checkpoint that restores saved task state without reviving dead one-time route or result-delivery authority. System Recents visuals, Window/Transition integration and Android/OEM device compatibility remain open.

## M4-T16 source baseline

M4-T16 makes PendingIntent identity durable and revision-bound, including Activity Result, mutability, FillIn/ClipData, sender permissions, virtual creator identity and cross-generation reattachment. It adds typed exact/repeating Alarm state, Listener and PendingIntent delivery, offline retention, Package-Service recovery, Notification Channel/Group lifecycle, foreground-service mapping and persistent click/delete/action sender identity. JobScheduler now persists typed network/power/storage/idle constraints, periodic/latency/deadline/expedited/persisted policy and bounded linear/exponential retry state while retaining the trusted Host-to-Guest JobService bridge. Android AlarmManager/SystemUI/JobScheduler timing, quota and OEM behavior remain device-gated.


## M4-T17 B1 source baseline

M4-T17 B1 extends the Guest-only PLT/GOT hook set with `openat2`, `statx`, `renameat2`, `faccessat2`, `getdents64` and file-backed `mmap`. `/proc/self/maps`, `/proc/self/cmdline` and `/proc/self/status` are projected from virtual process identity without exposing Host private paths. `dlopen` and `android_dlopen_ext` are constrained to the Guest native-library root and an explicit public system-library allowlist. These are source and Host-native test results; Android linker, OEM and ABI behavior is not yet device-proven.


## M4-T17 B2 source baseline

M4-T17 B2 extends the Guest-only native policy to IPv4/IPv6 sockets, forward and reverse DNS, virtual hostname and bounded synthetic interface enumeration. Host network-interface identity is not returned to Guest native code. RECORD_AUDIO and AppOps decisions now configure a generation-bound native capture gate; AAudio and NDK MediaRecorder start/stop symbols are intercepted when present, and revocation clears active capture authority while the existing Binder capability lease registry releases Java/Binder audio resources. These are source and Host-native tests; Android audio-server, VPN, proxy and OEM network behavior remain device-gated.

## M4-T17 B3 source baseline

M4-T17 B3 adds an independent `sandbox-companion32` APK for `armeabi-v7a` and `x86`, while the Host native module remains limited to `arm64-v8a` and `x86_64`. A Bundle-free, signature-permission Binder contract carries protocol, session, generation, virtual user, APK revision, one-time nonce and requested ABI. Host routing rejects unknown ABI metadata and never silently executes a 32-bit Guest in the 64-bit Broker process. The companion module and JNI boundary are source/Host-compile verified; Android APK packaging, cross-package Binder behavior and full 32-bit Guest execution remain device-gated.

## M4-T17 source baseline

M4-T17 hardens Guest Native execution across filesystem, procfs, dynamic loading, IPv4/IPv6 network identity and audio capture authorization. Native ABI is now an explicit package/runtime field. The Host native runtime is limited to `arm64-v8a` and `x86_64`; a separate signature-permission `sandbox-companion32` APK carries `armeabi-v7a` and `x86` with a typed, generation-bound Binder contract. This baseline contains source and Host-native evidence only. Four-ABI Android packaging, cross-package Binder execution and complete 32-bit Guest lifecycle remain device-gated and fail closed in the current Host route.

## M4-T18 source baseline

M4-T18 freezes the device-test preflight source baseline. Large mutable state owners were split into dedicated persistence/state components; durable files are bounded, checksum-protected and corruption-isolated; the exact legacy AIDL `Bundle` exception set is frozen; and machine-readable gates cover capacity, rollback, Binder/process death cleanup, APK Revision cleanup and Guest-query Host-fallback controls across twelve resource domains. A final preflight manifest records every production partial/blocked capability and the required Android build, component, scheduling, Native/ABI, WebView and stability evidence. **Device evidence remains 0**: no Android APK build, Emulator run, physical-device run, third-party compatibility rate or 20-minute stability result is claimed by this source baseline.
## M5-T1 real Android build baseline

M5-T1 freezes one fail-closed build path for the 64-bit Host, 64-bit Fixture, and 32-bit Companion APKs. Exact SDK packages, Gradle tasks, APK paths, ABI sets, and required native libraries are machine-readable in `build-environment.lock.json`. Use `scripts/install-locked-android-components.*` to install the locked SDK set and `scripts/build-device-test-apks.*` to build and collect the three debug APKs. The artifact verifier rejects ABI leakage, missing native libraries, duplicate ZIP entries, and unsafe paths. Source gates can pass without an Android SDK; an APK build is only PASS when the three APKs are actually produced and validated.

## M5-T2 cross-width runtime source baseline

M5-T2 replaces the 32-bit Companion probe-only path with a source-wired production Runtime Broker route. The Host transfers the Base APK, Split APKs and selected native libraries through a signature-protected, bounded, SHA-256 verified Binder file channel into Companion-private revision-scoped storage. `armeabi-v7a` and `x86` prepare, Activity, Service, Receiver, Provider and shutdown operations then execute through the same Runtime Broker/Guest Runtime implementation used by the 64-bit Host. Declared remote process names retain the existing session/slot model. Isolated components now fail before ordinary process allocation, but a dedicated Android isolated-UID transport remains blocked. Real APK build and device evidence remain 0.

## M5-T3 ordered broadcast and foreground-service source baseline

M5-T3 completes the source-wired Ordered Broadcast result chain, Guest PendingResult completion bridge and foreground-Service policy model. Ordered chains now have a bounded global deadline, timeout/skipped accounting and explicit terminal reasons. PendingResult completion is one-shot, payload-bounded and tied to Binder death. `startForegroundService` now creates a pending promotion with background-start policy, declared/requested type validation, notification ownership, timeout cleanup and process-death re-promotion. Android APK build and device evidence remain 0 because the current environment lacks the locked JDK 17 and Android SDK/NDK.

## M5-T4 native network, loader and diagnostics source baseline

M5-T4 completes the source-wired Native network, dynamic-loader and Crash/ANR closure. Guest sockets now use bounded lifecycle tracking, IPv4/IPv6 endpoint policy, local/interface identity projection and sensitive socket-option controls. Guest library loading validates ELF ABI/type plus `android_dlopen_ext` FD/offset/RELRO/reserved-address/namespace inputs. Diagnostics add alternate-stack fatal-signal evidence, a real SIGSEGV Host fixture, ANR episode tracking, bounded thread dumps and SHA-256 exports. Android APK build and device evidence remain 0 because the current environment lacks the locked JDK 17 and Android SDK/NDK.

## M5-T5 locked four-APK device-lab source baseline

M5-T5 adds an independent 32-bit Fixture, freezes Host/Fixture64/Fixture32/Companion32 as one ABI-checked build set, and provides offline-first toolchain bootstrap plus a deterministic official-Emulator runner. Formal evidence requires both x86_64 and x86, two virtual users, complete 64/32-bit component commands, typed Companion32 bitness proof, runtime diagnostics and at least 1,200 seconds without controlled-sandbox crash or ANR evidence. The current environment has no locked JDK 17 or Android SDK/Emulator, so Android build and device evidence remain 0.

## M5-T6 dedicated isolated Service source baseline

M5-T6 adds four predeclared `android:isolatedProcess=true` Service workers, a separate four-slot Session registry, typed per-generation capability contracts, outer/payload identity enforcement, platform PID/UID evidence, Service-only routing and Binder-death recovery. Ordinary Guest capacity remains eight slots and isolated Activity/Receiver/Provider requests fail closed. Production status is partial and device evidence remains 0 because the locked Android build and Emulator environment is unavailable.

## M5-T7 package surface source baseline

M5-T7 expands the clean-room package surface with typed Java/native/SDK/static shared-library requirements, deterministic required/optional resolution, revision-bound instrumentation metadata and PackageManager queries. Binder-owned install sessions now persist typed parameters, progress, staged bytes, commit attempts, bounded failure evidence and explicit retry. Unsupported inherit-existing, rollback and nonzero install-flag commits fail closed. Real Android PackageInstaller callbacks, SharedLibraryInfo constructors, ART loading and Instrumentation execution remain device-gated; device evidence remains 0.

## M5-T8 device-service virtualization source baseline

M5-T8 adds Package-Service-owned, revision-bound virtual profiles for Location, device identity, Telephony,
Wi-Fi, Bluetooth and Sensor domains. Every profile uses explicit BLOCKED/STATIC/HOST modes, bounded atomic
persistence, optimistic versioning and Runtime observer refresh. Framework source paths project common queries,
deny unsupported mutations and block Guest startup when a configured non-HOST hook is unavailable. Build and
Settings identity, multi-SIM subscriptions, Wi-Fi scanning, Bluetooth metadata and Sensor catalogs are covered in
Host/static evidence. Hidden Android signatures, device-identifiers/GMS, BLE callback transport and native Sensor
event queues remain device-gated; device evidence remains 0.

## M5-T9 Window, ActivityClient, Input/IME and Display source baseline

M5-T9 adds Package-Service-owned, package/virtual-user interaction profiles and generation-scoped ownership ledgers for
Window/WindowSession, ActivityClient, InputMethodManager and DisplayManager. Static policy projects display metrics,
hides Host IME catalogs, rewrites interaction identity only for the duration of a Host call, enforces window/input/
display limits and fails Guest startup when a required non-HOST hook is absent. Real Window/Surface/InputChannel,
ActivityClient transaction, keyboard rendering, virtual-display surface and OEM behavior remain device-gated;
device evidence remains 0.

## M5-T10 Connectivity, DNS, Proxy/VPN and Java network-services source baseline

M5-T10 adds Package-Service-owned, package/virtual-user network-service profiles and generation-scoped ownership
ledgers for Connectivity callbacks and VPN sessions. Static policy projects active/all networks, capabilities, link
properties, legacy network information, proxy state and deterministic DNS records without using Host network identity,
and enforces explicit mutation, callback and VPN-session limits. Missing required non-HOST Connectivity/DNS/VPN hooks
block Guest startup. Real Android network object layouts, resolver/netd integration, callback Binder transport, TUN
routing and OEM behavior remain device-gated; device evidence remains 0.

## M5-T11 User, launcher, shortcut, widget, usage and settings source baseline

M5-T11 adds repository-owned, package/virtual-user-isolated profiles and bounded durable data for UserManager,
LauncherApps, ShortcutManager, AppWidgetManager, UsageStatsManager and Settings/ContentService. Common framework
query/mutation paths are source-wired with `BLOCKED`/`STATIC`/`HOST` behavior and fail-closed Guest launch readiness.
Real hidden Binder/object layouts, launcher/widget callbacks, UsageStats enforcement and Settings Provider behavior
remain Android-build/device dependent; device evidence remains zero.

## M5-T12 WebView, GMS, OEM and detection source baseline

M5-T12 adds a Package-Service-owned compatibility profile for WebView, Google-service identity, OEM surfaces and
bounded virtual-environment detection governance. WebView receives a per-package/user/process data suffix, provider
projection and bounded renderer registry. Google and OEM identities use deterministic virtual values, optional Binder
hooks and explicit availability lists. The Guest class loader and PackageManager apply configured hidden class/package
policy, while non-HOST `/proc` sanitization requires the Native policy. This is Source PASS / Production PARTIAL only;
Chromium, GMS, OEM and third-party detection behavior remains Android build and device-gated, with device evidence 0.

## M5-T13 policy, accessibility, autofill, biometric, privacy and power source baseline

M5-T13 adds a Package-Service-owned policy profile for DevicePolicy, Accessibility, Autofill, Biometric/Fingerprint,
Sensor Privacy and Power/Vibrator services. Common query and bounded lifecycle paths are source-wired with explicit
`BLOCKED`/`STATIC`/`HOST` behavior, quota enforcement, rollback and fail-closed Guest launch readiness. The source does
not fabricate successful privileged policy changes or hardware authentication. Real Binder signatures, SystemUI,
Accessibility/Autofill callbacks, TEE biometrics, Doze/WakeLock accounting and physical vibration remain Android build
and device-gated; device evidence remains 0.

## M5-T14 media, communication and archival system-environment source baseline

M5-T14 adds repository-owned typed profiles and reversible source hooks for MediaSession, MediaRouter, Audio routing
and focus, SMS messaging, Backup and DropBox. Profiles are isolated by package and virtual user, persisted by Package
Service with optimistic versions, CRC and corrupt-state quarantine, and delivered to revision-bound Runtime sessions.
Media/router/focus ownership, SMS quota records and DropBox writes are bounded. Static SMS never calls the Host carrier
service. Source status is PASS; production status is PARTIAL; Android build and device evidence remain blocked/0.

## M5-T15 peripheral and external system-services source baseline

M5-T15 adds typed, durable per-package/per-virtual-user NFC, USB, Printing, Companion Device, MediaProjection, Camera
and configured OEM system-service profiles. It source-wires reversible service hooks, bounded generation-local
sessions, transactional OEM hook installation and fail-closed Guest readiness without exposing Host peripheral state.
Source status is PASS; production remains PARTIAL; the locked Android build and all physical-device evidence remain
blocked by the unavailable JDK 17/Android toolchain.

## M5-T16 global source-closure baseline

M5-T16 adds typed V2 Runtime operations, migrates repository-owned callers away from direct Bundle IPC, fixes inverse method-name collisions, and closes the RestrictionsManager false-coverage gap. Legacy Bundle methods remain compatibility adapters. The source audit records remaining service and large-class debt separately from Android/device evidence.

## M5-T17 privileged environment-services source baseline

M5-T17 closes the six source-feasible service gaps identified by the M5-T16 audit: SearchManager, StorageStats,
GraphicsStats, ContextHub, PersistentDataBlock and SystemUpdate. Typed per-package/per-virtual-user profiles are owned
by Package Service, atomically persisted and delivered to revision-bound Runtime sessions. Reversible Framework hooks
project deterministic queries, bound graphics/hub ownership and fail closed for unsupported privileged mutations.
Real graphics buffers, ContextHub hardware/callbacks, privileged persistent storage and the system-update engine remain
Android-build/device dependent; source status is PASS, production status is PARTIAL and device evidence remains 0.

## M5-T18 architecture-quality source baseline

M5-T18 hardens the offline source baseline without adding capability claims. Five Binder-client surfaces now share a death-aware, retrying connector; virtual-account passwords and tokens migrate to AES-GCM schema-6 persistence; account responsibility is extracted from the central system-service store; and known inverse/sub-string dispatch collisions have direct regressions. A locked JDK-17 CI workflow and a device-stage instrumentation smoke entry are defined. Twelve legacy Bundle AIDL methods and fourteen production classes above 500 lines remain explicitly recorded. The encryption key is app-private rather than Android Keystore-backed, the CI workflow has not executed remotely in this environment, and Android build/device evidence remains 0.
## M5-T19 architecture-decoupling source baseline

M5-T19 removes all twelve legacy Bundle methods from the Broker/Guest AIDL surface and keeps generic runtime execution on typed V2 request/result contracts. Package capability sessions, Activity/Task checkpoint handling, ordinary Guest binding, durable system-service records and application-environment value projection now have independent owners. The same-scope count of production classes above 500 lines decreases from fourteen to twelve, and direct method-name substring dispatch is prohibited outside the centralized matcher. Twelve critical source owners have explicit executed Host regressions; this is not a JaCoCo percentage. Android build, generated AIDL, Keystore, Emulator and device evidence remain 0.
## M5-T19.1-A Native Guest trust-boundary fix

M5-T19.1-A closes the product-level direct-syscall finding by changing Native execution admission. APK imports scan every artifact for standard `lib/<abi>/*.so` entries and ELF magic outside `lib/`; packaged Native Guests are rejected unless the install session explicitly records `EXPLICITLY_TRUSTED`. The decision is persisted in the immutable package record and rechecked by `RuntimeClient`, Runtime Broker input validation, Guest specification parsing and the Package-Service authority before a generation-scoped system-service capability is issued. Legacy Native records without trust metadata fail closed.

PLT/GOT rebinding remains a compatibility/redirection mechanism. It does not intercept `syscall(SYS_*)`, inline assembly, downloaded/generated native payloads or a custom user-space ELF loader. Explicitly trusted Native Guests still share the Host application UID on the ordinary route and therefore do not gain a strong hostile-code security boundary. Strong isolation for arbitrary Native code still requires a different UID/isolated execution design and Broker-only Host file/network access.

## M5-T19.1-B Native network buffer and FD correctness fix

M5-T19.1-B prevents hook-mediated receive and peer-query denials from exposing kernel-written data. `recvfrom`, `recv`, `recvmsg`, socket-backed `read`, `getpeername` and `getsockname` now use bounded temporary payload/address/control storage and copy to Guest buffers only after policy approval. Datagram sources are inspected with `MSG_PEEK` under a per-socket receive lock shared by `dup` aliases, so a denied datagram remains queued; denied stream peers are checked before bytes are read. `accept/accept4` close a rejected accepted descriptor without copying its address, although the kernel backlog entry is necessarily consumed.

The Guest import set now covers `send/sendmsg/recv/recvmsg/read/write/accept/accept4`, descriptor duplication through `dup/dup2/dup3/fcntl/fcntl64`, and the existing `sendto/recvfrom` paths. Socket tracking follows accepted and duplicated descriptors. The adapter is split into `native_network_interceptors.cpp` and is built by both Host and Companion32 native targets. These guarantees apply only to PLT/GOT-mediated calls from explicitly trusted Native Guests; direct syscalls and untrusted Native execution remain governed by M5-T19.1-A.
## M5-T19.1-C Binder binding-timeout recovery fix

M5-T19.1-C fixes the shared Binder connector when `bindService` returns `true` but no callback arrives. Each active binding Attempt now has a monotonic deadline. Timeout records `BIND_TIMEOUT`, clears the authoritative Attempt, releases all waiters, safely unbinds the connection once and preserves bounded exponential retry. A subsequent `require()` can create a fresh binding. Late `onServiceConnected` callbacks are rejected by the Attempt epoch check and their adapted capabilities are closed; timeout and `close()` cannot double-unbind the same connection. Host regressions cover initial no-callback timeout, timeout after retry backoff, late callback, close concurrency and 10/20/40 ms bounded retry. Android Binder, Emulator and device evidence remain 0.

## M5-T19.1-D Binder death-registration atomicity fix

M5-T19.1-D replaces four independent `linkToDeath` sequences with one two-phase
`DeathRegistrationHelper`. Broker observer callbacks, active virtual Job executions,
Package virtual-system-service sessions and ordered-Receiver completions now reserve their
authoritative registry entry before linking, then recheck that the same entry remains current and
that the Binder is alive before publishing success. A death delivered synchronously from
`linkToDeath` therefore removes the reservation instead of being followed by a stale insert.
Link failure, immediate death and explicit close share idempotent rollback/unlink ownership.
Deterministic Host regressions invoke the death recipient inside `linkToDeath`; no sleep-based
ordering is used. Real Binder-driver and device timing evidence remains 0.

## M5-T19.1-E Binder collection pagination and credential minimization fix

M5-T19.1-E replaces unbounded virtual Account, PendingIntent, Alarm, Notification/Channel, Job,
Shortcut, AppWidget and Settings collection transfers with typed pages. Every request supplies both
an item limit and a serialized-byte budget. Continuation tokens are HMAC protected and bound to the
collection, scoped capability, immutable snapshot revision, offset and monotonic expiry. A changed
collection rejects an old token with `PAGE_TOKEN_STALE`; tokens cannot be replayed across a virtual
package/user/process scope or another collection. New AIDL methods are appended after the complete
legacy method sequence, preserving all pre-existing Binder transaction IDs.

Legacy `list*` methods remain only as bounded compatibility adapters. They return a complete small
collection or fail with `PAGING_REQUIRED`; they never silently truncate. Binary fields above 64 KiB
are omitted from the Binder page and exposed through a session-scoped, one-time
`ParcelFileDescriptor` grant with length and SHA-256 metadata. Active grants are never silently
evicted; a page stops at the 64-grant window and resumes with its continuation token. Account enumeration now returns only `name` and `type`; password
and authentication tokens remain available solely through their dedicated checked methods. Host
regressions cover item/byte boundaries, token tampering and stale revisions, large binary recovery,
legacy rejection and credential-free account enumeration. Generated AIDL, Android Binder-driver,
Emulator and physical-device evidence remain 0.

## M5-T19.1-H Guest Binder reconnect correctness fix

M5-T19.1-H makes `RuntimeGuestConnectionPool` recover a cached dead Guest Binder inside the same
request before the operation is dispatched. The stale connection is removed atomically, unlinked and
unbound once, while concurrent callers share one in-flight replacement. A replacement Binder is not
published as live until `linkToDeath` succeeds and the slot identity and Binder liveness are rechecked.
A delayed death callback from the retired Binder is identity-checked and cannot remove the replacement. Pre-dispatch death may retry once because the Guest operation has not run; a Binder that
dies after operation dispatch is never replayed, avoiding duplicate side effects. Failure reasons remain
explicit (`DEAD_BINDER`, `BINDER_DIED`, `DISCONNECTED`, `BIND_REJECTED`, `BIND_TIMEOUT`). Android Binder-driver and
device evidence remain 0.

## M5-T19.1-I Guest storage-name collision fix

M5-T19.1-I replaces the lossy `replaceAll(..., "_")` path mapping with one shared
`GuestStorageNameCodec`. Short logical names use reversible UTF-8 Base64URL components; long names
use a bounded `v2h_<sha256>` component whose logical owner is recorded in a CRC-protected registry.
SharedPreferences, databases, normal files, `getDir` and external-file types now use the same codec,
and list APIs return logical names rather than encoded path components.

Registry metadata is used only for hashed long names and proven legacy migrations. Every metadata
transaction holds both a per-root JVM lock and an OS file lock, reloads the latest durable state, and
uses a unique synced temporary file. Reversible short names require no persistent claim.

Legacy underscore paths are migrated only when their logical owner is provably unique. Any underscore
or replacement-derived path is ambiguous from the first access and fails with
`LEGACY_NAME_COLLISION_AMBIGUOUS`; list APIs fail with `LEGACY_NAME_INDEX_AMBIGUOUS` rather than hiding
an unresolved old file. Unique legacy files are discovered and migrated by `fileList`/`databaseList`.
Deleted hashed artifacts release their registry claim. Registry corruption and physical-name conflicts
fail closed. Android filesystem and device
evidence remain 0.
