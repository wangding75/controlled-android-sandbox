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
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.NativeGuestPolicyContract;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import com.warden.controlledsandbox.runtime.provider.BrokerCursorRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerFileRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerObserverRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderBatchRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderLifecycleCoordinator;
import com.warden.controlledsandbox.runtime.provider.RuntimeProviderResourceCoordinator;
import com.warden.controlledsandbox.runtime.status.BrokerRuntimeStatusSource;
import com.warden.controlledsandbox.runtime.status.CombinedSessionMetricsRepository;
import com.warden.controlledsandbox.runtime.status.ServiceMetricsSource;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.contract.ProcessSlotContract;
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
import com.warden.controlledsandbox.runtime.status.RuntimeStatusDispatcher;
import com.warden.controlledsandbox.runtime.status.RuntimeStatusLegacyAdapter;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
/** Central process allocator and route authority. Business/UI code does not own runtime state. */
public final class RuntimeBrokerService extends Service implements RuntimeBrokerOperationHandler {
    private static final int SLOT_COUNT = ProcessSlotContract.ORDINARY_SLOT_COUNT;
    private final Clock clock = new SystemMonotonicClock();
    private final TokenGenerator tokenGenerator = new UuidTokenGenerator();
    private final AuditSink auditSink = new RuntimeAuditSink();
    private final SessionRegistry sessions = new SessionRegistry(SLOT_COUNT, tokenGenerator);
    private VirtualUidRegistry virtualUids;
    private RuntimePermissionCoordinator runtimePermissionCoordinator;
    private RuntimeSystemServiceCoordinator systemServiceCoordinator;
    private RuntimeComponentRecoveryCoordinator componentRecoveryCoordinator;
    private final UriGrantRegistry uriGrants = new UriGrantRegistry();
    private final BrokerStateStore brokerState = new BrokerStateStore();
    private final RuntimeIsolatedProcessCoordinator isolatedProcessCoordinator =
            new RuntimeIsolatedProcessCoordinator(this, brokerState, clock, tokenGenerator,
                    this::validateInput, this::makeSpec, () -> systemServiceCoordinator,
                    this::sessionBundle);
    private final RuntimeReceiverCoordinator receiverCoordinator = new RuntimeReceiverCoordinator(
            sessions, brokerState, clock, tokenGenerator,
            this::prepareGuestInternal,
            this::sessionById,
            (processSlot, request) -> callGuest(processSlot, guest -> guestOperation(
                    guest, RuntimeOperationRequest.INVOKE_COMPONENT, request)));
    private final BrokerActivityRuntime activityRuntime = new BrokerActivityRuntime(brokerState);
    private final ConcurrentHashMap<String, GuestLaunchObservation> launchObservations =
            new ConcurrentHashMap<>();
    private static final long LAUNCH_OBSERVATION_MS = 35_000L;
    private final RuntimeServiceCoordinator serviceCoordinator = new RuntimeServiceCoordinator(brokerState,
            (slot, request) -> callGuest(slot, guest -> guestOperation(
                    guest, RuntimeOperationRequest.INVOKE_COMPONENT, request)), clock);
    private final BrokerProviderRuntime providerRuntime = new BrokerProviderRuntime();
    private final BrokerCursorRuntime cursorRuntime = new BrokerCursorRuntime();
    private final BrokerFileRuntime fileRuntime = new BrokerFileRuntime();
    private final BrokerObserverRuntime observerRuntime = new BrokerObserverRuntime();
    private final ProviderLifecycleCoordinator providerLifecycle = new ProviderLifecycleCoordinator(
            providerRuntime, cursorRuntime, fileRuntime, observerRuntime, uriGrants);
    private final RuntimeProviderResourceCoordinator providerResources =
            new RuntimeProviderResourceCoordinator(providerLifecycle,
                    this::sessionById,
                    session -> brokerState.prepared(processKey(session.packageName(),
                            session.virtualUserId(), session.processName())),
                    (slot, request) -> callGuest(slot, guest -> guestOperation(
                            guest, RuntimeOperationRequest.INVOKE_COMPONENT, request)));
    private final RuntimeStatusDispatcher runtimeStatusDispatcher = new RuntimeStatusDispatcher(
            clock,
            new BrokerRuntimeStatusSource(
                    new CombinedSessionMetricsRepository(sessions, isolatedProcessCoordinator.sessionMetrics()),
                    activityRuntime, combinedServiceMetrics(), providerLifecycle,
                    providerRuntime, receiverCoordinator.lifecycle()),
            this::purgeExpiredResources,
            auditSink);
    private RuntimeGuestConnectionPool guestConnections;
    private ServiceMetricsSource combinedServiceMetrics() {
        return () -> Math.addExact(serviceCoordinator.recordCount(),
                isolatedProcessCoordinator.serviceMetrics().recordCount());
    }
    @Override public void onCreate() {
        super.onCreate();
        guestConnections = new RuntimeGuestConnectionPool(this, this::handleGuestDisconnect);
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

        @Override public void stopGuest(String packageName, int virtualUserId) {
            CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
            RuntimeBrokerService.this.stopGuestInternal(packageName, virtualUserId);
        }
    };

    @Override public Bundle prepareGuest(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        return RuntimeBrokerService.this.prepareGuestInternal(request);
    }

    @Override public Bundle launchActivity(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this); IsolatedProcessRoutePolicy.rejectOrdinaryRoute(request);
        startService(new Intent(RuntimeBrokerService.this, RuntimeBrokerService.class));
        Bundle prepared = RuntimeBrokerService.this.prepareGuestInternal(request);
        if (!isPrepared(prepared)) return prepared;
        String issuedRouteToken = "";
        try {
            String packageName = prepared.getString(RuntimeKeys.PACKAGE_NAME, "");
            int userId = prepared.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String processName = processName(prepared, packageName);
            GuestSession session = findSession(prepared.getString(RuntimeKeys.SESSION_ID, ""), prepared.getLong(RuntimeKeys.GENERATION, 0L));
            if (session != null && (!session.packageName().equals(packageName) || session.virtualUserId() != userId || !session.processName().equals(processName))) return failure("SESSION_IDENTITY_MISMATCH", "Prepared session identity changed");
            if (session == null) return failure("SESSION_NOT_FOUND", "Prepared session disappeared");
            String component = request == null ? "" : request.getString(RuntimeKeys.COMPONENT_CLASS, "");
            if (component.trim().isEmpty()) component = prepared.getString(RuntimeKeys.COMPONENT_CLASS, "");
            if (component.trim().isEmpty()) return failure("COMPONENT_MISSING", "No Guest Activity class supplied");
            Bundle transaction = activityRuntime.launch(session, component, prepared, request);
            issuedRouteToken = transaction.getString(RuntimeKeys.ROUTE_TOKEN, "");
            Intent launch = new Intent(RuntimeBrokerService.this, RuntimeStubComponents.activityClassFor(session.processSlot()));
            // The broker is a Service context. Preserve the virtual launch flags
            // in the transaction, but always mark the host trampoline as a new
            // task so Android accepts this Service-originated startActivity call.
            launch.addFlags(transaction.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0)
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            launch.putExtra(RuntimeKeys.ROUTE_TOKEN, issuedRouteToken);
            launch.putExtra(RuntimeKeys.SESSION_ID, session.sessionId());
            launch.putExtra(RuntimeKeys.GENERATION, session.generation());
            launch.putExtra(RuntimeKeys.ACTIVITY_TOKEN,
                    transaction.getString(RuntimeKeys.ACTIVITY_TOKEN, ""));
            // Keep the bounded Guest intent projection on the host launch envelope. VA/NBB
            // rewrite the framework launch transaction before ActivityThread instantiation; the
            // CAS Instrumentation bridge needs the same data before a Stub object exists. The
            // one-time route token remains authoritative and the copied fields are only a
            // transport projection, never a second route authority.
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.COMPONENT_CLASS);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.TASK_ID);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.TARGET_PACKAGE_NAME);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.INTENT_COMPONENT_PACKAGE);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.INTENT_COMPONENT_CLASS);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.ACTIVITY_ACTION);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.ACTIVITY_FLAGS);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.URI);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.BROADCAST_SCHEME);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.BROADCAST_HOST);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.BROADCAST_PATH);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.BROADCAST_MIME_TYPE);
            copyActivityFrameworkField(launch, transaction, RuntimeKeys.BROADCAST_CATEGORIES);
            if (transaction.containsKey(RuntimeKeys.INTENT_EXTRAS)) {
                Bundle extras = transaction.getBundle(RuntimeKeys.INTENT_EXTRAS);
                if (extras != null) launch.putExtra(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
            }
            startActivity(launch);
            // A Guest Activity callback runs on the same process main looper that the Android
            // framework uses to deliver the next launch transaction.  Waiting here for the
            // nested Activity to reach CREATED/RESUMED would block that looper through the
            // Broker call path (Guest main -> Broker -> ActivityThread), producing the same
            // re-entrant deadlock that VA/NBB avoid by acknowledging the transaction first.
            // The route and ledger remain pending and the target process reports lifecycle
            // evidence asynchronously through ACTIVITY_EVENT.
            if (request != null && request.getInt(RuntimeKeys.CALLER_TASK_ID, 0) > 0) {
                Bundle nested = sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                nested.putAll(transaction);
                nested.putString(RuntimeKeys.STATUS, GuestLaunchGate.LAUNCH_PENDING);
                nested.putBoolean("launcherResolved", true);
                return nested;
            }
            String activityToken = transaction.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            String sessionId = session.sessionId();
            GuestLaunchObservation existing = sessionId.isEmpty()
                    ? null : launchObservations.get(sessionId);
            // A splash Activity may synchronously start the real UI Activity.  Waiting again
            // on that nested launch blocks the Host main looper, so the second Stub never
            // reaches onCreate.  Only the first in-flight launch owns the observation window.
            if (existing != null) {
                launchObservations.put(activityToken, existing);
                Bundle nested = sessionBundle(session, GuestLaunchGate.LAUNCH_PENDING);
                nested.putAll(transaction);
                nested.putString(RuntimeKeys.STATUS, GuestLaunchGate.LAUNCH_PENDING);
                return nested;
            }
            GuestLaunchObservation observation = new GuestLaunchObservation(activityToken, component);
            if (!sessionId.isEmpty()) launchObservations.put(sessionId, observation);
            launchObservations.put(activityToken, observation);
            try {
                observation.await(LAUNCH_OBSERVATION_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            GuestLaunchEvidence evidence = observation.close();
            launchObservations.remove(activityToken, observation);
            if (!sessionId.isEmpty()) launchObservations.remove(sessionId, observation);
            String gate = GuestLaunchGate.evaluate(evidence);
            Bundle out = sessionBundle(session, gate);
            out.putAll(transaction);
            out.putString(RuntimeKeys.STATUS, gate);
            out.putBoolean("launcherResolved", evidence.launcherResolved);
            out.putBoolean("activityCreated", evidence.onCreateCompleted);
            out.putBoolean("activityResumed", evidence.resumed);
            out.putBoolean("windowEvidence", evidence.windowEvidence);
            out.putInt("fatalCount", evidence.fatalCount);
            out.putInt("anrCount", evidence.anrCount);
            if (GuestLaunchGate.LAUNCH_FAILED.equals(gate)) {
                out.putString(RuntimeKeys.ERROR_TYPE, "LAUNCH_GATE_FAILED");
                out.putString(RuntimeKeys.ERROR_MESSAGE, evidence.failure.isEmpty()
                        ? "guest Activity create/resume/window not confirmed" : evidence.failure);
            }
            return out;
        } catch (Throwable error) {
            try {
                if (!issuedRouteToken.isEmpty()) activityRuntime.launchFailed(issuedRouteToken);
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            return failure(error);
        }
    }

    private static void copyActivityFrameworkField(Intent target, Bundle source, String key) {
        if (target == null || source == null || key == null || !source.containsKey(key)) return;
        Object value = source.get(key);
        if (value instanceof String string) target.putExtra(key, string);
        else if (value instanceof Integer integer) target.putExtra(key, integer);
        else if (value instanceof ArrayList<?> list) {
            ArrayList<String> strings = new ArrayList<>();
            for (Object item : list) if (item instanceof String string) strings.add(string);
            target.putStringArrayListExtra(key, strings);
        }
    }

    @Override public Bundle invokeComponent(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        BrokerProviderRuntime.OperationRoute providerRoute = null;
        boolean providerAuditFinalized = false;
        UriGrantRegistry.Authorization uriGrantAuthorization = null;
        UriGrantRegistry.AuthorizationResult uriGrantAuthorizationResult = null;
        BrokerCursorRuntime.QueryReservation cursorQueryReservation = null;
        BrokerCursorRuntime.PageReservation cursorPageReservation = null;
        BrokerCursorRuntime.TerminalReservation cursorTerminalReservation = null;
        GuestSession cursorTargetSession = null;
        BrokerFileRuntime.OpenReservation fileOpenReservation = null;
        BrokerFileRuntime.CloseReservation fileCloseReservation = null;
        GuestSession fileTargetSession = null;
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            IsolatedProcessRoutePolicy.Match isolatedMatch = IsolatedProcessRoutePolicy.match(request);
            if (isolatedMatch != null) return isolatedProcessCoordinator.invoke(request, isolatedMatch);
            purgeExpiredResources();
            String operation = request.getString(ComponentOperations.OPERATION, "");
            ComponentOperations.requireKnownProviderOperation(operation);
            ComponentOperations.requireKnownServiceOperation(operation);
            String requestedPackage = required(request, RuntimeKeys.PACKAGE_NAME);
            int requestedUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            if (requestedUser < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
            if (ComponentOperations.PROVIDER_OBSERVER_UNREGISTER.equals(operation)) {
                return unregisterProviderObserver(request, requestedPackage, requestedUser);
            }
            if (ComponentOperations.PROVIDER_NOTIFY_CHANGE.equals(operation)) {
                return notifyProviderObservers(request, requestedPackage, requestedUser);
            }
            GuestSession session;
            BrokerCursorRuntime.Lease cursorLease = null;
            BrokerFileRuntime.Lease fileLease = null;
            if (ComponentOperations.isProviderTransactionOperation(operation)) {
                String callerInstance = providerRuntime.requireCallerInstance(request, requestedPackage, requestedUser,
                        RuntimeBrokerService.this::sessionById);
                String requestedTarget = ownerKey(requestedPackage, requestedUser);
                uriGrantAuthorization = beginUriGrantAuthorization(request, callerInstance, requestedTarget);
                UriGrantRegistry.Authorization authorization = uriGrantAuthorization;
                providerRoute = providerRuntime.routeOperation(request, operation, callerInstance,
                        requestedUser, requestedTarget, authorization == null ? null : authorization::allows,
                        permission -> ProviderDeclaredPermissionAuthorizer.allows(request, requestedPackage, requestedUser,
                                permission, this::sessionById, brokerState), now());
                if ("URI_GRANT".equals(providerRoute.permissionBasis())) {
                    if (authorization == null) throw new SecurityException("URI_GRANT_CALLER_SESSION_REQUIRED");
                    uriGrantAuthorizationResult = authorization.commit(now());
                }
                ProviderAuthorityRegistry.Entry target = providerRoute.entry();
                session = sessionById(target.sessionId(), target.generation());
                requireProviderTargetSession(session, target, request);
            } else if (isProviderCursorOperation(operation)) {
                cursorLease = cursorRuntime.require(required(request, RuntimeKeys.CURSOR_TOKEN), now());
                validateCursorRequestIdentity(request, requestedPackage, requestedUser, cursorLease);
                session = sessionById(cursorLease.targetSessionId(), cursorLease.targetGeneration());
                requireCursorTargetSession(session, cursorLease, request);
            } else if (ComponentOperations.isProviderFileLeaseOperation(operation)) {
                fileLease = fileRuntime.require(required(request, RuntimeKeys.FILE_TOKEN), now());
                validateFileRequestIdentity(request, requestedPackage, requestedUser, fileLease);
                session = sessionById(fileLease.targetSessionId(), fileLease.targetGeneration());
                requireFileTargetSession(session, fileLease, request);
            } else {
                String processName = processName(request, requestedPackage);
                session = sessions.get(requestedPackage, requestedUser, processName);
                if (!RuntimePreparingSessionPolicy.isOperational(session, request, false)) {
                    Bundle prepared = RuntimeBrokerService.this.prepareGuestInternal(request);
                    if (!isPrepared(prepared)) return prepared;
                    session = sessions.get(requestedPackage, requestedUser, processName);
                }
            }
            if (ComponentOperations.SEND_BROADCAST.equals(operation)
                    && !request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                    && request.getString(RuntimeKeys.RECEIVER_ID, "").trim().isEmpty()) {
                return receiverCoordinator.dispatchManifestBroadcast(request, session);
            }
            if ((ComponentOperations.SEND_IMPLICIT_BROADCAST.equals(operation)
                    || ComponentOperations.SEND_ORDERED_BROADCAST.equals(operation)
                    || request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false))
                    && request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                    && request.getString(RuntimeKeys.RECEIVER_ID, "").trim().isEmpty()) {
                return receiverCoordinator.dispatchImplicitManifestBroadcast(request, session,
                        ComponentOperations.SEND_ORDERED_BROADCAST.equals(operation)
                                || request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false));
            }
            String packageName = session.packageName();
            int userId = session.virtualUserId();
            String processName = session.processName();
            Bundle base = brokerState.prepared(processKey(packageName, userId, processName));
            if (base == null) throw new IllegalStateException("PREPARED_SPEC_MISSING");

            // Service component calls use the same Broker allocation path as the existing
            // virtual Service runtime, but the actual callbacks are delivered by the Android
            // ActivityThread in the target process.  Returning the lease here lets the Guest
            // Context start/bind a predeclared StubService without constructing the Guest
            // Service on the Broker thread.
            if (ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(operation)) {
                Bundle routed = new Bundle();
                routed.putString(RuntimeKeys.STATUS, "FRAMEWORK_SERVICE_ROUTE");
                routed.putString(RuntimeKeys.SESSION_ID, session.sessionId());
                routed.putLong(RuntimeKeys.GENERATION, session.generation());
                routed.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
                routed.putString(RuntimeKeys.PROCESS_NAME, session.processName());
                routed.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
                routed.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
                routed.putString(RuntimeKeys.COMPONENT_CLASS,
                        required(request, RuntimeKeys.COMPONENT_CLASS));
                routed.putString("frameworkServiceStubPackage", getPackageName());
                routed.putString("frameworkServiceStubClass",
                        RuntimeStubComponents.componentServiceClassFor(session.processSlot()).getName());
                return routed;
            }
            Bundle call = new Bundle(base);
            call.putAll(request);
            call.putString(RuntimeKeys.PACKAGE_NAME, packageName);
            call.putInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
            call.putString(RuntimeKeys.PROCESS_NAME, processName);
            final GuestSession activeSession = session;
            restoreTargetSessionIdentity(call, base, activeSession);
            if (providerRoute != null) {
                call.putString(ComponentOperations.AUTHORITY, providerRoute.authority());
                call.putString(RuntimeKeys.COMPONENT_CLASS, providerRoute.entry().component());
                call.putString(RuntimeKeys.URI, providerRoute.uri());
            }
            cursorTargetSession = activeSession;
            fileTargetSession = activeSession;
            ProviderAccess providerAccess = providerRoute != null
                    ? providerAccess(providerRoute, request, activeSession) : null;
            if (ComponentOperations.PROVIDER_OBSERVER_REGISTER.equals(operation)) {
                if (providerAccess == null) throw new IllegalStateException("PROVIDER_OBSERVER_ACCESS_MISSING");
                BrokerObserverRuntime.RegisterResult registration = observerRuntime.register(request,
                        providerAccess.callerInstance, providerAccess.callerSessionId,
                        providerAccess.callerGeneration, providerAccess.targetInstance,
                        activeSession.sessionId(), activeSession.generation(), activeSession.virtualUserId(),
                        providerRoute.authority(), providerAccess.uri);
                Bundle out = new Bundle();
                out.putString(RuntimeKeys.STATUS, registration.created()
                        ? "PROVIDER_OBSERVER_REGISTERED" : "PROVIDER_OBSERVER_ALREADY_REGISTERED");
                out.putString(RuntimeKeys.OBSERVER_ID, registration.entry().id());
                out.putString(ComponentOperations.AUTHORITY, registration.entry().authority());
                out.putString(RuntimeKeys.URI, registration.entry().uri());
                out.putBoolean(RuntimeKeys.OBSERVER_NOTIFY_DESCENDANTS,
                        registration.entry().notifyDescendants());
                out.putBoolean(RuntimeKeys.OBSERVER_DELIVER_SELF,
                        registration.entry().deliverSelfNotifications());
                if (uriGrantAuthorizationResult != null) {
                    out.putBoolean(RuntimeKeys.URI_GRANT_CONSUMED_ONE_TIME,
                            uriGrantAuthorizationResult.oneTimeConsumed());
                }
                providerRuntime.completeOperation(providerRoute, out, now());
                providerAuditFinalized = true;
                return out;
            } else if (ComponentOperations.PROVIDER_QUERY.equals(operation)) {
                if (providerAccess == null) throw new IllegalStateException("PROVIDER_QUERY_ACCESS_MISSING");
                cursorQueryReservation = cursorRuntime.reserveQuery(providerAccess.callerInstance,
                        providerAccess.callerSessionId, providerAccess.callerGeneration,
                        providerAccess.targetInstance, activeSession.packageName(), activeSession.virtualUserId(),
                        activeSession.processName(), activeSession.sessionId(), activeSession.generation(),
                        providerAccess.uri, providerAccess.flags, now());
                call.putString(RuntimeKeys.CURSOR_TOKEN, cursorQueryReservation.token());
                call.putLong(RuntimeKeys.CURSOR_TTL_MS, BrokerCursorRuntime.LEASE_TTL_MS);
            } else if (ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)) {
                if (cursorLease == null) throw new IllegalStateException("CURSOR_LEASE_MISSING");
                cursorPageReservation = cursorRuntime.reservePage(cursorLease.token(),
                        cursorLease.callerSessionId(), cursorLease.callerGeneration(),
                        cursorLease.targetSessionId(), cursorLease.targetGeneration(),
                        request.getInt(RuntimeKeys.CURSOR_OFFSET, -1),
                        request.getLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE, -1),
                        request.getInt(RuntimeKeys.CURSOR_PAGE_SIZE, 64), now());
            } else if (ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)
                    || ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation)) {
                if (cursorLease == null) throw new IllegalStateException("CURSOR_LEASE_MISSING");
                cursorTerminalReservation = cursorRuntime.reserveTerminal(cursorLease.token(),
                        cursorLease.callerSessionId(), cursorLease.callerGeneration(),
                        cursorLease.targetSessionId(), cursorLease.targetGeneration(),
                        request.getLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE, -1), now());
            } else if (ComponentOperations.isProviderFileOpenOperation(operation)) {
                if (providerAccess == null) throw new IllegalStateException("PROVIDER_FILE_ACCESS_MISSING");
                String mode = ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE.equals(operation)
                        ? "r" : required(request, RuntimeKeys.PROVIDER_FILE_MODE);
                String mimeType = request.getString(RuntimeKeys.PROVIDER_MIME_TYPE, "");
                fileOpenReservation = fileRuntime.reserveOpen(operation, providerAccess.callerInstance,
                        providerAccess.callerSessionId, providerAccess.callerGeneration,
                        providerAccess.targetInstance, activeSession.packageName(), activeSession.virtualUserId(),
                        activeSession.processName(), activeSession.sessionId(), activeSession.generation(),
                        providerAccess.uri, providerAccess.flags, mode, mimeType, now());
                call.putString(RuntimeKeys.FILE_TOKEN, fileOpenReservation.token());
                call.putLong(RuntimeKeys.FILE_TTL_MS, BrokerFileRuntime.LEASE_TTL_MS);
                call.putString(RuntimeKeys.PROVIDER_FILE_MODE, mode);
            } else if (ComponentOperations.PROVIDER_FILE_CLOSE.equals(operation)) {
                if (fileLease == null) throw new IllegalStateException("PROVIDER_FILE_LEASE_MISSING");
                fileCloseReservation = fileRuntime.reserveClose(fileLease.token(),
                        fileLease.callerSessionId(), fileLease.callerGeneration(),
                        fileLease.targetSessionId(), fileLease.targetGeneration(), now());
            }
            BrokerReceiverRuntime.Reservation receiverReservation = null;
            BrokerProviderRuntime.Reservation providerReservation = null;
            try {
                if (ComponentOperations.REGISTER_RECEIVER.equals(operation)) {
                    receiverReservation = receiverCoordinator.reserveRegistration(request, activeSession);
                } else if (ComponentOperations.UNREGISTER_RECEIVER.equals(operation)) {
                    receiverCoordinator.requireOwnedRegistration(request, activeSession);
                } else if (ComponentOperations.PREPARE_PROVIDER.equals(operation)) {
                    providerReservation = providerRuntime.reservePrepare(request, activeSession);
                }
                Bundle result;
                if (ComponentOperations.SEND_BROADCAST.equals(operation)
                        && request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                        && request.getString(RuntimeKeys.RECEIVER_ID, "").trim().isEmpty()) {
                    result = receiverCoordinator.dispatchDynamicBroadcast(request, activeSession);
                } else {
                    result = callGuest(session.processSlot(), guest -> guestOperation(
                            guest, RuntimeOperationRequest.INVOKE_COMPONENT, call));
                }
                if (ComponentOperations.PROVIDER_APPLY_BATCH.equals(operation)) {
                    if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                        if (result.getInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, Integer.MIN_VALUE)
                                == Integer.MIN_VALUE) {
                            result.putInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, -1);
                        }
                    } else {
                        try {
                            ProviderBatchRuntime.validateResult(result,
                                    request.getInt(RuntimeKeys.PROVIDER_BATCH_COUNT, -1));
                        } catch (ProviderBatchRuntime.BatchException error) {
                            throw new IllegalStateException(error.getMessage(), error);
                        }
                    }
                }
                result.putString(RuntimeKeys.SESSION_ID, activeSession.sessionId());
                result.putLong(RuntimeKeys.GENERATION, activeSession.generation());
                result.putInt(RuntimeKeys.PROCESS_SLOT, activeSession.processSlot());
                result.putString(RuntimeKeys.PROCESS_NAME, activeSession.processName());
                if (uriGrantAuthorizationResult != null) {
                    result.putBoolean(RuntimeKeys.URI_GRANT_CONSUMED_ONE_TIME,
                            uriGrantAuthorizationResult.oneTimeConsumed());
                }
                if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                    cursorRuntime.rollbackQuery(cursorQueryReservation);
                    if (cursorPageReservation != null) {
                        cursorRuntime.abort(cursorPageReservation.token());
                        providerResources.closeCursorBestEffort(activeSession, cursorPageReservation.token());
                    }
                    if (cursorTerminalReservation != null) cursorRuntime.completeTerminal(cursorTerminalReservation);
                    fileRuntime.rollbackOpen(fileOpenReservation);
                    if (fileCloseReservation != null) {
                        fileRuntime.abort(fileCloseReservation.token());
                        providerResources.closeFileBestEffort(activeSession, fileCloseReservation.token());
                    }
                    if (providerRoute != null) {
                        providerRuntime.completeOperation(providerRoute, result, now());
                        providerAuditFinalized = true;
                    }
                    receiverCoordinator.rollbackRegistration(receiverReservation);
                    providerRuntime.rollbackPrepare(providerReservation);
                } else {
                    serviceCoordinator.applySuccessfulOperation(activeSession, request, result);
                    if (ComponentOperations.UNREGISTER_RECEIVER.equals(operation)) {
                        receiverCoordinator.commitUnregister(request, activeSession);
                    }
                    if (ComponentOperations.PROVIDER_QUERY.equals(operation)) {
                        BrokerCursorRuntime.Lease committed = cursorRuntime.commitQuery(
                                cursorQueryReservation, result, now());
                        result.putString(RuntimeKeys.CURSOR_OWNER_SESSION_ID, committed.callerSessionId());
                        result.putLong(RuntimeKeys.CURSOR_OWNER_GENERATION, committed.callerGeneration());
                        result.putLong(RuntimeKeys.CURSOR_EXPIRES_AT, committed.expiresAtMs());
                    } else if (ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)) {
                        BrokerCursorRuntime.Lease committed = cursorRuntime.commitPage(
                                cursorPageReservation, result, now());
                        result.putString(RuntimeKeys.CURSOR_OWNER_SESSION_ID, committed.callerSessionId());
                        result.putLong(RuntimeKeys.CURSOR_OWNER_GENERATION, committed.callerGeneration());
                    } else if (cursorTerminalReservation != null) {
                        cursorRuntime.completeTerminal(cursorTerminalReservation);
                    }
                    if (fileOpenReservation != null) {
                        BrokerFileRuntime.Lease committed = fileRuntime.commitOpen(
                                fileOpenReservation, result, now());
                        result.putString(RuntimeKeys.FILE_OWNER_SESSION_ID, committed.callerSessionId());
                        result.putLong(RuntimeKeys.FILE_OWNER_GENERATION, committed.callerGeneration());
                        result.putLong(RuntimeKeys.FILE_EXPIRES_AT, committed.expiresAtMs());
                    } else if (fileCloseReservation != null) {
                        fileRuntime.completeClose(fileCloseReservation);
                    }
                    if (providerRoute != null) {
                        providerRuntime.completeOperation(providerRoute, result, now());
                        providerAuditFinalized = true;
                    }
                }
                return result;
            } catch (Throwable error) {
                try {
                    receiverCoordinator.rollbackRegistration(receiverReservation);
                    providerRuntime.rollbackPrepare(providerReservation);
                    cursorRuntime.rollbackQuery(cursorQueryReservation);
                    if (cursorPageReservation != null) {
                        cursorRuntime.abort(cursorPageReservation.token());
                        providerResources.closeCursorBestEffort(activeSession, cursorPageReservation.token());
                    }
                    if (cursorTerminalReservation != null) {
                        try { cursorRuntime.completeTerminal(cursorTerminalReservation); } catch (RuntimeException ignored) { }
                    }
                    if (cursorQueryReservation != null && cursorTargetSession != null) {
                        providerResources.closeCursorBestEffort(cursorTargetSession, cursorQueryReservation.token());
                    }
                    fileRuntime.rollbackOpen(fileOpenReservation);
                    if (fileOpenReservation != null && fileTargetSession != null) {
                        providerResources.closeFileBestEffort(fileTargetSession, fileOpenReservation.token());
                    }
                    if (fileCloseReservation != null) {
                        fileRuntime.abort(fileCloseReservation.token());
                        providerResources.closeFileBestEffort(activeSession, fileCloseReservation.token());
                    }
                    if (providerRoute != null) {
                        providerRuntime.failOperation(providerRoute, error, now());
                        providerAuditFinalized = true;
                    }
                } finally {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                throw error;
            }
        } catch (Throwable error) {
            try {
                cursorRuntime.rollbackQuery(cursorQueryReservation);
                if (cursorPageReservation != null) cursorRuntime.abort(cursorPageReservation.token());
                fileRuntime.rollbackOpen(fileOpenReservation);
                if (fileCloseReservation != null) fileRuntime.abort(fileCloseReservation.token());
                if (providerRoute != null && !providerAuditFinalized) {
                    providerRuntime.failOperation(providerRoute, error, now());
                }
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            Bundle failed = failure(error);
            ProviderBatchRuntime.BatchException batchError = findBatchException(error);
            if (batchError != null) {
                failed.putInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, batchError.operationIndex());
            }
            return failed;
        }
    }

    @Override public Bundle grantUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String ownerPackage = required(request, RuntimeKeys.PACKAGE_NAME);
            int ownerUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String targetPackage = required(request, RuntimeKeys.TARGET_PACKAGE_NAME);
            int targetUser = request.getInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, -1);
            if (ownerUser < 0 || targetUser < 0) {
                throw new IllegalArgumentException("virtual user ids must be non-negative");
            }
            if (ownerUser != targetUser) throw new SecurityException("URI_GRANT_CROSS_USER_DENIED");
            String uri = required(request, RuntimeKeys.URI);
            ProviderAuthorityRegistry.Entry providerOwner = providerRuntime.requireGrantOwner(uri, ownerUser,
                    ownerKey(ownerPackage, ownerUser));
            GuestSession ownerSession = sessionById(providerOwner.sessionId(), providerOwner.generation());
            if (ownerSession == null || (ownerSession.state() != SessionState.READY
                    && ownerSession.state() != SessionState.ACTIVE)) {
                throw new IllegalStateException("URI_GRANT_OWNER_NOT_RUNNING");
            }
            String targetSessionId = required(request, RuntimeKeys.URI_GRANT_TARGET_SESSION_ID);
            long targetGeneration = request.getLong(RuntimeKeys.URI_GRANT_TARGET_GENERATION, -1);
            GuestSession targetSession = sessionById(targetSessionId, targetGeneration);
            if (targetSession == null || (targetSession.state() != SessionState.READY
                    && targetSession.state() != SessionState.ACTIVE)) {
                throw new SecurityException("URI_GRANT_TARGET_SESSION_NOT_READY");
            }
            if (!targetSession.packageName().equals(targetPackage)
                    || targetSession.virtualUserId() != targetUser) {
                throw new SecurityException("URI_GRANT_TARGET_IDENTITY_MISMATCH");
            }
            int flags = request.getInt(RuntimeKeys.URI_FLAGS, 0);
            long ttlMs = request.getLong(RuntimeKeys.URI_GRANT_TTL_MS, 60_000L);
            boolean oneTime = request.getBoolean(RuntimeKeys.URI_GRANT_ONE_TIME, false);
            UriGrantRegistry.Grant grant = uriGrants.grant(ownerKey(ownerPackage, ownerUser),
                    ownerSession.sessionId(), ownerSession.generation(),
                    ownerKey(targetPackage, targetUser), targetSession.sessionId(), targetSession.generation(),
                    ownerUser, uri, flags, oneTime, now(), ttlMs);
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "URI_PERMISSION_GRANTED");
            out.putString(RuntimeKeys.URI_GRANT_ID, grant.id());
            out.putLong(RuntimeKeys.URI_GRANT_EXPIRES_AT, grant.expiresAtMs());
            out.putInt(RuntimeKeys.URI_FLAGS, grant.flags());
            out.putBoolean(RuntimeKeys.URI_GRANT_ONE_TIME, grant.oneTime());
            out.putString(RuntimeKeys.URI_GRANT_TARGET_SESSION_ID, grant.targetSessionId());
            out.putLong(RuntimeKeys.URI_GRANT_TARGET_GENERATION, grant.targetGeneration());
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_ACTIVITY_EVENT", "activity event rejected", error);
            return failure(error);
        }
    }

    @Override public Bundle revokeUriPermission(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String ownerPackage = required(request, RuntimeKeys.PACKAGE_NAME);
            int ownerUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String grantId = required(request, RuntimeKeys.URI_GRANT_ID);
            UriGrantRegistry.Grant grant = uriGrants.require(grantId, now());
            GuestSession ownerSession = sessionById(grant.ownerSessionId(), grant.ownerGeneration());
            if (ownerSession == null || (ownerSession.state() != SessionState.READY
                    && ownerSession.state() != SessionState.ACTIVE)) {
                throw new SecurityException("URI_GRANT_OWNER_SESSION_NOT_READY");
            }
            boolean revoked = uriGrants.revoke(grantId, ownerKey(ownerPackage, ownerUser),
                    ownerSession.sessionId(), ownerSession.generation(), now());
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, revoked ? "URI_PERMISSION_REVOKED" : "URI_PERMISSION_NOT_FOUND");
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure(error);
        }
    }

    @Override public Bundle consumeRoute(String token, String sessionId, long generation) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        try {
            GuestSession current = sessionById(sessionId, generation);
            if (current == null) current = latestSessionById(sessionId);
            if (current == null) throw new SecurityException("SESSION_OR_GENERATION_MISMATCH");
            if (current.state() == SessionState.RECOVERING) {
                // ActivityManager can recreate the Stub Activity before the replacement Guest
                // generation has been prepared.  Recover from the broker-owned route envelope;
                // this is the same generic prepare path used by launchActivity.
                Bundle route = activityRuntime.routeForPreparation(token);
                if (route == null) throw new IllegalStateException("ACTIVITY_ROUTE_NOT_FOUND");
                Bundle prepared = prepareGuestInternal(route);
                if (!isPrepared(prepared)) {
                    throw new IllegalStateException(prepared.getString(
                            RuntimeKeys.ERROR_TYPE, "GUEST_RECOVERY_FAILED"));
                }
                current = findSession(sessionId,
                        prepared.getLong(RuntimeKeys.GENERATION, current.generation()));
            }
            Bundle payload = activityRuntime.consume(token, current);
            if (current.state() == SessionState.READY) {
                current = sessions.transition(current.packageName(), current.virtualUserId(), current.processName(),
                        current.generation(), SessionState.ACTIVE, now(), "");
            }
            payload.putString(RuntimeKeys.STATUS, "ROUTE_GRANTED");
            return payload;
        } catch (Throwable error) {
            try {
                activityRuntime.launchFailed(token);
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            return failure(error);
        }
    }

    @Override public Bundle activityEvent(Bundle request) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String activityToken = request.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
            for (GuestLaunchObservation observation : new java.util.LinkedHashSet<>(
                    launchObservations.values())) {
                observation.onActivityEvent(request);
            }
            if ("FAILED".equals(request.getString(RuntimeKeys.ACTIVITY_EVENT, ""))) {
                Bundle acknowledged = new Bundle();
                acknowledged.putString(RuntimeKeys.STATUS, "ACTIVITY_EVENT_APPLIED");
                acknowledged.putString(RuntimeKeys.ACTIVITY_TOKEN, activityToken);
                return acknowledged;
            }
            GuestSession current = findSession(required(request, RuntimeKeys.SESSION_ID),
                    request.getLong(RuntimeKeys.GENERATION, -1));
            return activityRuntime.event(current, request);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.e("CS_ACTIVITY_EVENT", "activity event rejected", error);
            return failure(error);
        }
    }

    @Override public Bundle sessionStatus(String packageName, int virtualUserId) {
        CallerGuard.requireRuntimePeer(RuntimeBrokerService.this);
        GuestSession session = sessions.get(packageName, virtualUserId, packageName);
        return session == null ? failure("SESSION_NOT_FOUND", "No active session") : sessionBundle(session, session.state().name());
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
    private synchronized Bundle prepareGuestInternal(Bundle request) {
        try {
            Bundle input = request == null ? new Bundle() : new Bundle(request);
            validateInput(input);
            String packageName = input.getString(RuntimeKeys.PACKAGE_NAME, "");
            int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
            String processName = processName(input, packageName);
            String packageRevision = required(input, RuntimeKeys.PACKAGE_REVISION);
            input.putString(RuntimeKeys.PROCESS_NAME, processName);
            stopMismatchedRevisionSessions(packageName, userId, packageRevision);
            receiverCoordinator.indexPackage(input);
            GuestSession session = sessions.allocate(
                    packageName, userId, processName, packageRevision, now());
            GuestSession staleRecovery = null;
            String key = processKey(packageName, userId, processName);
            if (session.state() == SessionState.READY || session.state() == SessionState.ACTIVE) {
                Bundle cached = brokerState.prepared(key);
                if (cached == null) throw new IllegalStateException("PREPARED_SPEC_MISSING");
                if (!session.packageRevision().equals(
                        cached.getString(RuntimeKeys.PACKAGE_REVISION, ""))) {
                    throw new IllegalStateException("PREPARED_SPEC_REVISION_MISMATCH");
                }
                // A persisted READY/ACTIVE session is only a broker-side lease.  After a
                // process death Android may recreate the declared Guest service with an empty
                // GuestRuntimeEnvironment while the old session record and prepared spec still
                // exist.  Do not return the cached spec until the newly bound Binder proves that
                // bindApplication/LoadedApk/Application bootstrap is actually READY.
                Bundle runtimeStatus = null;
                Throwable statusFailure = null;
                try {
                    runtimeStatus = callGuest(session.processSlot(), guest -> guestOperation(
                            guest, RuntimeOperationRequest.GUEST_RUNTIME_STATUS, new Bundle()));
                } catch (Throwable error) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                    statusFailure = error;
                }
                if (runtimeStatus != null
                        && "READY".equals(runtimeStatus.getString(RuntimeKeys.STATUS, ""))) {
                    receiverCoordinator.bindSession(session);
                    Bundle out = new Bundle(cached);
                    out.putString(RuntimeKeys.STATUS, cached.getBoolean("frameworkDegraded", false)
                            ? "ALREADY_PREPARED_DEGRADED" : "ALREADY_PREPARED");
                    return out;
                }
                GuestSession observed = sessions.get(packageName, userId, processName);
                if (observed != null && (observed.state() == SessionState.READY
                        || observed.state() == SessionState.ACTIVE)) {
                    String reason = statusFailure == null
                            ? "GUEST_RUNTIME_STATUS_" + (runtimeStatus == null
                                    ? "MISSING" : runtimeStatus.getString(RuntimeKeys.STATUS, "UNKNOWN"))
                            : "GUEST_RUNTIME_STATUS_FAILED:" + statusFailure.getClass().getSimpleName();
                    sessions.markProcessDied(packageName, userId, processName,
                            observed.generation(), now(), reason);
                }
                session = sessions.get(packageName, userId, processName);
            }
            if (session.state() == SessionState.RECOVERING) {
                staleRecovery = session;
                session = sessions.beginRecovery(packageName, userId, processName, session.generation(), now());
            } else if (session.state() == SessionState.ALLOCATED) {
                session = sessions.transition(packageName, userId, processName, session.generation(),
                        SessionState.PREPARING, now(), "");
            } else {
                throw new IllegalStateException("SESSION_BUSY:" + session.state());
            }
            Bundle spec = makeSpec(input, session);
            systemServiceCoordinator.attach(session, spec);
            // Publish only the exact PREPARING generation so Application.onCreate() can use
            // standard Context APIs without recursively trying to prepare the same process.
            brokerState.putPrepared(key, new Bundle(spec));
            Bundle guestResult;
            try {
                guestResult = callGuest(session.processSlot(), guest -> guestOperation(
                        guest, RuntimeOperationRequest.PREPARE_GUEST, spec));
            } catch (Throwable error) {
                try {
                    brokerState.removePrepared(key);
                    if (staleRecovery != null) {
                        activityRuntime.invalidate(staleRecovery);
                        serviceCoordinator.invalidate(staleRecovery);
                        receiverCoordinator.stopSession(staleRecovery,
                                "ORDERED_RECEIVER_RECOVERY_FAILED");
                        providerResources.stopSession(staleRecovery);
                    }
                    systemServiceCoordinator.stop(session);
                } finally {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                }
                throw error;
            }
            String guestStatus = guestResult.getString(RuntimeKeys.STATUS, "FAILED");
            boolean degraded = "DEGRADED".equals(guestStatus) || "ALREADY_DEGRADED".equals(guestStatus);
            if (!"READY".equals(guestStatus) && !"ALREADY_READY".equals(guestStatus) && !degraded) {
                sessions.transition(packageName, userId, processName, session.generation(), SessionState.FAILED,
                        now(), guestResult.getString(RuntimeKeys.ERROR_TYPE, "GUEST_PREPARE_FAILED"));
                if (staleRecovery != null) {
                    activityRuntime.invalidate(staleRecovery);
                    serviceCoordinator.invalidate(staleRecovery);
                    receiverCoordinator.stopSession(staleRecovery,
                            "ORDERED_RECEIVER_RECOVERY_FAILED");
                    providerResources.stopSession(staleRecovery);
                }
                systemServiceCoordinator.stop(session);
                brokerState.removePrepared(key);
                return guestResult;
            }
            if (staleRecovery != null) {
                componentRecoveryCoordinator.recover(staleRecovery, session, spec);
            }
            GuestSession ready = sessions.transition(packageName, userId, processName, session.generation(),
                    SessionState.READY, now(), "");
            Bundle cachedSpec = new Bundle(spec);
            cachedSpec.putBoolean("frameworkDegraded", degraded);
            brokerState.putPrepared(key, cachedSpec);
            receiverCoordinator.bindSession(ready);
            Bundle out = new Bundle(spec);
            out.putAll(guestResult);
            out.putString(RuntimeKeys.STATUS, degraded ? "PREPARED_DEGRADED" : "PREPARED");
            out.putString("sessionState", ready.state().name());
            return out;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure(error);
        }
    }

    private static Bundle guestOperation(IGuestProcess guest, String operation, Bundle payload)
            throws Exception {
        return RuntimeOperationTransport.toLegacyBundle(
                RuntimeOperationTransport.execute(guest, operation, payload));
    }

    private static void restoreTargetSessionIdentity(Bundle target, Bundle prepared,
                                                     GuestSession session) {
        target.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        target.putLong(RuntimeKeys.GENERATION, session.generation());
        target.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        target.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        copyString(target, prepared, RuntimeKeys.APK_PATH);
        copyString(target, prepared, RuntimeKeys.APK_SHA256);
        copyString(target, prepared, RuntimeKeys.BASE_APK_SHA256);
        copyLong(target, prepared, RuntimeKeys.APK_VERSION_CODE);
        copyString(target, prepared, RuntimeKeys.PACKAGE_REVISION);
        copyString(target, prepared, RuntimeKeys.NATIVE_LIBRARY_DIR);
        copyString(target, prepared, RuntimeKeys.NATIVE_ABI);
        copyString(target, prepared, RuntimeKeys.NATIVE_GUEST_TRUST);
        copyString(target, prepared, RuntimeKeys.NATIVE_EXECUTION_MODE);
        copyString(target, prepared, RuntimeKeys.APPLICATION_CLASS);
        copyString(target, prepared, RuntimeKeys.DATA_ROOT);
        if (prepared.containsKey(RuntimeKeys.PERMISSIONS)) {
            target.putStringArrayList(RuntimeKeys.PERMISSIONS,
                    prepared.getStringArrayList(RuntimeKeys.PERMISSIONS));
        }
        if (prepared.containsKey(RuntimeKeys.PACKAGE_STATE)) {
            target.putParcelable(RuntimeKeys.PACKAGE_STATE,
                    prepared.getParcelable(RuntimeKeys.PACKAGE_STATE));
        }
        if (prepared.containsKey(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER)) {
            target.putBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER,
                    prepared.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER));
        }
        if (prepared.containsKey(RuntimeKeys.RUNTIME_BROKER_BINDER)) {
            target.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER,
                    prepared.getBinder(RuntimeKeys.RUNTIME_BROKER_BINDER));
        }
    }

    private static void copyString(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putString(key, source.getString(key, ""));
    }

    private static void copyLong(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putLong(key, source.getLong(key));
    }

    private void stopMismatchedRevisionSessions(String packageName, int userId,
                                                String requestedRevision) {
        java.util.List<GuestSession> existing = sessions.getAll(packageName, userId);
        for (GuestSession session : SessionRevisionPolicy.mismatchedLiveSessions(
                existing, requestedRevision)) {
            stopSession(session);
        }
        activityRuntime.clearMismatchedRevision(userId, packageName, requestedRevision);
    }

    private synchronized void stopGuestInternal(String packageName, int userId) {
        java.util.List<GuestSession> active = new ArrayList<>(sessions.getAll(packageName, userId));
        for (GuestSession session : active) stopSession(session);
        isolatedProcessCoordinator.stopGuest(packageName, userId);
        receiverCoordinator.invalidateInstance(packageName, userId,
                "ORDERED_RECEIVER_INSTANCE_STOPPED");
        providerResources.invalidateInstance(packageName, userId);
        activityRuntime.clearPackageInstance(userId, packageName);
    }

    private void stopSession(GuestSession original) {
        GuestSession session = original;
        try {
            if (session.state() != SessionState.STOPPING
                    && session.state() != SessionState.STOPPED
                    && session.state() != SessionState.FAILED) {
                session = sessions.transition(session.packageName(), session.virtualUserId(), session.processName(),
                        session.generation(), SessionState.STOPPING, now(), "");
                final GuestSession stopping = session;
                guestConnections.callWithTimeout(session.processSlot(), guest -> {
                    guest.shutdown(stopping.sessionId(), stopping.generation());
                    Bundle out = new Bundle();
                    out.putString(RuntimeKeys.STATUS, "STOPPED");
                    return out;
                }, 10_000L);
                sessions.transition(session.packageName(), session.virtualUserId(), session.processName(),
                        session.generation(), SessionState.STOPPED, now(), "");
            }
        } catch (Throwable error) {
            try {
                GuestSession current = sessions.get(original.packageName(), original.virtualUserId(), original.processName());
                if (current != null && current.state() == SessionState.STOPPING
                        && current.state().canTransitionTo(SessionState.STOPPED)) {
                    GuestSession stopped = sessions.transition(current.packageName(), current.virtualUserId(),
                            current.processName(), current.generation(), SessionState.STOPPED, now(),
                            String.valueOf(error.getMessage()));
                    RuntimeEventLog.event("GUEST_STOP_FORCED", sessionBundle(stopped, "STOPPED"));
                } else if (current != null && current.state().canTransitionTo(SessionState.FAILED)) {
                    sessions.transition(current.packageName(), current.virtualUserId(),
                            current.processName(), current.generation(), SessionState.FAILED, now(),
                            String.valueOf(error.getMessage()));
                }
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
        } finally {
            brokerState.removePrepared(processKey(original.packageName(), original.virtualUserId(), original.processName()));
            receiverCoordinator.stopSession(original, "ORDERED_RECEIVER_SESSION_STOPPED");
            activityRuntime.invalidate(original);
            serviceCoordinator.stopSession(original);
            providerResources.stopSession(original);
            if (systemServiceCoordinator != null) systemServiceCoordinator.stop(original);
            releaseGuestConnection(original.processSlot());
        }
    }

    private Bundle makeSpec(Bundle input, GuestSession session) throws Exception {
        Bundle spec = new Bundle(input);
        spec.putInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        spec.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        spec.putLong(RuntimeKeys.GENERATION, session.generation());
        spec.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        spec.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        spec.putString(RuntimeKeys.PACKAGE_REVISION, session.packageRevision());
        spec.putInt(RuntimeKeys.VIRTUAL_UID, uidRegistry().uidFor(session.packageName(), session.virtualUserId()));
        File dataRoot = new File(getFilesDir(), "instances/u" + session.virtualUserId() + "/" + safe(session.packageName()));
        if (!dataRoot.isDirectory() && !dataRoot.mkdirs() && !dataRoot.isDirectory()) throw new IllegalStateException("Cannot create Guest instance root");
        spec.putString(RuntimeKeys.DATA_ROOT, dataRoot.getCanonicalPath());
        return spec;
    }

    private void validateInput(Bundle input) throws Exception {
        int protocol = input.getInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        if (!RuntimeProtocol.isCompatible(protocol)) throw new IllegalArgumentException("UNSUPPORTED_PROTOCOL:" + protocol);
        String packageName = required(input, RuntimeKeys.PACKAGE_NAME);
        if (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid package name");
        int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (userId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String processName = processName(input, packageName);
        if (!processName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+(\\:[A-Za-z0-9_.]+)?")) {
            throw new IllegalArgumentException("Invalid process name");
        }
        input.putString(RuntimeKeys.PROCESS_NAME, processName);
        File apk = new File(required(input, RuntimeKeys.APK_PATH)).getCanonicalFile();
        File privateRoot = getFilesDir().getCanonicalFile();
        if (!apk.isFile()) throw new IllegalArgumentException("APK file is missing");
        if (!apk.toPath().startsWith(privateRoot.toPath())) throw new SecurityException("APK path is outside app-private storage");
        long apkVersionCode = input.getLong(RuntimeKeys.APK_VERSION_CODE, -1L);
        String apkSha256 = required(input, RuntimeKeys.APK_SHA256);
        String baseApkSha256 = input.getString(RuntimeKeys.BASE_APK_SHA256, apkSha256);
        ArrayList<String> splitNames = optionalStringList(input, RuntimeKeys.SPLIT_NAMES);
        ArrayList<String> splitTypes = optionalStringList(input, RuntimeKeys.SPLIT_TYPES);
        ArrayList<String> splitConfigFor = optionalStringList(input, RuntimeKeys.SPLIT_CONFIG_FOR);
        ArrayList<String> splitUses = optionalStringList(input, RuntimeKeys.SPLIT_USES);
        ArrayList<String> splitPaths = optionalStringList(input, RuntimeKeys.SPLIT_PATHS);
        ArrayList<String> splitSha256s = optionalStringList(input, RuntimeKeys.SPLIT_SHA256S);
        int splitCount = splitNames.size();
        if (splitCount > 255) throw new IllegalArgumentException("Too many split APKs");
        if (splitTypes.size() != splitCount || splitConfigFor.size() != splitCount
                || splitUses.size() != splitCount || splitPaths.size() != splitCount
                || splitSha256s.size() != splitCount) {
            throw new IllegalArgumentException("Split metadata arrays must have identical sizes");
        }
        java.util.Set<String> uniqueSplitNames = new java.util.HashSet<>();
        java.util.Set<String> uniqueSplitPaths = new java.util.HashSet<>();
        ArrayList<PackageRevisionSetVerifier.Artifact> splitArtifacts = new ArrayList<>();
        ArrayList<String> canonicalSplitPaths = new ArrayList<>();
        for (int index = 0; index < splitCount; index++) {
            String splitName = splitNames.get(index);
            if (splitName == null || splitName.trim().isEmpty() || !uniqueSplitNames.add(splitName)) {
                throw new IllegalArgumentException("Split names must be non-empty and unique");
            }
            File splitFile = new File(splitPaths.get(index)).getCanonicalFile();
            if (!splitFile.isFile()) throw new IllegalArgumentException("Split APK file is missing: " + splitName);
            if (!splitFile.toPath().startsWith(privateRoot.toPath())) {
                throw new SecurityException("Split APK path is outside app-private storage: " + splitName);
            }
            if (!uniqueSplitPaths.add(splitFile.getCanonicalPath())) {
                throw new IllegalArgumentException("Split APK paths must be unique");
            }
            canonicalSplitPaths.add(splitFile.getCanonicalPath());
            splitArtifacts.add(new PackageRevisionSetVerifier.Artifact(
                    splitName, splitTypes.get(index), splitConfigFor.get(index), splitUses.get(index),
                    splitFile, splitSha256s.get(index)));
        }
        PackageRevision revision = PackageRevisionSetVerifier.verify(
                apk, baseApkSha256, splitArtifacts, apkVersionCode, apkSha256);
        input.putString(RuntimeKeys.APK_PATH, apk.getCanonicalPath());
        input.putString(RuntimeKeys.BASE_APK_SHA256, baseApkSha256.toLowerCase(java.util.Locale.ROOT));
        input.putStringArrayList(RuntimeKeys.SPLIT_PATHS, canonicalSplitPaths);
        input.putString(RuntimeKeys.APK_SHA256, revision.apkSha256());
        input.putLong(RuntimeKeys.APK_VERSION_CODE, revision.versionCode());
        input.putString(RuntimeKeys.PACKAGE_REVISION, revision.canonical());
        String nativeDir = input.getString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
        if (!nativeDir.trim().isEmpty()) {
            File nativeFile = new File(nativeDir).getCanonicalFile();
            if (!nativeFile.toPath().startsWith(privateRoot.toPath())) throw new SecurityException("Native library path is outside app-private storage");
        }
        boolean containsNativeCode = input.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT,
                !nativeDir.trim().isEmpty());
        String nativeTrust = NativeGuestPolicyContract.normalizeTrust(
                input.getString(RuntimeKeys.NATIVE_GUEST_TRUST, ""));
        String nativeMode = input.getString(RuntimeKeys.NATIVE_EXECUTION_MODE,
                NativeGuestPolicyContract.executionMode(containsNativeCode));
        NativeGuestPolicyContract.requireAllowed(
                containsNativeCode, nativeTrust, nativeMode, nativeDir);
        input.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, containsNativeCode);
        input.putString(RuntimeKeys.NATIVE_GUEST_TRUST, nativeTrust);
        input.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, nativeMode);
    }

    private static ArrayList<String> optionalStringList(Bundle input, String key) {
        ArrayList<String> values = input.getStringArrayList(key);
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private Bundle unregisterProviderObserver(Bundle request, String requestedPackage, int requestedUser) {
        ObserverCaller caller = requireObserverCaller(request, requestedPackage, requestedUser);
        ProviderObserverRegistry.Entry removed = observerRuntime.unregister(
                required(request, RuntimeKeys.OBSERVER_ID), caller.instanceId, caller.session.sessionId(),
                caller.session.generation());
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "PROVIDER_OBSERVER_UNREGISTERED");
        out.putString(RuntimeKeys.OBSERVER_ID, removed.id());
        return out;
    }

    private Bundle notifyProviderObservers(Bundle request, String requestedPackage, int requestedUser) {
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

    private GuestSession sessionById(String sessionId, long generation) {
        for (GuestSession candidate : sessions.snapshot()) {
            if (candidate.sessionId().equals(sessionId) && candidate.generation() == generation) return candidate;
        }
        return null;
    }

    private GuestSession latestSessionById(String sessionId) {
        GuestSession latest = null;
        for (GuestSession candidate : sessions.snapshot()) {
            if (!candidate.sessionId().equals(sessionId)
                    || candidate.state() == SessionState.STOPPED
                    || candidate.state() == SessionState.FAILED) continue;
            if (latest == null || candidate.generation() > latest.generation()) latest = candidate;
        }
        return latest;
    }
    private static void requireProviderTargetSession(GuestSession session,
            ProviderAuthorityRegistry.Entry target, Bundle request) {
        if (!RuntimePreparingSessionPolicy.isOperational(session, request, false)) {
            throw new IllegalStateException("PROVIDER_TARGET_SESSION_NOT_READY");
        }
        if (!ownerKey(session.packageName(), session.virtualUserId()).equals(target.instanceId())
                || !session.processName().equals(target.processName())) {
            throw new SecurityException("PROVIDER_AUTHORITY_SESSION_MISMATCH");
        }
    }

    private static void requireCursorTargetSession(GuestSession session,
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

    private UriGrantRegistry.Authorization beginUriGrantAuthorization(Bundle request, String callerInstance,
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

    private ProviderAccess providerAccess(BrokerProviderRuntime.OperationRoute route, Bundle request,
                                          GuestSession targetSession) {
        String callerSessionId = request.getString(RuntimeKeys.CALLER_SESSION_ID, "");
        long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION, -1);
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

    private void validateCursorRequestIdentity(Bundle request, String requestedPackage, int requestedUser,
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
        GuestSession caller = sessionById(callerSessionId, callerGeneration);
        if (!RuntimePreparingSessionPolicy.isOperational(caller, request, true)
                || !ownerKey(caller.packageName(), caller.virtualUserId()).equals(lease.callerInstance())) {
            throw new SecurityException("CURSOR_CALLER_SESSION_NOT_READY");
        }
        // Authorization is fixed when the Cursor lease is issued. Revocation blocks new opens;
        // the existing lease remains bounded by its own TTL and Session/generation lifecycle.
    }

    private static ProviderBatchRuntime.BatchException findBatchException(Throwable error) {
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

    private static boolean isProviderCursorOperation(String operation) {
        return ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation);
    }

    private static void requireFileTargetSession(GuestSession session,
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

    private void validateFileRequestIdentity(Bundle request, String requestedPackage, int requestedUser,
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
        GuestSession caller = sessionById(callerSessionId, callerGeneration);
        if (!RuntimePreparingSessionPolicy.isOperational(caller, request, true)
                || !ownerKey(caller.packageName(), caller.virtualUserId()).equals(lease.callerInstance())) {
            throw new SecurityException("PROVIDER_FILE_CALLER_SESSION_NOT_READY");
        }
        // A delivered file descriptor is an independent OS capability. Revocation blocks new opens;
        // Broker/Guest retained copies are still bounded by Lease TTL and Session/generation cleanup.
    }

    private void purgeExpiredResources() {
        purgeExpiredResources(now());
    }

    private void purgeExpiredResources(long nowMs) {
        providerResources.purgeExpired(nowMs);
        receiverCoordinator.purgeExpired();
        serviceCoordinator.purgeExpiredForeground();
        isolatedProcessCoordinator.purgeExpiredForeground();
    }

    private Bundle callGuest(int slot, RuntimeGuestConnectionPool.GuestCall call) throws Exception {
        return guestConnections.call(slot, call);
    }

    private void releaseGuestConnection(int slot) {
        guestConnections.release(slot);
    }

    private void handleGuestDisconnect(int slot, String reason) {
        GuestSession affected;
        synchronized (this) {
            affected = sessions.markSlotDisconnected(slot, now(), reason);
            if (affected != null) {
                activityRuntime.processDisconnected(affected);
                serviceCoordinator.disconnectSession(affected);
                receiverCoordinator.disconnectSession(affected,
                        "ORDERED_RECEIVER_GUEST_DISCONNECTED:" + reason);
                RuntimeEventLog.event("GUEST_PROCESS_DISCONNECTED",
                        sessionBundle(affected, affected.state().name()));
            }
        }
        if (affected != null) {
            providerResources.disconnectSession(affected);
        }
    }

    private GuestSession findSession(String sessionId, long generation) {
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
        for (GuestSession session : sessions.snapshot()) {
            receiverCoordinator.stopSession(session, "ORDERED_RECEIVER_BROKER_DESTROYED");
            serviceCoordinator.stopSession(session);
            providerResources.stopSession(session);
        }
        if (guestConnections != null) guestConnections.close();
        receiverCoordinator.invalidateAll("ORDERED_RECEIVER_BROKER_DESTROYED");
        if (runtimePermissionCoordinator != null) runtimePermissionCoordinator.close();
        if (systemServiceCoordinator != null) systemServiceCoordinator.close();
        serviceCoordinator.close();
        isolatedProcessCoordinator.close();
        super.onDestroy();
    }

    private Bundle sessionBundle(GuestSession session, String status) {
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

    private VirtualUidRegistry uidRegistry() {
        VirtualUidRegistry registry = virtualUids;
        if (registry == null) throw new IllegalStateException("VIRTUAL_UID_REGISTRY_NOT_INITIALIZED");
        return registry;
    }

    private static boolean isPrepared(Bundle bundle) {
        String status = bundle.getString(RuntimeKeys.STATUS, "");
        return "PREPARED".equals(status) || "ALREADY_PREPARED".equals(status)
                || "PREPARED_DEGRADED".equals(status) || "ALREADY_PREPARED_DEGRADED".equals(status);
    }
    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private long now() { return clock.nowMillis(); }
    public static String ownerKey(String packageName, int userId) {
        return BrokerProviderRuntime.instanceId(packageName, userId);
    }
    private static String processKey(String packageName, int userId, String processName) {
        return ownerKey(packageName, userId) + ":" + processName;
    }
    private static String processName(Bundle bundle, String packageName) {
        String value = bundle == null ? "" : bundle.getString(RuntimeKeys.PROCESS_NAME, "");
        if (value == null || value.trim().isEmpty()) return packageName;
        value = value.trim();
        return value.startsWith(":") ? packageName + value : value;
    }

    private static Bundle failure(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return failure(root.getClass().getName(), String.valueOf(root.getMessage()));
    }
    private static Bundle failure(String type, String message) {
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
    private static final class ProviderAccess {
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
