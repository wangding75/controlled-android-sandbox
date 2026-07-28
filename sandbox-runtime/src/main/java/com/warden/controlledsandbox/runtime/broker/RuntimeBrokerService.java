package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.PackageServiceResult;

import com.warden.controlledsandbox.runtime.component.activity.ActivityTaskContractFailure;
import com.warden.controlledsandbox.runtime.component.activity.BrokerActivityRuntime;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity0;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity1;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity2;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity3;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity4;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity5;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity6;
import com.warden.controlledsandbox.runtime.component.activity.StubActivity7;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerReceiverRuntime;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService0;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService1;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService2;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService3;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService4;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService5;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService6;
import com.warden.controlledsandbox.runtime.guest.GuestProcessService7;
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.BrokerCursorRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerFileRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerObserverRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderBatchRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderLifecycleCoordinator;
import com.warden.controlledsandbox.runtime.provider.RuntimeProviderResourceCoordinator;
import com.warden.controlledsandbox.runtime.status.BrokerRuntimeStatusSource;


import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Central process allocator and route authority. Business/UI code does not own runtime state. */
public final class RuntimeBrokerService extends Service {
    private static final int SLOT_COUNT = 8;
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
    private final RuntimeReceiverCoordinator receiverCoordinator = new RuntimeReceiverCoordinator(
            sessions, brokerState, clock, tokenGenerator,
            this::prepareGuestInternal,
            this::sessionById,
            (processSlot, request) -> callGuest(processSlot, guest -> guest.invokeComponent(request)));
    private final BrokerActivityRuntime activityRuntime = new BrokerActivityRuntime(brokerState);
    private final RuntimeServiceCoordinator serviceCoordinator = new RuntimeServiceCoordinator(
            brokerState, (slot, request) -> callGuest(slot, guest -> guest.invokeComponent(request)));
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
                    (slot, request) -> callGuest(slot, guest -> guest.invokeComponent(request)));
    private final RuntimeStatusDispatcher runtimeStatusDispatcher = new RuntimeStatusDispatcher(
            clock,
            new BrokerRuntimeStatusSource(sessions, activityRuntime, serviceCoordinator, providerLifecycle,
                    providerRuntime, receiverCoordinator.lifecycle()),
            this::purgeExpiredResources,
            auditSink);
    private final ConcurrentMap<Integer, GuestConnection> guestConnections = new ConcurrentHashMap<>();

    @Override public void onCreate() {
        super.onCreate();
        activityRuntime.configureCheckpointStore(
                new File(new File(getFilesDir(), "runtime"), "activity-tasks.checkpoint").toPath());
        File registryFile = new File(new File(getFilesDir(), "runtime"), "virtual-uids.registry");
        virtualUids = new VirtualUidRegistry(registryFile.toPath());
        runtimePermissionCoordinator = new RuntimePermissionCoordinator(
                new RuntimePermissionPackageClient(this), this::permissionSession);
        systemServiceCoordinator = new RuntimeSystemServiceCoordinator(
                new RuntimeVirtualSystemServicePackageClient(this));
        componentRecoveryCoordinator = new RuntimeComponentRecoveryCoordinator(
                sessions, clock, activityRuntime, serviceCoordinator, receiverCoordinator,
                providerResources, systemServiceCoordinator);
    }
    private final IRuntimeBroker.Stub binder = new IRuntimeBroker.Stub() {
        @Override public Bundle prepareGuest(Bundle request) {
            CallerGuard.requireSameApplication();
            return RuntimeBrokerService.this.prepareGuestInternal(request);
        }

        @Override public Bundle launchActivity(Bundle request) {
            CallerGuard.requireSameApplication();
            Bundle prepared = RuntimeBrokerService.this.prepareGuestInternal(request);
            if (!isPrepared(prepared)) return prepared;
            String issuedRouteToken = "";
            try {
                String packageName = prepared.getString(RuntimeKeys.PACKAGE_NAME, "");
                int userId = prepared.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
                String processName = processName(prepared, packageName);
                GuestSession session = sessions.get(packageName, userId, processName);
                if (session == null) return failure("SESSION_NOT_FOUND", "Prepared session disappeared");
                String component = request == null ? "" : request.getString(RuntimeKeys.COMPONENT_CLASS, "");
                if (component.trim().isEmpty()) component = prepared.getString(RuntimeKeys.COMPONENT_CLASS, "");
                if (component.trim().isEmpty()) return failure("COMPONENT_MISSING", "No Guest Activity class supplied");

                Bundle transaction = activityRuntime.launch(session, component, prepared, request);
                issuedRouteToken = transaction.getString(RuntimeKeys.ROUTE_TOKEN, "");
                Intent launch = new Intent(RuntimeBrokerService.this, activityClassFor(session.processSlot()));
                launch.addFlags(transaction.getInt(RuntimeKeys.ACTIVITY_FLAGS, Intent.FLAG_ACTIVITY_NEW_TASK));
                launch.putExtra(RuntimeKeys.ROUTE_TOKEN, issuedRouteToken);
                launch.putExtra(RuntimeKeys.SESSION_ID, session.sessionId());
                launch.putExtra(RuntimeKeys.GENERATION, session.generation());
                launch.putExtra(RuntimeKeys.ACTIVITY_TOKEN,
                        transaction.getString(RuntimeKeys.ACTIVITY_TOKEN, ""));
                startActivity(launch);

                Bundle out = sessionBundle(session, "LAUNCH_REQUESTED");
                out.putAll(transaction);
                return out;
            } catch (Throwable error) {
                if (!issuedRouteToken.isEmpty()) activityRuntime.launchFailed(issuedRouteToken);
                return failure(error);
            }
        }

        @Override public Bundle invokeComponent(Bundle request) {
            CallerGuard.requireSameApplication();
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
                            requestedUser, requestedTarget, authorization == null ? null : authorization::allows, now());
                    if ("URI_GRANT".equals(providerRoute.permissionBasis())) {
                        if (authorization == null) throw new SecurityException("URI_GRANT_CALLER_SESSION_REQUIRED");
                        uriGrantAuthorizationResult = authorization.commit(now());
                    }
                    ProviderAuthorityRegistry.Entry target = providerRoute.entry();
                    session = sessionById(target.sessionId(), target.generation());
                    requireProviderTargetSession(session, target);
                } else if (isProviderCursorOperation(operation)) {
                    cursorLease = cursorRuntime.require(required(request, RuntimeKeys.CURSOR_TOKEN), now());
                    validateCursorRequestIdentity(request, requestedPackage, requestedUser, cursorLease);
                    session = sessionById(cursorLease.targetSessionId(), cursorLease.targetGeneration());
                    requireCursorTargetSession(session, cursorLease);
                } else if (ComponentOperations.isProviderFileLeaseOperation(operation)) {
                    fileLease = fileRuntime.require(required(request, RuntimeKeys.FILE_TOKEN), now());
                    validateFileRequestIdentity(request, requestedPackage, requestedUser, fileLease);
                    session = sessionById(fileLease.targetSessionId(), fileLease.targetGeneration());
                    requireFileTargetSession(session, fileLease);
                } else {
                    String processName = processName(request, requestedPackage);
                    session = sessions.get(requestedPackage, requestedUser, processName);
                    if (session == null || (session.state() != SessionState.READY
                            && session.state() != SessionState.ACTIVE)) {
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
                Bundle call = new Bundle(base);
                call.putAll(request);
                call.putString(RuntimeKeys.PACKAGE_NAME, packageName);
                call.putInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
                call.putString(RuntimeKeys.PROCESS_NAME, processName);
                if (providerRoute != null) {
                    call.putString(ComponentOperations.AUTHORITY, providerRoute.authority());
                    call.putString(RuntimeKeys.COMPONENT_CLASS, providerRoute.entry().component());
                    call.putString(RuntimeKeys.URI, providerRoute.uri());
                }
                final GuestSession activeSession = session;
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
                        result = callGuest(session.processSlot(), guest -> guest.invokeComponent(call));
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
                    throw error;
                }
            } catch (Throwable error) {
                cursorRuntime.rollbackQuery(cursorQueryReservation);
                if (cursorPageReservation != null) cursorRuntime.abort(cursorPageReservation.token());
                fileRuntime.rollbackOpen(fileOpenReservation);
                if (fileCloseReservation != null) fileRuntime.abort(fileCloseReservation.token());
                if (providerRoute != null && !providerAuditFinalized) {
                    providerRuntime.failOperation(providerRoute, error, now());
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
            CallerGuard.requireSameApplication();
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
                return failure(error);
            }
        }

        @Override public Bundle revokeUriPermission(Bundle request) {
            CallerGuard.requireSameApplication();
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
                return failure(error);
            }
        }

        @Override public Bundle consumeRoute(String token, String sessionId, long generation) {
            CallerGuard.requireSameApplication();
            try {
                GuestSession current = findSession(sessionId, generation);
                Bundle payload = activityRuntime.consume(token, current);
                if (current.state() == SessionState.READY) {
                    current = sessions.transition(current.packageName(), current.virtualUserId(), current.processName(),
                            generation, SessionState.ACTIVE, now(), "");
                }
                payload.putString(RuntimeKeys.STATUS, "ROUTE_GRANTED");
                return payload;
            } catch (Throwable error) {
                activityRuntime.launchFailed(token);
                return failure(error);
            }
        }

        @Override public Bundle activityEvent(Bundle request) {
            CallerGuard.requireSameApplication();
            try {
                if (request == null) throw new IllegalArgumentException("request is required");
                GuestSession current = findSession(required(request, RuntimeKeys.SESSION_ID),
                        request.getLong(RuntimeKeys.GENERATION, -1));
                return activityRuntime.event(current, request);
            } catch (Throwable error) {
                return failure(error);
            }
        }

        @Override public ActivityTaskResult activityTaskOperation(ActivityTaskRequest request) {
            CallerGuard.requireSameApplication();
            try {
                if (request == null) throw new IllegalArgumentException("request is required");
                GuestSession current = findSession(request.sessionId(), request.generation());
                return activityRuntime.taskOperation(current, request);
            } catch (Throwable error) {
                return ActivityTaskContractFailure.from(request, error);
            }
        }

        @Override public Bundle sessionStatus(String packageName, int virtualUserId) {
            CallerGuard.requireSameApplication();
            GuestSession session = sessions.get(packageName, virtualUserId, packageName);
            return session == null ? failure("SESSION_NOT_FOUND", "No active session") : sessionBundle(session, session.state().name());
        }

        @Override public PackageServiceResult requestRuntimePermission(String sessionId,
                long generation, String permission, int requestCode) {
            CallerGuard.requireSameApplication();
            return runtimePermissionCoordinator.request(sessionId, generation, permission, requestCode);
        }

        @Override public PackageServiceResult reportRuntimePermissionResult(String sessionId,
                long generation, String permission, int requestCode, boolean hostGranted,
                String reason) {
            CallerGuard.requireSameApplication();
            return runtimePermissionCoordinator.report(sessionId, generation, permission, requestCode,
                    hostGranted, reason);
        }

        @Override public RuntimeStatusResult runtimeStatusV2(RuntimeStatusRequest request) {
            CallerGuard.requireSameApplication();
            return runtimeStatusDispatcher.dispatch(request);
        }

        @Override public Bundle runtimeStatus() {
            CallerGuard.requireSameApplication();
            RuntimeStatusRequest legacyRequest = new RuntimeStatusRequest(
                    RuntimeProtocol.CURRENT, "legacy-runtime-status");
            return RuntimeStatusLegacyAdapter.toBundle(runtimeStatusDispatcher.dispatch(legacyRequest));
        }

        @Override public void stopGuest(String packageName, int virtualUserId) {
            CallerGuard.requireSameApplication();
            RuntimeBrokerService.this.stopGuestInternal(packageName, virtualUserId);
        }
    };

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
                receiverCoordinator.bindSession(session);
                Bundle out = new Bundle(cached);
                out.putString(RuntimeKeys.STATUS, cached.getBoolean("frameworkDegraded", false)
                        ? "ALREADY_PREPARED_DEGRADED" : "ALREADY_PREPARED");
                return out;
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
            Bundle guestResult;
            try {
                guestResult = callGuest(session.processSlot(), guest -> guest.prepareGuest(spec));
            } catch (Throwable error) {
                if (staleRecovery != null) {
                    activityRuntime.invalidate(staleRecovery);
                    serviceCoordinator.invalidate(staleRecovery);
                    receiverCoordinator.stopSession(staleRecovery,
                            "ORDERED_RECEIVER_RECOVERY_FAILED");
                    providerResources.stopSession(staleRecovery);
                }
                systemServiceCoordinator.stop(session);
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
            return failure(error);
        }
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
                callGuest(session.processSlot(), guest -> {
                    guest.shutdown(stopping.sessionId(), stopping.generation());
                    Bundle out = new Bundle();
                    out.putString(RuntimeKeys.STATUS, "STOPPED");
                    return out;
                });
                sessions.transition(session.packageName(), session.virtualUserId(), session.processName(),
                        session.generation(), SessionState.STOPPED, now(), "");
            }
        } catch (Throwable error) {
            GuestSession current = sessions.get(original.packageName(), original.virtualUserId(), original.processName());
            if (current != null && current.state().canTransitionTo(SessionState.FAILED)) {
                sessions.transition(current.packageName(), current.virtualUserId(), current.processName(),
                        current.generation(), SessionState.FAILED, now(), String.valueOf(error.getMessage()));
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

    private static void requireProviderTargetSession(GuestSession session, ProviderAuthorityRegistry.Entry target) {
        if (session == null || (session.state() != SessionState.READY && session.state() != SessionState.ACTIVE)) {
            throw new IllegalStateException("PROVIDER_TARGET_SESSION_NOT_READY");
        }
        if (!ownerKey(session.packageName(), session.virtualUserId()).equals(target.instanceId())
                || !session.processName().equals(target.processName())) {
            throw new SecurityException("PROVIDER_AUTHORITY_SESSION_MISMATCH");
        }
    }

    private static void requireCursorTargetSession(GuestSession session, BrokerCursorRuntime.Lease lease) {
        if (session == null || (session.state() != SessionState.READY && session.state() != SessionState.ACTIVE)) {
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
            if (caller == null || (caller.state() != SessionState.READY && caller.state() != SessionState.ACTIVE)) {
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
        if (caller == null || (caller.state() != SessionState.READY && caller.state() != SessionState.ACTIVE)
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

    private static void requireFileTargetSession(GuestSession session, BrokerFileRuntime.Lease lease) {
        if (session == null || (session.state() != SessionState.READY && session.state() != SessionState.ACTIVE)) {
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
        if (caller == null || (caller.state() != SessionState.READY && caller.state() != SessionState.ACTIVE)
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
    }

    private Bundle callGuest(int slot, GuestCall call) throws Exception {
        GuestConnection connection = requireGuestConnection(slot);
        try {
            return call.run(connection.requireGuest());
        } catch (Exception error) {
            if (!connection.isAlive()) handleGuestDisconnect(slot, connection, "BINDER_CALL_FAILED:" + error.getClass().getSimpleName());
            throw error;
        }
    }

    private GuestConnection requireGuestConnection(int slot) throws Exception {
        GuestConnection connection;
        synchronized (this) {
            connection = guestConnections.get(slot);
            if (connection != null && connection.isAlive()) return connection;
            if (connection == null) {
                connection = new GuestConnection(slot);
                guestConnections.put(slot, connection);
                Intent intent = new Intent(this, serviceClassFor(slot));
                if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    guestConnections.remove(slot);
                    throw new IllegalStateException("BIND_FAILED");
                }
            }
        }
        if (!connection.await(10, TimeUnit.SECONDS) || !connection.isAlive()) {
            handleGuestDisconnect(slot, connection, "BIND_TIMEOUT");
            throw new IllegalStateException("BIND_TIMEOUT");
        }
        return connection;
    }

    private void releaseGuestConnection(int slot) {
        GuestConnection connection;
        synchronized (this) { connection = guestConnections.remove(slot); }
        if (connection == null) return;
        connection.closing = true;
        connection.unlinkDeath();
        try { unbindService(connection); } catch (Exception ignored) { }
    }

    private void handleGuestDisconnect(int slot, GuestConnection source, String reason) {
        GuestSession affected = null;
        synchronized (this) {
            GuestConnection current = guestConnections.get(slot);
            if (current != source) return;
            guestConnections.remove(slot);
            if (!source.closing) {
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
        }
        if (affected != null) {
            providerResources.disconnectSession(affected);
        }
        source.unlinkDeath();
        try { unbindService(source); } catch (Exception ignored) { }
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
        Integer[] slots;
        synchronized (this) { slots = guestConnections.keySet().toArray(new Integer[0]); }
        for (int slot : slots) releaseGuestConnection(slot);
        receiverCoordinator.invalidateAll("ORDERED_RECEIVER_BROKER_DESTROYED");
        if (runtimePermissionCoordinator != null) runtimePermissionCoordinator.close();
        if (systemServiceCoordinator != null) systemServiceCoordinator.close();
        serviceCoordinator.close();
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

    private static Class<?> serviceClassFor(int slot) {
        switch (slot) {
            case 0: return GuestProcessService0.class;
            case 1: return GuestProcessService1.class;
            case 2: return GuestProcessService2.class;
            case 3: return GuestProcessService3.class;
            case 4: return GuestProcessService4.class;
            case 5: return GuestProcessService5.class;
            case 6: return GuestProcessService6.class;
            case 7: return GuestProcessService7.class;
            default: throw new IllegalArgumentException("Invalid process slot: " + slot);
        }
    }
    private static Class<?> activityClassFor(int slot) {
        switch (slot) {
            case 0: return StubActivity0.class;
            case 1: return StubActivity1.class;
            case 2: return StubActivity2.class;
            case 3: return StubActivity3.class;
            case 4: return StubActivity4.class;
            case 5: return StubActivity5.class;
            case 6: return StubActivity6.class;
            case 7: return StubActivity7.class;
            default: throw new IllegalArgumentException("Invalid process slot: " + slot);
        }
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



    private interface GuestCall { Bundle run(IGuestProcess guest) throws Exception; }

    private final class GuestConnection implements ServiceConnection, IBinder.DeathRecipient {
        final int slot;
        final CountDownLatch connected = new CountDownLatch(1);
        volatile IGuestProcess guest;
        volatile IBinder binderToken;
        volatile boolean closing;

        GuestConnection(int slot) { this.slot = slot; }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binderToken = service;
            guest = IGuestProcess.Stub.asInterface(service);
            try { service.linkToDeath(this, 0); }
            catch (Throwable error) {
                guest = null;
                binderToken = null;
            } finally {
                connected.countDown();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            guest = null;
            binderToken = null;
            connected.countDown();
            handleGuestDisconnect(slot, this, "SERVICE_DISCONNECTED");
        }

        @Override public void onBindingDied(ComponentName name) {
            guest = null;
            binderToken = null;
            connected.countDown();
            handleGuestDisconnect(slot, this, "BINDING_DIED");
        }

        @Override public void onNullBinding(ComponentName name) {
            guest = null;
            binderToken = null;
            connected.countDown();
            handleGuestDisconnect(slot, this, "NULL_BINDING");
        }

        @Override public void binderDied() {
            guest = null;
            binderToken = null;
            handleGuestDisconnect(slot, this, "BINDER_DIED");
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException { return connected.await(timeout, unit); }
        boolean isAlive() {
            IBinder token = binderToken;
            return guest != null && token != null && token.isBinderAlive();
        }
        IGuestProcess requireGuest() {
            IGuestProcess value = guest;
            if (value == null || !isAlive()) throw new IllegalStateException("GUEST_BINDER_DEAD");
            return value;
        }
        void unlinkDeath() {
            IBinder token = binderToken;
            if (token != null) {
                try { token.unlinkToDeath(this, 0); } catch (Throwable ignored) { }
            }
        }
    }
}
