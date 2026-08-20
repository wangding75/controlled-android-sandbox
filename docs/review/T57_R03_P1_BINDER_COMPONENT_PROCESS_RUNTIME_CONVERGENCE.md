# T57-R03 P1 Binder + Component/Process Runtime Convergence

## Scope and result

This document records the complete P1 production campaign for the common Controlled Android
Sandbox runtime. It covers the Binder boundary and the Service, Job, Broadcast, Provider,
PendingIntent and Process paths above it. It does not contain XH logic, target-package hooks,
OEM adaptations, or the later Package/Loader, Native, SystemService/GMS or application campaigns.

The implementation keeps existing high-level Java proxies where they own Android API adaptation,
but moves low-level Binder identity and transport into
`sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/binder/`.

## Root causes found

1. Existing service hooks adapted Java methods, but there was no reusable transaction-level
   boundary for `IBinder` descriptor/code/Parcel/flags, returned Binder objects, callback Binders,
   or death leases.
2. A Java `Proxy` that implements `IBinder` is not a marshalable Android Binder object. On the
   real API32 path it caused system-server Binder arguments and callbacks to be seen as null or
   invalid. The production boundary therefore uses a real local `android.os.Binder` subclass;
   the Java `IBinder` proxy remains only for the repository's plain-Java static stubs.
3. Not every Binder-shaped value is an AIDL interface. Activity/service/resultTo/permission and
   published-service tokens are framework authority keys and must retain their exact raw Binder
   identity. The boundary is declared-type-aware and deliberately preserves these values.
4. Returned AIDL interfaces and callback/listener Binders could otherwise escape after the root
   service was projected. The boundary creates stable child leases and recursively applies the
   same session fence.
5. Hook teardown previously had no single point that invalidated all Binder leases for the active
   process generation. `GuestIdentity` now owns the fence and `FrameworkHooks.close()` closes it
   before resource teardown.

## Final architecture

```text
Guest Component/API
    -> GuestIdentity / IdentityContext
    -> BinderInterceptionFoundation (root + transaction + child Binder leases)
    -> existing semantic adapter (Service/Job/Broadcast/Provider/PI policy)
    -> physical Host framework operation
    -> Android framework/System Server
    -> callback / returned Binder / lifecycle event
    -> Guest projection, session and generation fence
```

`BinderIdentity` carries the virtual package, virtual UID/user, operation package, attribution
tag, process name and session/generation. The physical UID/PID remains the host process identity;
CAS does not pretend application code can forge the kernel caller. The virtual identity is applied
at the semantic contract and transaction policy boundaries.

`BinderTransaction` retains the live input/output Parcels, transaction code, descriptor, flags,
one-way status and exception path. `BinderTransactionInterceptor` is reusable by semantic adapters;
individual system services do not need to implement a new low-level Binder relay.

`BinderInterceptionFoundation` wraps root services, nested returned AIDL interfaces and callback
arguments. `linkToDeath`, `unlinkToDeath`, explicit invalidation and the shared fence retire stale
root and child leases. `IApplicationThread` and other exact AOSP process endpoint types are
preserved where the framework registers the raw identity itself.

## ServiceManager and Binder foundation audit

`ServiceManagerBinderHook` and `ReflectiveServiceHook` now descriptor-validate the service, install
the typed semantic projection, and publish the projection's CAS Binder into the ServiceManager
cache. `getService`, `checkService`, cache replacement, `queryLocalInterface`, returned typed
interfaces, callback arguments and death invalidation use the common boundary.

The AOSP `waitForService` path was explicitly audited. Android's Java implementation can call a
native wait path directly rather than consulting the Java `sCache`; this is a hidden/native
integration boundary, not something that can be safely solved by pretending a Java cache entry
intercepts it. CAS's supported Guest framework managers use descriptor-validated projected service
fields/cache paths, and no direct Guest operation was added that returns an unprojected Host Binder.
Direct hidden `waitForService` interception remains a documented integration/native debt; it is
not silently claimed as complete and it is not a security bypass in the supported Guest API.

The former service-local raw `IBinder` proxy in `InputManagerServiceHook` was replaced with the
common foundation. Existing dynamic proxies were not globally deleted: they remain semantic
adapters above the boundary, as required by the campaign contract.

## Component runtime convergence

### Service

The existing framework-owned ActivityThread service lifecycle remains the owner of the actual
Service object. The P1 boundary is shared by start/stop/bind/publish/callback operations and by
the `GuestServiceConnectionRelay`, `FrameworkServiceBindingLedger`,
`GuestActivityThreadServiceLifecycle`, `GuestActivityThreadServiceBridge`, foreground transport
and runtime service coordinator. Start/foreground-start project Guest component identity and
physical host mapping; stop operations reach the framework lifecycle and do not only delete a
virtual ledger entry. Multiple clients, repeated binds, unbind/rebind and callback order remain
ledger/framework coordinated. Sticky remote-service recovery was exercised after a real remote
process kill and produced a new process generation plus framework recovery/start markers.

The MuMu API32 notification channel rejects the fixture's post-recovery foreground notification;
that is recorded as notification/integration debt. It does not change the Service routing or
session-generation result and is outside this P1's notification campaign.

### Job / JobService

`VirtualJobService` and `GuestJobServiceBridge` continue to own the semantic job execution and
version-aware callback adaptation. The callback Binder is projected through the same foundation;
generation, stop, finish and process-death paths retain the existing reschedule/auto-finish policy.
The implementation does not add scattered API-version patches for `IJobCallback` or unbind.

### Broadcast

Manifest metadata resolution, process bootstrap, dynamic receiver registration/unregistration and
session ownership remain in the broadcast runtime. Ordered delivery retains result code, result
data, result extras, abort and sequence state through the existing ordered receiver token registry.
Receiver callback Binder arguments are covered by the common callback boundary. Permission,
exported, sender identity, package visibility and background restrictions remain semantic framework
checks; no OEM-specific relaxation was introduced.

### Provider

The existing Provider transport was preserved and audited rather than rewritten. `BrokerProviderRuntime`,
`ProviderCursorTransport`, `CursorWireCodec`, `BrokerFileRuntime`, `GuestProviderFileTransport`,
`ProviderBatchRuntime`, `AtomicProviderBatch`, `BrokerObserverRuntime` and
`ProviderLifecycleCoordinator` retain authority, virtual package, process and caller identity.
Cursor-window/large-cursor close and cancellation, file/asset/typed-file descriptor ownership,
batch/observer/URI permissions and persistable grants remain real transport operations. Native FD
escape beyond the Java ownership contract is deferred to the Native campaign.

### PendingIntent / IntentSender

The real PendingIntent runtime is unchanged in shape: virtual creator/request/flag projection is
backed by a physical host PendingIntent and the system dispatches through the Guest relay. Activity,
service and broadcast targets, update/cancel, immutable/mutable and one-shot state remain in the
existing durable runtime. Process death and relay ownership use the same session/generation model.
The API32 framework probe's raw callback marker expects the original guest action, while the
intentional system-holder route observes the explicit relay action; the actual dispatch and target
callback pass. This is a probe contract/evidence debt, not a reason to weaken the real PI path.

## Process and lifecycle ownership

Ordinary process capacity remains 64 slots; isolated process capacity remains 16 slots. Process
name, PID mapping, virtual UID, package ownership, session and generation are not collapsed when
`:main`, `:remote`, `:push` or `:web` are used. Component bootstrap obtains the process-specific
identity and Binder leases. A real process death invalidates the old session, stale child callback
leases fail closed, and recovery creates a new generation rather than reusing old state.

The API32 manual remote-service recovery smoke killed the real remote Guest PID. Logs showed the
old process disconnect, a replacement PID, `GUEST_SERVICE_FRAMEWORK_RECOVERED`, and a new
`GUEST_SERVICE_FRAMEWORK_STARTED` with a new generation. The recovery runner itself has a separate
fixture/launch-gate issue for the no-history foreground-service kickoff and is listed as integration
debt.

Activity/FIX03 remains framework-owned. The P1 smoke retained virtual Activity identity versus
physical Stub identity, task reuse and framework lifecycle markers; no Activity production rollback
or FIX03 re-open was made.

## VA / NewBlackbox reference decision

The locked clean-room sources under `ref/upstream/` were used only for architecture comparison.
No source was copied or mechanically translated.

### VA

`REFERENCE:` VirtualApp centralizes virtual activity/process/service state in
`ref/upstream/VirtualApp/VirtualApp/lib/src/main/java/com/lody/virtual/server/am/VActivityManagerService.java`,
tracks process state in `.../am/ProcessRecord.java`, coordinates static receiver delivery and
ordered result completion in `.../am/BroadcastSystem.java`, and routes service connections and
provider acquisition through the virtual activity manager. Its README also records historical
`publishService` crash, JobService unbind, process-start re-entry deadlock and newer `IJobCallback`
adaptation fixes.

`CAS:` CAS keeps framework-owned Activity lifecycle and uses its own runtime coordinators and
process/session/generation ledgers. P1 adds one Binder substrate below those semantic coordinators,
not a second virtual ActivityManager implementation.

`GAP:` VA's historical fixes identify the same compatibility domains, but do not by themselves
provide a safe API32/API35/API36 transaction boundary for CAS's Host/Guest split. Direct hidden
ServiceManager native-wait interception remains a CAS integration debt.

`DECISION:` Reuse the behavioral categories—central ownership, connection records, receiver
completion, process records and version-aware callbacks—while independently implementing the CAS
identity contract, real Binder transport and existing framework-owned Activity semantics.

### NewBlackbox

`REFERENCE:` NewBlackbox separates `BActivityManagerService`, `ActiveServices`,
`BroadcastManager`, `ProcessRecord`, and proxy-service records under
`ref/upstream/NewBlackbox/Bcore/src/main/java/top/niunaijun/blackbox/core/system/`; its physical
Binder hook entry is `.../src/main/cpp/Hook/BinderHook.h`, and host-facing service dispatch uses
`.../proxy/ProxyService.java` and `ProxyServiceRecord.java`.

`CAS:` CAS does not move the Guest into an app-specific proxy-service universe. Existing Service,
Broadcast, Provider, Job and PendingIntent runtimes retain their production contracts, while the
new Binder foundation is shared by root, returned and callback Binders.

`GAP:` NBB demonstrates explicit proxy component/process ownership and native Binder-hook
architecture; it is not an AOSP-version proof for CAS's declared framework contracts, raw token
identity or cross-ABI companion process.

`DECISION:` Adopt the separation of component/process records and callback ownership as reference
architecture only. Do not copy NBB proxy classes or introduce a privileged Binder driver hook.

## VA PRO compatibility gap mapping

The README/changelog signals were grouped by root cause instead of made into individual hooks:

| VA PRO signal family | CAS P1 closure | Remaining debt |
|---|---|---|
| Binder identity, returned Binder, callback Binder and death | Common root/child Binder leases, descriptor and transaction metadata, callback wrapping and session fence | Direct native hidden `waitForService` remains a documented integration boundary |
| Service start validation, `publishService`, stop/unbind and process-start deadlock | Framework-owned Service lifecycle plus shared identity/process routing and real callback Binder path | MuMu foreground notification channel on recovery |
| JobService unbind, ANR and `IJobCallback` drift | Existing version-aware Job bridge now sits below the common Binder/callback/session boundary | API35/36 full cross-ABI Job smoke is blocked by companion ABI installability, not source build |
| Manifest/dynamic/ordered Broadcast | Metadata resolution, receiver ownership and ordered result state are retained | Runner/evidence expansion deferred |
| Provider caller identity, cursor/file/batch/observer | Existing strong Provider transport audited under shared identity/lifecycle ownership | Native FD escape is a later Native campaign |
| PendingIntent creator, flags, update/cancel and dispatch | Existing real physical PendingIntent + Guest relay preserved | Probe action expectation differs from relay projection |
| Process startup, multi-process, death/recovery | 64 ordinary / 16 isolated slots, process-specific identity and generation fencing | Recovery runner/fixture orchestration debt |

## Verification record

Required source checks:

```text
python tools/static_android_compile.py                         PASS
python tools/capability/run_local_capability_audit.py --all     diagnostic PASS, 29 PASS / 13 existing KNOWN_ISSUE, NEW_REGRESSION=0
git diff --check                                                PASS
BinderInterceptionFoundationSelfTest                            PASS
```

API32 framework smoke reached `FRAMEWORK_PROBE_PASS` with service start/bind, Job, manifest and
dynamic/ordered Broadcast, Provider query/file/batch, PendingIntent dispatch, multi-process route,
cross-component route and Activity task contract markers. A clean repeat of the framework probe
also reached `FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_DELIVERED`, `...FINISHED`,
`...FRAMEWORK_PASS` and `FRAMEWORK_PROBE_PASS`; the external-broadcast run exposed a probe
concurrency/evidence ordering problem rather than a clean-path Ordered Broadcast failure. The
dynamic Receiver transport now explicitly transfers and finishes the Host `PendingResult`, which
removed the API32 registered-receiver ANR. The manual remote-process death smoke reached
replacement process plus framework Service recovery markers.

API35 and API36 base component smokes reached Guest prepare, `ACTIVITY_FRAMEWORK_READY`,
`INSTRUMENTATION_READY`, `ATMS_ACTIVITY_LAUNCH_REQUEST` and projected Activity transaction
markers. API35 returned `LAUNCH_PASS`. API36's command-side Activity launch gate returned
`LAUNCH_GATE_FAILED` after those framework markers and the temporary Guest process disconnected;
there was no Java fatal exception and this matches the existing FIX03 launch/evidence debt, so it
is not treated as a new P1 Binder/component source regression. The API32 companion APK cannot
install on official x86_64 API35/API36 AVDs (`INSTALL_FAILED_NO_MATCHING_ABIS`), so full
cross-ABI probe evidence is recorded as integration debt rather than treated as a production
source failure. API37 is not installed locally: `DEFERRED_API37`.

## Known issues and boundary of PASS

The remaining issues are runner, probe, fixture, notification-platform or evidence infrastructure
issues unless a later source audit proves a production semantic failure:

- PendingIntent probe callback action compares against the pre-relay action.
- The broad transport runner can overlap external dynamic broadcasts with the long component
  probe and report an Ordered async result mismatch; the isolated clean framework path passes.
- The recovery runner's no-history FGS kickoff can fail before kill-gate capture; the manual remote
  recovery path supplies the production evidence.
- MuMu rejects the fixture's foreground notification channel during recovery cleanup.
- Official API35/API36 x86_64 AVDs cannot install the API32 companion APK.
- API36 command-side Activity launch-gate/process-disconnect evidence remains in the existing
  FIX03 integration debt after the projected framework transaction markers pass.
- Direct hidden native `ServiceManager.waitForService` interception and Native FD escape need
  their respective future integration campaigns.

No XH package name, target-app rule, OEM branch, fake success, permission bypass, process-isolation
disablement or Activity/FIX03 production regression was introduced.
