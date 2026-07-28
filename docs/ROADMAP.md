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
- PARTIAL — AlarmManager power/reboot semantics, Account authenticators, Notification callbacks/channels and JobService constraints/callbacks remain open.
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
