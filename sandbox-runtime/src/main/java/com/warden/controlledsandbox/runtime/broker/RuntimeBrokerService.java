package com.warden.controlledsandbox.runtime.broker;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.runtime.component.activity.ActivityTaskContractFailure;
import com.warden.controlledsandbox.runtime.component.activity.ActivityResultContractFailure;
import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerReceiverRuntime;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchEvidence;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchGate;
import com.warden.controlledsandbox.runtime.guest.GuestLaunchObservation;
import com.warden.controlledsandbox.runtime.guest.GuestPackageMetadataMapper;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse;
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.NativeGuestPolicyContract;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import com.warden.controlledsandbox.runtime.provider.BrokerCursorRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerFileRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerObserverRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderQueryCancellation;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderBatchRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderLifecycleCoordinator;
import com.warden.controlledsandbox.runtime.provider.RuntimeProviderResourceCoordinator;
import com.warden.controlledsandbox.runtime.status.BrokerRuntimeStatusSource;
import com.warden.controlledsandbox.runtime.status.CombinedSessionMetricsRepository;
import com.warden.controlledsandbox.runtime.status.ServiceMetricsSource;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.IRuntimeStorage;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.contract.ProcessSlotContract;
import com.warden.controlledsandbox.contract.PackageArtifactSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.domain.port.AuditSink;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.PackageRevision;
import com.warden.controlledsandbox.domain.component.provider.ProviderAuthorityRegistry;
import com.warden.controlledsandbox.domain.component.provider.ProviderObserverRegistry;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionRevisionPolicy;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.identity.VirtualUidRegistry;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;
import com.warden.controlledsandbox.runtime.status.RuntimeStatusDispatcher;
import com.warden.controlledsandbox.runtime.status.RuntimeStatusLegacyAdapter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/** Central process allocator and route authority. Business/UI code does not own runtime state. */
public final class RuntimeBrokerService extends Service implements RuntimeBrokerOperationHandler {
    private static final int SLOT_COUNT = ProcessSlotContract.ORDINARY_SLOT_COUNT;
    private final Clock clock = new SystemMonotonicClock();
    private final TokenGenerator tokenGenerator = new UuidTokenGenerator();
    private final AuditSink auditSink = new RuntimeAuditSink();
    final SessionRegistry sessions = new SessionRegistry(SLOT_COUNT, tokenGenerator);
    VirtualUidRegistry virtualUids;
    RuntimePackageAuthorityClient packageAuthority;
    private RuntimePermissionCoordinator runtimePermissionCoordinator;
    RuntimeSystemServiceCoordinator systemServiceCoordinator;
    RuntimeComponentRecoveryCoordinator componentRecoveryCoordinator;
    RuntimeOwnershipSweep ownershipSweep;
    final UriGrantRegistry uriGrants = new UriGrantRegistry();
    final BrokerStateStore brokerState = new BrokerStateStore();
    /** System-held PendingIntent recovery may synchronously bind a Guest that calls back to Host. */
    private final ExecutorService pendingIntentRelayExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "sandbox-pending-intent-relay");
        thread.setDaemon(true);
        return thread;
    });
    final RuntimeIsolatedShareManager isolatedShares =
            new RuntimeIsolatedShareManager(this);
    final RuntimeIsolatedProcessCoordinator isolatedProcessCoordinator =
            new RuntimeIsolatedProcessCoordinator(this, brokerState, clock, tokenGenerator,
                    this::validateInput, this::makeSpec, () -> systemServiceCoordinator,
                    this::sessionBundle, isolatedShares::finish);
    final RuntimeReceiverCoordinator receiverCoordinator = new RuntimeReceiverCoordinator(
            sessions, brokerState, clock, tokenGenerator,
            this::prepareGuestInternal,
            this::sessionById,
            (processSlot, request) -> callGuest(processSlot, guest -> guestOperation(
                    guest, RuntimeOperationRequest.INVOKE_COMPONENT, request)));
    final BrokerActivityRuntime activityRuntime = new BrokerActivityRuntime(brokerState);
    final ConcurrentHashMap<String, GuestLaunchObservation> launchObservations =
            new ConcurrentHashMap<>();
    private final RuntimeActivityLaunchCoordinator activityLaunchCoordinator =
            new RuntimeActivityLaunchCoordinator(this);
    final RuntimeServiceCoordinator serviceCoordinator = new RuntimeServiceCoordinator(brokerState,
            (slot, request) -> callGuest(slot, guest -> guestOperation(
                    guest, RuntimeOperationRequest.INVOKE_COMPONENT, request)), clock);
    final BrokerProviderRuntime providerRuntime = new BrokerProviderRuntime();
    final BrokerCursorRuntime cursorRuntime = new BrokerCursorRuntime();
    final BrokerProviderQueryCancellation queryCancellations =
            new BrokerProviderQueryCancellation();
    final BrokerFileRuntime fileRuntime = new BrokerFileRuntime();
    final BrokerObserverRuntime observerRuntime = new BrokerObserverRuntime();
    private final ProviderLifecycleCoordinator providerLifecycle = new ProviderLifecycleCoordinator(
            providerRuntime, cursorRuntime, fileRuntime, observerRuntime,
            queryCancellations, uriGrants);
    final RuntimeProviderResourceCoordinator providerResources =
            new RuntimeProviderResourceCoordinator(providerLifecycle,
                    this::sessionById,
                    session -> brokerState.prepared(processKey(session.packageName(),
                            session.virtualUserId(), session.processName())),
                    (slot, request) -> callGuest(slot, guest -> guestOperation(
                            guest, RuntimeOperationRequest.INVOKE_COMPONENT, request)));
    private final RuntimeBrokerResourceOperationCoordinator resourceOperationCoordinator =
            new RuntimeBrokerResourceOperationCoordinator(this);
    private final RuntimeFrameworkOperationCoordinator frameworkOperationCoordinator =
            new RuntimeFrameworkOperationCoordinator(this);
    private final RuntimeComponentOperationCoordinator componentOperationCoordinator =
            new RuntimeComponentOperationCoordinator(this);
    private final RuntimeStatusDispatcher runtimeStatusDispatcher = new RuntimeStatusDispatcher(
            clock,
            new BrokerRuntimeStatusSource(
                    new CombinedSessionMetricsRepository(sessions, isolatedProcessCoordinator.sessionMetrics()),
                    activityRuntime, combinedServiceMetrics(), providerLifecycle,
                    providerRuntime, receiverCoordinator.lifecycle()),
            this::purgeExpiredResources,
            auditSink);
    RuntimeGuestConnectionPool guestConnections;
    private RuntimeCrossAbiCompanionClient crossAbiCompanion;
    final RuntimeCrossAbiProviderRelay crossAbiProviderRelay =
            new RuntimeCrossAbiProviderRelay();
    private final RuntimeGuestLifecycleCoordinator guestLifecycleCoordinator =
            new RuntimeGuestLifecycleCoordinator(this);
    private final RuntimeGuestRequestValidator guestRequestValidator =
            new RuntimeGuestRequestValidator(this);
    private ServiceMetricsSource combinedServiceMetrics() {
        return () -> Math.addExact(serviceCoordinator.recordCount(),
                isolatedProcessCoordinator.serviceMetrics().recordCount());
    }
    @Override public void onCreate() {
        super.onCreate();
        guestConnections = new RuntimeGuestConnectionPool(this, this::handleGuestDisconnect);
        packageAuthority = new RuntimePackageAuthorityClient(this);
        if (!RuntimePeerPolicy.isCompanionPackage(getPackageName())) {
            crossAbiCompanion = new RuntimeCrossAbiCompanionClient(this,
                    crossAbiProviderRelay::invalidateAll);
        }
        activityRuntime.configureCheckpointStore(
                new File(new File(getFilesDir(), "runtime"), "activity-tasks.checkpoint").toPath());
        File registryFile = new File(new File(getFilesDir(), "runtime"), "virtual-uids.registry");
        virtualUids = new VirtualUidRegistry(registryFile.toPath());
        runtimePermissionCoordinator = new RuntimePermissionCoordinator(
                new RuntimePermissionPackageClient(this), this::permissionSession);
        systemServiceCoordinator = new RuntimeSystemServiceCoordinator(
                new RuntimeVirtualSystemServicePackageClient(this), binder.asBinder());
        componentRecoveryCoordinator = new RuntimeComponentRecoveryCoordinator(
                sessions, clock, activityRuntime, serviceCoordinator, receiverCoordinator,
                providerResources, systemServiceCoordinator);
        ownershipSweep = new RuntimeOwnershipSweep(new OrdinaryOwnershipHooks());
        RuntimePeerPolicy.installIsolatedPeerRegistry(isolatedProcessCoordinator.peerRegistry());
    }
    private final IRuntimeBroker.Stub binder = new IRuntimeBroker.Stub() {
        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            return RuntimeBrokerOperationAdapter.execute(RuntimeBrokerService.this, request);
        }
        @Override public ActivityTaskResult activityTaskOperation(ActivityTaskRequest request) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            try {
                if (request == null) throw new IllegalArgumentException("request is required");
                GuestSession current = findSession(request.sessionId(), request.generation());
                return activityRuntime.taskOperation(current, request);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                return ActivityTaskContractFailure.from(request, error);
            }
        }
        @Override public ActivityResultResult activityResultOperation(ActivityResultRequest request) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            try {
                if (request == null) throw new IllegalArgumentException("request is required");
                GuestSession current = findSession(request.sessionId(), request.generation());
                return activityRuntime.resultOperation(current, request);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                return ActivityResultContractFailure.from(request, error);
            }
        }

        @Override public PackageServiceResult requestRuntimePermission(String sessionId,
                long generation, String permission, int requestCode) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            return runtimePermissionCoordinator.request(sessionId, generation, permission, requestCode);
        }

        @Override public PackageServiceResult reportRuntimePermissionResult(String sessionId,
                long generation, String permission, int requestCode, boolean hostGranted,
                String reason) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            return runtimePermissionCoordinator.report(sessionId, generation, permission, requestCode,
                    hostGranted, reason);
        }

        @Override public RuntimeStatusResult runtimeStatusV2(RuntimeStatusRequest request) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            return runtimeStatusDispatcher.dispatch(request);
        }

        @Override public int virtualUidFor(String packageName, int virtualUserId) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new IllegalArgumentException("packageName is required");
            }
            if (virtualUserId < 0 || virtualUserId > 999) {
                throw new IllegalArgumentException("virtualUserId out of range");
            }
            return uidRegistry().uidFor(packageName.trim(), virtualUserId);
        }

        @Override public void stopGuest(String packageName, int virtualUserId) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            RuntimeBrokerService.this.stopGuestInternal(packageName, virtualUserId);
        }
    };
    final IRuntimeStorage.Stub storageBinder = new IRuntimeStorage.Stub() {
        @Override public Bundle execute(String operation, String sessionId, long generation,
                                         String packageName, int virtualUserId, String name,
                                         boolean deviceProtected, byte[] data) {
            Bundle request = new Bundle();
            request.putString(RuntimeKeys.PACKAGE_NAME, packageName);
            request.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
            request.putString(RuntimeKeys.STORAGE_OPERATION, operation);
            request.putString(RuntimeKeys.STORAGE_NAME, name);
            request.putBoolean(RuntimeKeys.STORAGE_DEVICE_PROTECTED, deviceProtected);
            if (data != null) request.putByteArray(RuntimeKeys.STORAGE_DATA, data);
            return performStorageOperation(request, sessionId, generation);
        }

        @Override public Bundle move(String sessionId, long generation, String packageName,
                                     int virtualUserId, String sourceName,
                                     boolean sourceDeviceProtected, String targetName,
                                     boolean targetDeviceProtected) {
            Bundle request = new Bundle();
            request.putString(RuntimeKeys.PACKAGE_NAME, packageName);
            request.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
            request.putString(RuntimeKeys.STORAGE_NAME, sourceName);
            request.putString(RuntimeKeys.STORAGE_TARGET_NAME, targetName);
            request.putBoolean(RuntimeKeys.STORAGE_SOURCE_DEVICE_PROTECTED,
                    sourceDeviceProtected);
            request.putBoolean(RuntimeKeys.STORAGE_TARGET_DEVICE_PROTECTED,
                    targetDeviceProtected);
            return performStorageMove(request, sessionId, generation);
        }
    };

    @Override public Bundle prepareGuest(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return RuntimeBrokerService.this.prepareGuestInternal(request);
    }

    @Override public Bundle launchActivity(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        IsolatedProcessRoutePolicy.rejectOrdinaryRoute(request);
        return activityLaunchCoordinator.launch(request);
    }

    @Override public Bundle invokeComponent(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return componentOperationCoordinator.invoke(request);
    }

    @Override public Bundle grantUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return resourceOperationCoordinator.grantUriPermission(request);
    }

    @Override public Bundle revokeUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return resourceOperationCoordinator.revokeUriPermission(request);
    }

    @Override public Bundle checkUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return resourceOperationCoordinator.checkUriPermission(request);
    }

    @Override public Bundle openPackageResources(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return resourceOperationCoordinator.openPackageResources(request);
    }

    @Override public Bundle consumeRoute(String token, String sessionId, long generation) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return resourceOperationCoordinator.consumeRoute(token, sessionId, generation);
    }

    @Override public Bundle activityEvent(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return resourceOperationCoordinator.activityEvent(request);
    }

    @Override public Bundle storageOperation(Bundle request, String sessionId, long generation) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return resourceOperationCoordinator.storageOperation(request, sessionId, generation);
    }

    private Bundle performStorageOperation(Bundle request, String sessionId, long generation) {
        return resourceOperationCoordinator.performStorageOperation(request, sessionId, generation);
    }

    private Bundle performStorageMove(Bundle request, String sessionId, long generation) {
        return resourceOperationCoordinator.performStorageMove(request, sessionId, generation);
    }

    @Override public Bundle sessionStatus(String packageName, int virtualUserId) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        GuestSession session = sessions.get(packageName, virtualUserId,
                applicationProcessName(packageName, virtualUserId));
        return session == null ? failure("SESSION_NOT_FOUND", "No active session") : sessionBundle(session, session.state().name());
    }

    /**
     * Session status is a package-level compatibility API, but a manifest may move the
     * Application and every component without an explicit process into a named process.  Do
     * not silently look only at the package-name process: that makes a valid custom application
     * process appear dead to callers after the lease was allocated under its declared name.
     */
    private String applicationProcessName(String packageName, int virtualUserId) {
        if (packageAuthority != null) {
            try {
                VirtualPackageStateSnapshot state = packageAuthority.virtualPackageState(
                        packageName, virtualUserId);
                ApplicationInfo application = state == null ? null : state.applicationInfo();
                String declared = normalizeProcessName(packageName,
                        application == null ? "" : application.processName);
                if (!declared.isEmpty()) return declared;
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                android.util.Log.w("CS_SESSION_STATUS", "application process lookup failed package="
                        + packageName + " user=" + virtualUserId, error);
            }
        }
        return packageName;
    }

    @Override public Bundle runtimeStatus() {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        RuntimeStatusRequest legacyRequest = new RuntimeStatusRequest(
                RuntimeProtocol.CURRENT, "legacy-runtime-status");
        Bundle legacy = RuntimeStatusLegacyAdapter.toBundle(
                runtimeStatusDispatcher.dispatch(legacyRequest));
        isolatedProcessCoordinator.addLegacyStatus(legacy);
        return legacy;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null
                && RuntimePendingIntentRelayReceiver.ACTION.equals(intent.getAction())) {
            String tokenId = intent.getStringExtra(RuntimeKeys.PENDING_INTENT_TOKEN_ID);
            pendingIntentRelayExecutor.execute(() -> dispatchSystemHeldAsync(tokenId));
        }
        return START_STICKY;
    }

    private void dispatchSystemHeldAsync(String tokenId) {
        try {
            android.util.Log.i("CS_PENDING_INTENT", "SYSTEM_HOLDER_RELAY_DISPATCH_ASYNC token=" + tokenId);
            frameworkOperationCoordinator.dispatchSystemHeld(tokenId);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_PENDING_INTENT", "SYSTEM_HOLDER_RELAY_FAILED token=" + tokenId, error);
        }
    }
    synchronized Bundle prepareGuestInternal(Bundle request) {
        return guestLifecycleCoordinator.prepareGuest(request);
    }

    static Bundle guestOperation(IGuestProcess guest, String operation, Bundle payload)
            throws Exception {
        return RuntimeOperationTransport.toLegacyBundle(
                RuntimeOperationTransport.execute(guest, operation, payload));
    }

    /**
     * Commits lifecycle callbacks that were delivered by the target process's ActivityThread
     * Service transport.  Framework-owned Services must update the same Broker state machine as
     * the legacy component route, but must never be sent back through GuestComponentRuntime (that
     * would construct a second Service instance beside ActivityThread.mServices).
     */
    Bundle recordFrameworkServiceEvent(GuestSession session, Bundle request) {
        return frameworkOperationCoordinator.recordFrameworkServiceEvent(session, request);
    }

    Bundle createPendingIntentSender(Bundle request, String requestedPackage,
                                     int requestedUser) throws Exception {
        return frameworkOperationCoordinator.createPendingIntentSender(request, requestedPackage, requestedUser);
    }

    static void restoreTargetSessionIdentity(Bundle target, Bundle prepared,
                                             GuestSession session) {
        RuntimeFrameworkOperationCoordinator.restoreTargetSessionIdentity(target, prepared, session);
    }


    private void stopGuestInternal(String packageName, int userId) {
        guestLifecycleCoordinator.stopGuest(packageName, userId);
    }

    private Bundle makeSpec(Bundle input, GuestSession session) throws Exception {
        return guestLifecycleCoordinator.makeSpec(input, session);
    }

    /** Returns the package selected by the virtual resolver, if this request has one. */
    static String targetPackageForRequest(Bundle request, String callerPackage) {
        if (request == null) return callerPackage == null ? "" : callerPackage;
        String componentPackage = request.getString(RuntimeKeys.INTENT_COMPONENT_PACKAGE, "");
        if (componentPackage != null && !componentPackage.trim().isEmpty()) {
            return componentPackage.trim();
        }
        String targetPackage = request.getString(RuntimeKeys.TARGET_PACKAGE_NAME, "");
        return targetPackage == null || targetPackage.trim().isEmpty()
                ? (callerPackage == null ? "" : callerPackage)
                : targetPackage.trim();
    }

    GuestSession callerSession(Bundle request, String callerPackage) {
        if (request == null) throw new SecurityException("CROSS_PACKAGE_CALLER_REQUEST_MISSING");
        String sessionId = request.getString(RuntimeKeys.CALLER_SESSION_ID,
                request.getString(RuntimeKeys.SESSION_ID, ""));
        long generation = request.getLong(RuntimeKeys.CALLER_GENERATION,
                request.getLong(RuntimeKeys.GENERATION, -1L));
        GuestSession caller = findSession(sessionId, generation);
        int requestedUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (!caller.packageName().equals(callerPackage)
                || (requestedUser >= 0 && caller.virtualUserId() != requestedUser)) {
            throw new SecurityException("CROSS_PACKAGE_CALLER_IDENTITY_MISMATCH");
        }
        return caller;
    }

    GuestSession requireCurrentGuestSession(Bundle request, String packageName, int userId) {
        GuestSession caller = callerSession(request, packageName);
        String requestedProcess = normalizeProcessName(packageName,
                request.getString(RuntimeKeys.PROCESS_NAME, ""));
        if (!caller.processName().equals(requestedProcess)) {
            throw new SecurityException("GUEST_SESSION_PROCESS_MISMATCH");
        }
        if (!RuntimePreparingSessionPolicy.isOperational(caller, request, false)) {
            throw new SecurityException("GUEST_SESSION_NOT_READY");
        }
        if (caller.virtualUserId() != userId) {
            throw new SecurityException("GUEST_SESSION_USER_MISMATCH");
        }
        return caller;
    }

    void requireInstalledVirtualPackage(String packageName, int virtualUserId)
            throws Exception {
        if (packageAuthority == null) {
            throw new IllegalStateException("RUNTIME_PACKAGE_AUTHORITY_NOT_INITIALIZED");
        }
        PackageRecordSnapshot record = packageAuthority.findRecord(packageName);
        if (record == null || !packageName.equals(record.packageName())) {
            throw new SecurityException("URI_GRANT_TARGET_NOT_INSTALLED:" + packageName);
        }
        VirtualPackageStateSnapshot state = packageAuthority.virtualPackageState(
                packageName, virtualUserId);
        if (state == null || !packageName.equals(state.packageName())
                || state.virtualUserId() != virtualUserId) {
            throw new SecurityException("URI_GRANT_TARGET_INSTANCE_NOT_INSTALLED:" + packageName);
        }
    }

    static String providerAuthorityFromUri(String uri) {
        if (uri == null || !uri.startsWith("content://")) {
            throw new IllegalArgumentException("Provider URI must use content scheme");
        }
        int start = "content://".length();
        int end = uri.length();
        for (int index = start; index < uri.length(); index++) {
            char value = uri.charAt(index);
            if (value == '/' || value == '?' || value == '#') {
                end = index;
                break;
            }
        }
        String authority = uri.substring(start, end).trim();
        if (authority.isEmpty()) throw new IllegalArgumentException("Provider URI authority is required");
        return authority;
    }

    /**
     * Rebuilds a target-package runtime request from package-authority state. The incoming Guest
     * bundle supplies only the caller intent and caller identity; executable paths, split
     * artifacts and native policy are read again from the trusted package authority.
     */
    Bundle prepareForeignTargetRequest(Bundle callerRequest, GuestSession caller,
                                               String targetPackage,
                                               VirtualPackageMetadata.Type type,
                                               boolean ensureReady) throws Exception {
        if (packageAuthority == null) throw new IllegalStateException(
                "RUNTIME_PACKAGE_AUTHORITY_NOT_INITIALIZED");
        PackageRecordSnapshot record = packageAuthority.findRecord(targetPackage);
        if (record == null || !targetPackage.equals(record.packageName())) {
            throw new SecurityException("CROSS_PACKAGE_TARGET_NOT_INSTALLED:" + targetPackage);
        }
        VirtualPackageStateSnapshot targetState = packageAuthority.virtualPackageState(
                targetPackage, caller.virtualUserId());
        if (targetState == null || !targetPackage.equals(targetState.packageName())
                || targetState.virtualUserId() != caller.virtualUserId()) {
            throw new SecurityException("CROSS_PACKAGE_TARGET_STATE_MISMATCH:" + targetPackage);
        }
        String component = callerRequest.getString(RuntimeKeys.COMPONENT_CLASS, "").trim();
        if (component.isEmpty()) throw new IllegalArgumentException(
                "CROSS_PACKAGE_COMPONENT_MISSING");
        VirtualPackageUniverse universe = crossPackageUniverse(callerRequest, caller,
                targetPackage, targetState);
        requireAccessibleCrossPackageComponent(universe, caller, targetPackage, component, type,
                callerPermissions(callerRequest));
        VirtualPackageMetadata targetMetadata = universe.packageMetadata(targetPackage);
        if (targetMetadata == null) throw new SecurityException(
                "CROSS_PACKAGE_TARGET_METADATA_MISSING:" + targetPackage);
        VirtualPackageMetadata.Component targetComponent = targetMetadata.component(component, type);
        if (targetComponent == null) throw new SecurityException(
                "CROSS_PACKAGE_COMPONENT_NOT_DECLARED:" + component);
        String declaredProcess = normalizeProcessName(targetPackage,
                targetComponent.processName());
        String requestedProcess = normalizeProcessName(targetPackage,
                callerRequest.getString(RuntimeKeys.PROCESS_NAME, ""));
        if (requestedProcess.isEmpty()) requestedProcess = declaredProcess;
        if (!declaredProcess.equals(requestedProcess)) {
            throw new SecurityException("CROSS_PACKAGE_PROCESS_MISMATCH:" + component);
        }

        Bundle targetRequest = new Bundle(callerRequest);
        targetRequest.putString(RuntimeKeys.PACKAGE_NAME, targetPackage);
        targetRequest.putInt(RuntimeKeys.VIRTUAL_USER_ID, caller.virtualUserId());
        targetRequest.putString(RuntimeKeys.PROCESS_NAME, requestedProcess);
        targetRequest.putString(RuntimeKeys.APK_PATH, record.apkPath());
        targetRequest.putString(RuntimeKeys.APK_SHA256, record.apkSha256());
        targetRequest.putString(RuntimeKeys.BASE_APK_SHA256, record.baseApkSha256());
        targetRequest.putLong(RuntimeKeys.APK_VERSION_CODE, record.versionCode());
        targetRequest.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, record.nativeLibraryDir());
        targetRequest.putString(RuntimeKeys.NATIVE_ABI, record.nativeAbi());
        targetRequest.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, record.containsNativeCode());
        targetRequest.putString(RuntimeKeys.NATIVE_GUEST_TRUST, record.nativeGuestTrust());
        targetRequest.putString(RuntimeKeys.NATIVE_EXECUTION_MODE,
                record.nativeExecutionMode());
        targetRequest.putString(RuntimeKeys.APPLICATION_CLASS, record.applicationClass());
        targetRequest.putString(RuntimeKeys.COMPONENT_CLASS, component);
        targetRequest.putString(RuntimeKeys.TARGET_PACKAGE_NAME, targetPackage);
        targetRequest.putString(RuntimeKeys.INTENT_COMPONENT_PACKAGE, targetPackage);
        targetRequest.putParcelable(RuntimeKeys.PACKAGE_STATE, targetState);
        RuntimeGuestArtifactProjection.put(targetRequest, record);
        targetRequest.putStringArrayList(RuntimeKeys.PERMISSIONS,
                RuntimeGuestArtifactProjection.csv(record.permissions()));
        targetRequest.putString(RuntimeKeys.CALLER_PACKAGE_NAME, caller.packageName());
        targetRequest.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, caller.virtualUserId());
        targetRequest.putString(RuntimeKeys.CALLER_SESSION_ID, caller.sessionId());
        targetRequest.putLong(RuntimeKeys.CALLER_GENERATION, caller.generation());
        targetRequest.putParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE,
                targetPackageUniverse(callerRequest, caller, targetPackage, targetState,
                        record));
        if (ensureReady) {
            Bundle prepared = prepareGuestInternal(targetRequest);
            if (!isPrepared(prepared)) {
                throw new IllegalStateException(prepared.getString(RuntimeKeys.ERROR_TYPE,
                        "CROSS_PACKAGE_TARGET_PREPARE_FAILED") + ":"
                        + prepared.getString(RuntimeKeys.ERROR_MESSAGE, ""));
            }
        }
        android.util.Log.i("CS_CROSS_PACKAGE_ROUTE", "caller=" + caller.packageName()
                + " target=" + targetPackage + " component=" + component
                + " process=" + requestedProcess + " prepared=" + ensureReady);
        return targetRequest;
    }

    boolean targetRequiresCompanion(String targetPackage, int virtualUserId) throws Exception {
        if (crossAbiCompanion == null || packageAuthority == null) return false;
        PackageRecordSnapshot record = packageAuthority.findRecord(targetPackage);
        if (record == null || !targetPackage.equals(record.packageName())) {
            throw new SecurityException("CROSS_PACKAGE_TARGET_NOT_INSTALLED:" + targetPackage);
        }
        return requiresCompanionAbi(record.nativeAbi());
    }

    Bundle routeForeignOperation(Bundle callerRequest, GuestSession caller, String targetPackage,
                                 VirtualPackageMetadata.Type type, String operation)
            throws Exception {
        if (crossAbiCompanion == null) {
            throw new IllegalStateException("CROSS_ABI_COMPANION_ROUTE_UNAVAILABLE");
        }
        PackageRecordSnapshot record = packageAuthority.findRecord(targetPackage);
        if (record == null || !targetPackage.equals(record.packageName())) {
            throw new SecurityException("CROSS_PACKAGE_TARGET_NOT_INSTALLED:" + targetPackage);
        }
        if (!requiresCompanionAbi(record.nativeAbi())) {
            throw new IllegalStateException("CROSS_ABI_TARGET_NOT_COMPANION:" + targetPackage);
        }
        Bundle target = prepareForeignTargetRequest(callerRequest, caller, targetPackage, type,
                false);
        android.util.Log.i("CS_CROSS_ABI_ROUTE", "caller=" + caller.packageName()
                + " target=" + targetPackage + " operation=" + operation
                + " abi=" + record.nativeAbi() + " delegated=true");
        return crossAbiCompanion.execute(record, caller.virtualUserId(), operation, target);
    }

    /**
     * Executes a foreign Provider operation in the ABI-owned Companion while keeping Host-side
     * Package visibility, URI grants and permission policy authoritative.  Stream-like resources
     * are translated through {@link RuntimeCrossAbiProviderRelay}; ordinary result payloads remain
     * wire-compatible with the existing Guest ContentResolver facade.
     */
    Bundle routeForeignProviderOperation(Bundle callerRequest, GuestSession caller,
                                         String targetPackage, String operation) throws Exception {
        if (crossAbiCompanion == null) {
            throw new IllegalStateException("CROSS_ABI_COMPANION_ROUTE_UNAVAILABLE");
        }
        PackageRecordSnapshot record = packageAuthority.findRecord(targetPackage);
        if (record == null || !targetPackage.equals(record.packageName())) {
            throw new SecurityException("CROSS_PACKAGE_TARGET_NOT_INSTALLED:" + targetPackage);
        }
        if (!requiresCompanionAbi(record.nativeAbi())) {
            throw new IllegalStateException("CROSS_ABI_TARGET_NOT_COMPANION:" + targetPackage);
        }

        Bundle target = prepareForeignTargetRequest(callerRequest, caller, targetPackage,
                VirtualPackageMetadata.Type.PROVIDER, false);
        RuntimeCrossAbiProviderRelay.RemoteRequest existing = null;
        if (isCrossAbiProviderResourceOperation(operation)) {
            existing = crossAbiProviderRelay.prepareExisting(callerRequest, operation);
            target = new Bundle(existing.request());
        }
        target.putBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, true);

        BrokerProviderRuntime.OperationRoute route = null;
        UriGrantRegistry.Authorization authorization = null;
        try {
            if (ComponentOperations.isProviderTransactionOperation(operation)) {
                providerRuntime.ensureRemotePrepared(target, targetPackage, caller.virtualUserId(),
                        target.getString(RuntimeKeys.PROCESS_NAME, record.packageName()));
                String callerInstance = ownerKey(caller.packageName(), caller.virtualUserId());
                String targetInstance = ownerKey(targetPackage, caller.virtualUserId());
                authorization = beginUriGrantAuthorization(target, callerInstance, targetInstance);
                final Bundle routedTarget = target;
                route = providerRuntime.routeOperation(target, operation, callerInstance,
                        caller.virtualUserId(), targetInstance,
                        authorization == null ? null : authorization::allows,
                        permission -> ProviderDeclaredPermissionAuthorizer.allows(routedTarget,
                                caller.packageName(), caller.virtualUserId(), permission,
                                this::sessionById, brokerState), now());
                if ("URI_GRANT".equals(route.permissionBasis())) {
                    if (authorization == null) throw new SecurityException(
                            "URI_GRANT_CALLER_SESSION_REQUIRED");
                    authorization.commit(now());
                }
                target.putString(RuntimeKeys.CROSS_ABI_PROVIDER_PERMISSION_BASIS,
                        route.permissionBasis());
            } else if (ComponentOperations.PREPARE_PROVIDER.equals(operation)
                    || ComponentOperations.PROVIDER_NOTIFY_CHANGE.equals(operation)) {
                providerRuntime.ensureRemotePrepared(target, targetPackage, caller.virtualUserId(),
                        target.getString(RuntimeKeys.PROCESS_NAME, record.packageName()));
            }

            android.util.Log.i("CS_CROSS_ABI_ROUTE", "caller=" + caller.packageName()
                    + " target=" + targetPackage + " operation=" + operation
                    + " abi=" + record.nativeAbi() + " provider=true delegated=true");
            Bundle result = crossAbiCompanion.execute(record, caller.virtualUserId(),
                    RuntimeOperationRequest.INVOKE_COMPONENT, target);
            if (route != null) providerRuntime.completeOperation(route, result, now());
            return exposeCrossAbiProviderResult(operation, result, callerRequest, target);
        } catch (Exception error) {
            if (route != null) providerRuntime.failOperation(route, error, now());
            throw error;
        } catch (Error fatal) {
            if (route != null) providerRuntime.failOperation(route, fatal, now());
            throw fatal;
        }
    }

    Bundle routeForeignProviderObserverUnregister(Bundle callerRequest, GuestSession caller)
            throws Exception {
        RuntimeCrossAbiProviderRelay.RemoteRequest remote =
                crossAbiProviderRelay.prepareObserverUnregister(callerRequest);
        PackageRecordSnapshot record = packageAuthority.findRecord(remote.targetPackage());
        if (record == null || !remote.targetPackage().equals(record.packageName())) {
            throw new SecurityException("CROSS_PACKAGE_TARGET_NOT_INSTALLED:" + remote.targetPackage());
        }
        Bundle result = crossAbiCompanion.execute(record, remote.virtualUserId(),
                RuntimeOperationRequest.INVOKE_COMPONENT, remote.request());
        return crossAbiProviderRelay.finishObserverUnregister(result, callerRequest);
    }

    boolean hasCrossAbiProviderObserver(String observerId) {
        return crossAbiProviderRelay.hasObserver(observerId);
    }

    private Bundle exposeCrossAbiProviderResult(String operation, Bundle result, Bundle original,
                                                Bundle targetRequest) {
        if (result == null || "FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
            return result;
        }
        if (ComponentOperations.PROVIDER_QUERY.equals(operation)) {
            return crossAbiProviderRelay.exposeCursor(result, targetRequest);
        }
        if (ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)) {
            return crossAbiProviderRelay.preserveCursorToken(result, original);
        }
        if (ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation)) {
            return crossAbiProviderRelay.finishCursor(result, original);
        }
        if (ComponentOperations.isProviderFileOpenOperation(operation)) {
            return crossAbiProviderRelay.exposeFile(result, targetRequest);
        }
        if (ComponentOperations.PROVIDER_FILE_CLOSE.equals(operation)) {
            return crossAbiProviderRelay.finishFile(result, original);
        }
        if (ComponentOperations.PROVIDER_OBSERVER_REGISTER.equals(operation)) {
            return crossAbiProviderRelay.exposeObserver(result, targetRequest);
        }
        return result;
    }

    private static boolean isCrossAbiProviderResourceOperation(String operation) {
        return ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation)
                || ComponentOperations.PROVIDER_FILE_CLOSE.equals(operation);
    }

    private static boolean requiresCompanionAbi(String abi) {
        return "x86".equals(abi) || "armeabi-v7a".equals(abi);
    }

    VirtualPackageUniverse crossPackageUniverse(Bundle request, GuestSession caller,
                                                         String targetPackage,
                                                         VirtualPackageStateSnapshot targetState) {
        VirtualPackageStateSnapshot callerState = packageState(request);
        if (callerState == null || !caller.packageName().equals(callerState.packageName())) {
            throw new SecurityException("CROSS_PACKAGE_CALLER_PACKAGE_STATE_MISSING");
        }
        ArrayList<VirtualPackageMetadata> views = new ArrayList<>();
        views.add(GuestPackageMetadataMapper.fromSnapshot(callerState,
                metadataApplicationInfo(callerState, caller.packageName(),
                        uidRegistry().uidFor(caller.packageName(), caller.virtualUserId()))));
        request.setClassLoader(VirtualPackageProjectionSnapshot.class.getClassLoader());
        ArrayList<VirtualPackageProjectionSnapshot> projections = request.getParcelableArrayList(
                RuntimeKeys.PACKAGE_UNIVERSE);
        if (projections != null) {
            for (VirtualPackageProjectionSnapshot projection : projections) {
                if (projection == null) continue;
                String packageName = projection.packageState().packageName();
                if (caller.packageName().equals(packageName) || targetPackage.equals(packageName)) {
                    continue;
                }
                views.add(GuestPackageMetadataMapper.fromProjection(projection));
            }
        }
        views.add(GuestPackageMetadataMapper.fromSnapshot(targetState,
                metadataApplicationInfo(targetState, targetPackage,
                        uidRegistry().uidFor(targetPackage, caller.virtualUserId()))));
        return new VirtualPackageUniverse(views);
    }

    private ArrayList<VirtualPackageProjectionSnapshot> targetPackageUniverse(
            Bundle callerRequest, GuestSession caller, String targetPackage,
            VirtualPackageStateSnapshot targetState, PackageRecordSnapshot targetRecord) {
        ArrayList<VirtualPackageProjectionSnapshot> result = new ArrayList<>();
        callerRequest.setClassLoader(VirtualPackageProjectionSnapshot.class.getClassLoader());
        ArrayList<VirtualPackageProjectionSnapshot> projections = callerRequest
                .getParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE);
        if (projections != null) {
            for (VirtualPackageProjectionSnapshot projection : projections) {
                if (projection == null) continue;
                String packageName = projection.packageState().packageName();
                if (targetPackage.equals(packageName) || caller.packageName().equals(packageName)) {
                    continue;
                }
                result.add(projection);
            }
        }
        VirtualPackageStateSnapshot callerState = packageState(callerRequest);
        if (callerState != null) {
            result.add(new VirtualPackageProjectionSnapshot(callerState,
                    callerRequest.getString(RuntimeKeys.APK_PATH, ""),
                    callerRequest.getString(RuntimeKeys.NATIVE_LIBRARY_DIR, ""),
                    uidRegistry().uidFor(caller.packageName(), caller.virtualUserId()),
                    callerState.applicationInfo()));
        }
        // Keep a fresh target projection out of the target's own view. The argument documents
        // that the executable target was authority-validated here and prevents a future caller
        // from accidentally reusing a stale target projection as its primary package.
        if (targetState == null || targetRecord == null) throw new IllegalStateException(
                "CROSS_PACKAGE_TARGET_PROJECTION_MISSING");
        return result;
    }

    private void requireAccessibleCrossPackageComponent(VirtualPackageUniverse universe,
                                                         GuestSession caller,
                                                         String targetPackage, String component,
                                                         VirtualPackageMetadata.Type type,
                                                         Set<String> permissions) {
        if (!universe.isVisibleTo(caller.packageName(), targetPackage)) {
            throw new SecurityException("CROSS_PACKAGE_NOT_VISIBLE:" + targetPackage);
        }
        Intent explicit = new Intent().setComponent(new ComponentName(targetPackage, component));
        if (universe.query(caller.packageName(), explicit, type, 0L, permissions).isEmpty()) {
            throw new SecurityException("CROSS_PACKAGE_COMPONENT_NOT_EXPORTED_OR_PERMISSION:" +
                    targetPackage + "/" + component);
        }
    }

    static Set<String> callerPermissions(Bundle request) {
        Set<String> result = new HashSet<>();
        VirtualPackageStateSnapshot state = packageState(request);
        if (state != null) {
            for (com.warden.controlledsandbox.contract.VirtualPermissionSnapshot permission
                    : state.permissions()) {
                if (permission.effectiveGranted()) result.add(permission.name());
            }
        }
        return result;
    }

    static VirtualPackageStateSnapshot packageState(Bundle request) {
        if (request == null) return null;
        request.setClassLoader(VirtualPackageStateSnapshot.class.getClassLoader());
        return request.getParcelable(RuntimeKeys.PACKAGE_STATE);
    }

    private static ApplicationInfo metadataApplicationInfo(VirtualPackageStateSnapshot state,
                                                           String packageName, int virtualUid) {
        ApplicationInfo info = state == null ? null : state.applicationInfo();
        if (info == null) info = new ApplicationInfo();
        info.packageName = packageName;
        info.uid = virtualUid;
        return info;
    }

    static String normalizeProcessName(String packageName, String processName) {
        String value = processName == null ? "" : processName.trim();
        if (value.isEmpty()) return "";
        return value.startsWith(":") ? packageName + value : value;
    }

    void validateInput(Bundle input) throws Exception {
        guestRequestValidator.validate(input);
    }

    void validateDeclaredProcess(String packageName, int virtualUserId,
                                 String requestedProcess) throws Exception {
        guestRequestValidator.validateDeclaredProcess(packageName, virtualUserId, requestedProcess);
    }

    Bundle unregisterProviderObserver(Bundle request, String requestedPackage, int requestedUser) {
        if (request.getBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, false)) {
            String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, "");
            int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, -1);
            String callerSessionId = required(request, RuntimeKeys.CALLER_SESSION_ID);
            long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION, -1L);
            ProviderObserverRegistry.Entry removed = observerRuntime.unregisterRelayed(
                    required(request, RuntimeKeys.OBSERVER_ID), ownerKey(callerPackage, callerUser),
                    callerSessionId, callerGeneration);
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "PROVIDER_OBSERVER_UNREGISTERED");
            out.putString(RuntimeKeys.OBSERVER_ID, removed.id());
            return out;
        }
        ObserverCaller caller = requireObserverCaller(request, requestedPackage, requestedUser);
        ProviderObserverRegistry.Entry removed = observerRuntime.unregister(
                required(request, RuntimeKeys.OBSERVER_ID), caller.instanceId, caller.session.sessionId(),
                caller.session.generation());
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "PROVIDER_OBSERVER_UNREGISTERED");
        out.putString(RuntimeKeys.OBSERVER_ID, removed.id());
        return out;
    }

    Bundle notifyProviderObservers(Bundle request, String requestedPackage, int requestedUser) {
        String process = processName(request, requestedPackage);
        GuestSession owner = sessions.get(requestedPackage, requestedUser, process);
        if (owner == null || (owner.state() != SessionState.READY && owner.state() != SessionState.ACTIVE)) {
            throw new SecurityException("PROVIDER_NOTIFY_OWNER_SESSION_NOT_READY");
        }
        ProviderAuthorityRegistry.Entry provider = providerRuntime.requireOwned(request, owner);
        String uri = required(request, RuntimeKeys.URI);
        int flags = request.getInt(RuntimeKeys.OBSERVER_CHANGE_FLAGS, 0);
        BrokerObserverRuntime.NotifyResult notification = observerRuntime.notifyChange(requestedUser,
                provider.authority(), uri, ownerKey(owner.packageName(), owner.virtualUserId()),
                owner.sessionId(), owner.generation(), flags);
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, notification.failures().isEmpty()
                ? "PROVIDER_CHANGE_NOTIFIED" : "PROVIDER_CHANGE_PARTIAL");
        out.putString(ComponentOperations.AUTHORITY, provider.authority());
        out.putString(RuntimeKeys.URI, uri);
        out.putInt(RuntimeKeys.OBSERVER_MATCHED_COUNT, notification.matched());
        out.putInt(RuntimeKeys.OBSERVER_DELIVERED_COUNT, notification.delivered());
        out.putStringArrayList(RuntimeKeys.OBSERVER_FAILURES,
                new ArrayList<>(notification.failures()));
        return out;
    }

    private ObserverCaller requireObserverCaller(Bundle request, String requestedPackage, int requestedUser) {
        String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, requestedPackage);
        int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, requestedUser);
        String sessionId = required(request, RuntimeKeys.CALLER_SESSION_ID);
        long generation = request.getLong(RuntimeKeys.CALLER_GENERATION, -1);
        GuestSession caller = sessionById(sessionId, generation);
        if (caller == null || (caller.state() != SessionState.READY && caller.state() != SessionState.ACTIVE)) {
            throw new SecurityException("PROVIDER_OBSERVER_CALLER_SESSION_NOT_READY");
        }
        String instance = ownerKey(callerPackage, callerUser);
        if (!caller.packageName().equals(callerPackage) || caller.virtualUserId() != callerUser
                || !ownerKey(caller.packageName(), caller.virtualUserId()).equals(instance)) {
            throw new SecurityException("PROVIDER_OBSERVER_CALLER_IDENTITY_MISMATCH");
        }
        return new ObserverCaller(instance, caller);
    }

    private RuntimePermissionCoordinator.PermissionSession permissionSession(
            String sessionId, long generation) {
        GuestSession session = sessionById(sessionId, generation);
        if (session == null) return null;
        boolean ready = session.state() == SessionState.READY || session.state() == SessionState.ACTIVE;
        return new RuntimePermissionCoordinator.PermissionSession(session.packageName(),
                session.virtualUserId(), session.sessionId(), session.generation(), ready);
    }

    GuestSession sessionById(String sessionId, long generation) {
        for (GuestSession candidate : sessions.snapshot()) {
            if (candidate.sessionId().equals(sessionId) && candidate.generation() == generation) return candidate;
        }
        return null;
    }

    /** Resolve both session registries without weakening the generation fence. */
    GuestSession findStorageSession(String sessionId, long generation) {
        for (GuestSession candidate : sessions.snapshot()) {
            if (candidate.sessionId().equals(sessionId) && candidate.generation() == generation
                    && candidate.state() != SessionState.STOPPED
                    && candidate.state() != SessionState.FAILED) {
                return candidate;
            }
        }
        GuestSession isolated = isolatedProcessCoordinator.findStorageSession(sessionId, generation);
        if (isolated != null) return isolated;
        throw new SecurityException("SESSION_OR_GENERATION_MISMATCH");
    }

    GuestSession latestSessionById(String sessionId) {
        GuestSession latest = null;
        for (GuestSession candidate : sessions.snapshot()) {
            if (!candidate.sessionId().equals(sessionId)
                    || candidate.state() == SessionState.STOPPED
                    || candidate.state() == SessionState.FAILED) continue;
            if (latest == null || candidate.generation() > latest.generation()) latest = candidate;
        }
        return latest;
    }
    static void requireProviderTargetSession(GuestSession session,
            ProviderAuthorityRegistry.Entry target, Bundle request) {
        if (!RuntimePreparingSessionPolicy.isOperational(session, request, false)) {
            throw new IllegalStateException("PROVIDER_TARGET_SESSION_NOT_READY");
        }
        if (!ownerKey(session.packageName(), session.virtualUserId()).equals(target.instanceId())
                || !session.processName().equals(target.processName())) {
            throw new SecurityException("PROVIDER_AUTHORITY_SESSION_MISMATCH");
        }
    }

    static void requireCursorTargetSession(GuestSession session,
            BrokerCursorRuntime.Lease lease, Bundle request) {
        if (!RuntimePreparingSessionPolicy.isOperational(session, request, false)) {
            throw new IllegalStateException("CURSOR_TARGET_SESSION_NOT_READY");
        }
        if (!session.packageName().equals(lease.targetPackage())
                || session.virtualUserId() != lease.targetVirtualUserId()
                || !session.processName().equals(lease.targetProcessName())
                || !ownerKey(session.packageName(), session.virtualUserId()).equals(lease.targetInstance())) {
            throw new SecurityException("CURSOR_TARGET_IDENTITY_MISMATCH");
        }
    }

    UriGrantRegistry.Authorization beginUriGrantAuthorization(Bundle request, String callerInstance,
                                                                         String targetInstance) {
        if (callerInstance.equals(targetInstance)) return null;
        String callerPackage = required(request, RuntimeKeys.CALLER_PACKAGE_NAME);
        int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, -1);
        String callerSessionId = required(request, RuntimeKeys.CALLER_SESSION_ID);
        long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION, -1);
        GuestSession caller = sessionById(callerSessionId, callerGeneration);
        if (caller == null || (caller.state() != SessionState.READY && caller.state() != SessionState.ACTIVE)) {
            throw new SecurityException("URI_GRANT_CALLER_SESSION_NOT_READY");
        }
        if (!caller.packageName().equals(callerPackage) || caller.virtualUserId() != callerUser
                || !ownerKey(callerPackage, callerUser).equals(callerInstance)) {
            throw new SecurityException("URI_GRANT_CALLER_IDENTITY_MISMATCH");
        }
        return uriGrants.beginAuthorization(callerInstance, callerSessionId, callerGeneration, callerUser, now());
    }

    ProviderAccess providerAccess(BrokerProviderRuntime.OperationRoute route, Bundle request,
                                           GuestSession targetSession) {
        String callerSessionId = request.getString(RuntimeKeys.CALLER_SESSION_ID, "");
        long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION, -1);
        if (request.getBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, false)) {
            String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, "");
            int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, -1);
            if (!ownerKey(callerPackage, callerUser).equals(route.callerInstance())) {
                throw new SecurityException("PROVIDER_RELAY_CALLER_IDENTITY_MISMATCH");
            }
            return new ProviderAccess(route.callerInstance(), callerSessionId, callerGeneration,
                    route.targetInstance(), route.uri(), route.flags());
        }
        GuestSession caller;
        if (callerSessionId.trim().isEmpty() && route.callerInstance().equals(route.targetInstance())) {
            caller = targetSession;
        } else {
            caller = sessionById(callerSessionId, callerGeneration);
            if (!RuntimePreparingSessionPolicy.isOperational(caller, request, true)) {
                throw new SecurityException("PROVIDER_CALLER_SESSION_NOT_READY");
            }
            if (!ownerKey(caller.packageName(), caller.virtualUserId()).equals(route.callerInstance())) {
                throw new SecurityException("PROVIDER_CALLER_SESSION_IDENTITY_MISMATCH");
            }
        }
        return new ProviderAccess(route.callerInstance(), caller.sessionId(), caller.generation(),
                route.targetInstance(), route.uri(), route.flags());
    }

    void validateCursorRequestIdentity(Bundle request, String requestedPackage, int requestedUser,
                                               BrokerCursorRuntime.Lease lease) {
        if (!lease.targetPackage().equals(requestedPackage) || lease.targetVirtualUserId() != requestedUser) {
            throw new SecurityException("CURSOR_TARGET_REQUEST_MISMATCH");
        }
        String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, requestedPackage);
        int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, requestedUser);
        if (!ownerKey(callerPackage, callerUser).equals(lease.callerInstance())) {
            throw new SecurityException("CURSOR_CALLER_INSTANCE_MISMATCH");
        }
        String callerSessionId = request.getString(RuntimeKeys.CALLER_SESSION_ID,
                request.getString(RuntimeKeys.CURSOR_OWNER_SESSION_ID, ""));
        if (callerSessionId == null || callerSessionId.trim().isEmpty()) {
            throw new IllegalArgumentException(RuntimeKeys.CALLER_SESSION_ID + " is required");
        }
        long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION,
                request.getLong(RuntimeKeys.CURSOR_OWNER_GENERATION, -1));
        if (!lease.callerSessionId().equals(callerSessionId)
                || lease.callerGeneration() != callerGeneration) {
            throw new SecurityException("CURSOR_CALLER_SESSION_MISMATCH");
        }
        if (request.getBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, false)) return;
        GuestSession caller = sessionById(callerSessionId, callerGeneration);
        if (!RuntimePreparingSessionPolicy.isOperational(caller, request, true)
                || !ownerKey(caller.packageName(), caller.virtualUserId()).equals(lease.callerInstance())) {
            throw new SecurityException("CURSOR_CALLER_SESSION_NOT_READY");
        }
        // Authorization is fixed when the Cursor lease is issued. Revocation blocks new opens;
        // the existing lease remains bounded by its own TTL and Session/generation lifecycle.
    }

    static ProviderBatchRuntime.BatchException findBatchException(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof ProviderBatchRuntime.BatchException) {
                return (ProviderBatchRuntime.BatchException) cursor;
            }
            if (cursor.getCause() == cursor) break;
            cursor = cursor.getCause();
        }
        return null;
    }

    static boolean isProviderCursorOperation(String operation) {
        return ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation);
    }

    static void requireFileTargetSession(GuestSession session,
            BrokerFileRuntime.Lease lease, Bundle request) {
        if (!RuntimePreparingSessionPolicy.isOperational(session, request, false)) {
            throw new IllegalStateException("PROVIDER_FILE_TARGET_SESSION_NOT_READY");
        }
        if (!session.packageName().equals(lease.targetPackage())
                || session.virtualUserId() != lease.targetVirtualUserId()
                || !session.processName().equals(lease.targetProcessName())
                || !ownerKey(session.packageName(), session.virtualUserId()).equals(lease.targetInstance())) {
            throw new SecurityException("PROVIDER_FILE_TARGET_IDENTITY_MISMATCH");
        }
    }

    void validateFileRequestIdentity(Bundle request, String requestedPackage, int requestedUser,
                                             BrokerFileRuntime.Lease lease) {
        if (!lease.targetPackage().equals(requestedPackage) || lease.targetVirtualUserId() != requestedUser) {
            throw new SecurityException("PROVIDER_FILE_TARGET_REQUEST_MISMATCH");
        }
        String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, requestedPackage);
        int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, requestedUser);
        if (!ownerKey(callerPackage, callerUser).equals(lease.callerInstance())) {
            throw new SecurityException("PROVIDER_FILE_CALLER_INSTANCE_MISMATCH");
        }
        String callerSessionId = request.getString(RuntimeKeys.CALLER_SESSION_ID,
                request.getString(RuntimeKeys.FILE_OWNER_SESSION_ID, ""));
        if (callerSessionId == null || callerSessionId.trim().isEmpty()) {
            throw new IllegalArgumentException(RuntimeKeys.CALLER_SESSION_ID + " is required");
        }
        long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION,
                request.getLong(RuntimeKeys.FILE_OWNER_GENERATION, -1));
        if (!lease.callerSessionId().equals(callerSessionId)
                || lease.callerGeneration() != callerGeneration) {
            throw new SecurityException("PROVIDER_FILE_CALLER_SESSION_MISMATCH");
        }
        if (request.getBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, false)) return;
        GuestSession caller = sessionById(callerSessionId, callerGeneration);
        if (!RuntimePreparingSessionPolicy.isOperational(caller, request, true)
                || !ownerKey(caller.packageName(), caller.virtualUserId()).equals(lease.callerInstance())) {
            throw new SecurityException("PROVIDER_FILE_CALLER_SESSION_NOT_READY");
        }
        // A delivered file descriptor is an independent OS capability. Revocation blocks new opens;
        // Broker/Guest retained copies are still bounded by Lease TTL and Session/generation cleanup.
    }

    void purgeExpiredResources() {
        purgeExpiredResources(now());
    }

    private void purgeExpiredResources(long nowMs) {
        providerResources.purgeExpired(nowMs);
        receiverCoordinator.purgeExpired();
        serviceCoordinator.purgeExpiredForeground();
        isolatedProcessCoordinator.purgeExpiredForeground();
    }

    Bundle callGuest(int slot, RuntimeGuestConnectionPool.GuestCall call) throws Exception {
        return guestConnections.call(slot, call);
    }

    void releaseGuestConnection(int slot) {
        guestConnections.release(slot);
    }

    private void handleGuestDisconnect(int slot, String reason) {
        GuestSession affected;
        synchronized (this) {
            affected = sessions.markSlotDisconnected(slot, now(), reason);
        }
        if (affected == null) return;
        if (ownershipSweep != null) ownershipSweep.death(affected, reason);
        RuntimeEventLog.event("GUEST_PROCESS_DISCONNECTED",
                sessionBundle(affected, affected.state().name()));
    }

    GuestSession findSession(String sessionId, long generation) {
        if (sessionId == null || sessionId.trim().isEmpty() || generation < 1) {
            throw new SecurityException("INVALID_SESSION_IDENTITY");
        }
        for (GuestSession session : sessions.snapshot()) {
            if (session.sessionId().equals(sessionId) && session.generation() == generation
                    && session.state() != SessionState.STOPPED && session.state() != SessionState.FAILED) {
                return session;
            }
        }
        throw new SecurityException("SESSION_OR_GENERATION_MISMATCH");
    }

    @Override public void onDestroy() {
        pendingIntentRelayExecutor.shutdownNow();
        for (GuestSession session : sessions.snapshot()) {
            if (ownershipSweep != null) {
                ownershipSweep.stop(session, "ORDERED_RECEIVER_BROKER_DESTROYED");
            } else {
                receiverCoordinator.stopSession(session, "ORDERED_RECEIVER_BROKER_DESTROYED");
                serviceCoordinator.stopSession(session);
                providerResources.stopSession(session);
            }
        }
        if (guestConnections != null) guestConnections.close();
        receiverCoordinator.invalidateAll("ORDERED_RECEIVER_BROKER_DESTROYED");
        if (packageAuthority != null) packageAuthority.close();
        if (crossAbiCompanion != null) crossAbiCompanion.close();
        crossAbiProviderRelay.close();
        if (runtimePermissionCoordinator != null) runtimePermissionCoordinator.close();
        if (systemServiceCoordinator != null) systemServiceCoordinator.close();
        serviceCoordinator.close();
        isolatedProcessCoordinator.close();
        isolatedShares.close();
        RuntimePeerPolicy.installIsolatedPeerRegistry(null);
        super.onDestroy();
    }

    Bundle sessionBundle(GuestSession session, String status) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, status);
        out.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        out.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        out.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        out.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        out.putString(RuntimeKeys.PACKAGE_REVISION, session.packageRevision());
        out.putInt(RuntimeKeys.VIRTUAL_UID, uidRegistry().uidFor(session.packageName(), session.virtualUserId()));
        out.putLong(RuntimeKeys.GENERATION, session.generation());
        out.putString("sessionState", session.state().name());
        out.putString("failure", session.failure());
        return out;
    }

    VirtualUidRegistry uidRegistry() {
        VirtualUidRegistry registry = virtualUids;
        if (registry == null) throw new IllegalStateException("VIRTUAL_UID_REGISTRY_NOT_INITIALIZED");
        return registry;
    }

    private final class OrdinaryOwnershipHooks implements RuntimeOwnershipSweep.Hooks {
        @Override public void sweepActivity(GuestSession session, RuntimeOwnershipGraph.Event event) {
            if (event == RuntimeOwnershipGraph.Event.STOP
                    || event == RuntimeOwnershipGraph.Event.RECOVERY_FAILED) {
                activityRuntime.invalidate(session);
            } else {
                activityRuntime.processDisconnected(session);
            }
        }

        @Override public void sweepService(GuestSession session, RuntimeOwnershipGraph.Event event) {
            if (event == RuntimeOwnershipGraph.Event.STOP) serviceCoordinator.stopSession(session);
            else serviceCoordinator.disconnectSession(session);
        }

        @Override public void sweepReceiver(GuestSession session, RuntimeOwnershipGraph.Event event,
                                            String reason) {
            String token = reason == null || reason.isEmpty()
                    ? "ORDERED_RECEIVER_GUEST_DISCONNECTED" : reason;
            if (event == RuntimeOwnershipGraph.Event.STOP) {
                receiverCoordinator.stopSession(session, token);
            } else {
                receiverCoordinator.disconnectSession(session,
                        token.startsWith("ORDERED_RECEIVER_")
                                ? token : "ORDERED_RECEIVER_GUEST_DISCONNECTED:" + token);
            }
        }

        @Override public void sweepProviderLease(GuestSession session,
                                                 RuntimeOwnershipGraph.Event event) {
            crossAbiProviderRelay.invalidateCaller(session.packageName(), session.virtualUserId(),
                    session.sessionId(), session.generation());
            if (event == RuntimeOwnershipGraph.Event.STOP) providerResources.stopSession(session);
            else providerResources.disconnectSession(session);
        }

        @Override public void revokeProviderGrant(GuestSession session) {
            providerResources.stopSession(session);
        }

        @Override public void sweepSystemServiceCallback(GuestSession session) {
            if (systemServiceCoordinator != null) systemServiceCoordinator.stop(session);
        }

        @Override public void revokeIsolatedPeer(GuestSession session) {
            isolatedProcessCoordinator.peerRegistry()
                    .revoke(session.sessionId(), session.generation());
        }
    }

    static boolean isPrepared(Bundle bundle) {
        String status = bundle.getString(RuntimeKeys.STATUS, "");
        return "PREPARED".equals(status) || "ALREADY_PREPARED".equals(status)
                || "PREPARED_DEGRADED".equals(status) || "ALREADY_PREPARED_DEGRADED".equals(status);
    }
    static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    long now() { return clock.nowMillis(); }
    public static String ownerKey(String packageName, int userId) {
        return BrokerProviderRuntime.instanceId(packageName, userId);
    }
    static String processKey(String packageName, int userId, String processName) {
        return ownerKey(packageName, userId) + ":" + processName;
    }
    static String processName(Bundle bundle, String packageName) {
        String value = bundle == null ? "" : bundle.getString(RuntimeKeys.PROCESS_NAME, "");
        if (value == null || value.trim().isEmpty()) return packageName;
        value = value.trim();
        return value.startsWith(":") ? packageName + value : value;
    }

    static Bundle failure(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return failure(root.getClass().getName(), String.valueOf(root.getMessage()));
    }
    static Bundle failure(String type, String message) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "FAILED");
        out.putString(RuntimeKeys.ERROR_TYPE, type);
        out.putString(RuntimeKeys.ERROR_MESSAGE, message == null ? "" : message);
        return out;
    }
    private static final class ObserverCaller {
        final String instanceId;
        final GuestSession session;

        ObserverCaller(String instanceId, GuestSession session) {
            this.instanceId = instanceId;
            this.session = session;
        }
    }
    static final class ProviderAccess {
        final String callerInstance;
        final String callerSessionId;
        final long callerGeneration;
        final String targetInstance;
        final String uri;
        final int flags;

        ProviderAccess(String callerInstance, String callerSessionId, long callerGeneration,
                       String targetInstance, String uri, int flags) {
            this.callerInstance = callerInstance;
            this.callerSessionId = callerSessionId;
            this.callerGeneration = callerGeneration;
            this.targetInstance = targetInstance;
            this.uri = uri;
            this.flags = flags;
        }
    }

}
