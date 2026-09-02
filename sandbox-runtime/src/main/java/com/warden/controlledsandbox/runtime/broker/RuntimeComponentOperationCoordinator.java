package com.warden.controlledsandbox.runtime.broker;

import android.content.Intent;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.IGuestProcess;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.domain.component.provider.ProviderAuthorityRegistry;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.runtime.component.receiver.BrokerReceiverRuntime;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.BrokerCursorRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerFileRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerObserverRuntime;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderQueryCancellation;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderBatchRuntime;
import com.warden.controlledsandbox.runtime.provider.RuntimeProviderResourceCoordinator;
import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService.ProviderAccess;

final class RuntimeComponentOperationCoordinator {
    private final RuntimeBrokerService owner;
    private final SessionRegistry sessions;
    private final UriGrantRegistry uriGrants;
    private final BrokerStateStore brokerState;
    private final RuntimeIsolatedProcessCoordinator isolatedProcessCoordinator;
    private final RuntimeReceiverCoordinator receiverCoordinator;
    private final RuntimeServiceCoordinator serviceCoordinator;
    private final BrokerProviderRuntime providerRuntime;
    private final BrokerCursorRuntime cursorRuntime;
    private final BrokerProviderQueryCancellation queryCancellations;
    private final BrokerFileRuntime fileRuntime;
    private final BrokerObserverRuntime observerRuntime;
    private final RuntimeProviderResourceCoordinator providerResources;

    RuntimeComponentOperationCoordinator(RuntimeBrokerService owner) {
        this.owner = owner;
        this.sessions = owner.sessions;
        this.uriGrants = owner.uriGrants;
        this.brokerState = owner.brokerState;
        this.isolatedProcessCoordinator = owner.isolatedProcessCoordinator;
        this.receiverCoordinator = owner.receiverCoordinator;
        this.serviceCoordinator = owner.serviceCoordinator;
        this.providerRuntime = owner.providerRuntime;
        this.cursorRuntime = owner.cursorRuntime;
        this.queryCancellations = owner.queryCancellations;
        this.fileRuntime = owner.fileRuntime;
        this.observerRuntime = owner.observerRuntime;
        this.providerResources = owner.providerResources;
    }

    Bundle invoke(Bundle request) {
        CallerGuard.requireRuntimePeer(owner);
        ComponentInvocation invocation = null;
        try {
            if (request != null && ComponentOperations.FRAMEWORK_SERVICE_EVENT.equals(
                    request.getString(ComponentOperations.OPERATION, ""))) {
                // Lifecycle events are Broker-owned state commits, not Guest callbacks.  Consume
                // the one-shot descriptor after the Binder boundary so the Broker can retain the
                // complete Intent for START_REDELIVER_INTENT without echoing it back over Binder.
                RuntimeIntentWireCodec.materializePayloadForBroker(request);
            }
            invocation = prepareInvocation(request);
            if (invocation.immediate != null) return invocation.immediate;
            return invokePrepared(invocation);
        } catch (Throwable error) {
            return failureWithCleanup(invocation, error);
        } finally {
            if (invocation != null && invocation.queryCancellationId != null) {
                queryCancellations.close(invocation.queryCancellationId);
            }
        }
    }

    private ComponentInvocation prepareInvocation(Bundle request) throws Exception {
        if (request == null) throw new IllegalArgumentException("request is required");
        IsolatedProcessRoutePolicy.Match isolatedMatch = IsolatedProcessRoutePolicy.match(request);
        if (isolatedMatch != null) {
            owner.startService(new Intent(owner, RuntimeBrokerService.class));
            return ComponentInvocation.immediate(isolatedProcessCoordinator.invoke(request, isolatedMatch));
        }
        purgeExpiredResources();
        String operation = request.getString(ComponentOperations.OPERATION, "");
        ComponentOperations.requireKnownProviderOperation(operation);
        ComponentOperations.requireKnownServiceOperation(operation);
        String requestedPackage = required(request, RuntimeKeys.PACKAGE_NAME);
        int requestedUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (requestedUser < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String targetPackage = targetPackageForRequest(request, requestedPackage);
        boolean explicitForeignReceiver = (ComponentOperations.SEND_BROADCAST.equals(operation)
                || ComponentOperations.SEND_ORDERED_BROADCAST.equals(operation))
                && !request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                && !targetPackage.isEmpty() && !targetPackage.equals(requestedPackage);
        boolean foreignServiceTarget = ComponentOperations.isServiceOperation(operation)
                && !targetPackage.isEmpty() && !targetPackage.equals(requestedPackage);
        if ((foreignServiceTarget || explicitForeignReceiver)
                && owner.targetRequiresCompanion(targetPackage, requestedUser)) {
            GuestSession caller = callerSession(request, requestedPackage);
            VirtualPackageMetadata.Type type = foreignServiceTarget
                    ? VirtualPackageMetadata.Type.SERVICE : VirtualPackageMetadata.Type.RECEIVER;
            return ComponentInvocation.immediate(owner.routeForeignOperation(request, caller,
                    targetPackage, type, RuntimeOperationRequest.INVOKE_COMPONENT));
        }
        if (ComponentOperations.PROVIDER_OBSERVER_UNREGISTER.equals(operation)
                && owner.hasCrossAbiProviderObserver(
                        request.getString(RuntimeKeys.OBSERVER_ID, ""))) {
            GuestSession caller = callerSession(request, requestedPackage);
            return ComponentInvocation.immediate(
                    owner.routeForeignProviderObserverUnregister(request, caller));
        }
        if (ComponentOperations.isProviderOperation(operation)
                && !targetPackage.isEmpty() && !targetPackage.equals(requestedPackage)
                && owner.targetRequiresCompanion(targetPackage, requestedUser)) {
            GuestSession caller = callerSession(request, requestedPackage);
            return ComponentInvocation.immediate(owner.routeForeignProviderOperation(
                    request, caller, targetPackage, operation));
        }
        if (ComponentOperations.CREATE_PENDING_INTENT_SENDER.equals(operation)) {
            return ComponentInvocation.immediate(
                    createPendingIntentSender(request, requestedPackage, requestedUser));
        }
        if (ComponentOperations.isProviderOperation(operation)
                && !targetPackage.isEmpty() && !targetPackage.equals(requestedPackage)) {
            GuestSession caller = callerSession(request, requestedPackage);
            boolean ensureProviderReady = ComponentOperations.PREPARE_PROVIDER.equals(operation)
                    || ComponentOperations.PROVIDER_OBSERVER_REGISTER.equals(operation);
            request = prepareForeignTargetRequest(request, caller, targetPackage,
                    VirtualPackageMetadata.Type.PROVIDER, ensureProviderReady);
            requestedPackage = request.getString(RuntimeKeys.PACKAGE_NAME, targetPackage);
            requestedUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, caller.virtualUserId());
        }
        boolean foreignService = ComponentOperations.isServiceOperation(operation)
                && !targetPackage.isEmpty() && !targetPackage.equals(requestedPackage);
        boolean prepareForeignService = ComponentOperations.START_SERVICE.equals(operation)
                || ComponentOperations.START_FOREGROUND_SERVICE.equals(operation)
                || ComponentOperations.BIND_SERVICE.equals(operation)
                || ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(operation);
        if (foreignService) {
            GuestSession caller = callerSession(request, requestedPackage);
            request = prepareForeignTargetRequest(request, caller, targetPackage,
                    VirtualPackageMetadata.Type.SERVICE, prepareForeignService);
            requestedPackage = request.getString(RuntimeKeys.PACKAGE_NAME, targetPackage);
            requestedUser = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, caller.virtualUserId());
        }
        boolean foreignReceiver = ComponentOperations.SEND_BROADCAST.equals(operation)
                && !request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                && !targetPackage.isEmpty() && !targetPackage.equals(requestedPackage);
        if (ComponentOperations.PROVIDER_OBSERVER_UNREGISTER.equals(operation)) {
            return ComponentInvocation.immediate(
                    unregisterProviderObserver(request, requestedPackage, requestedUser));
        }
        if (ComponentOperations.PROVIDER_NOTIFY_CHANGE.equals(operation)) {
            return ComponentInvocation.immediate(
                    notifyProviderObservers(request, requestedPackage, requestedUser));
        }
        return new ComponentInvocation(request, operation, requestedPackage, requestedUser,
                targetPackage, foreignReceiver);
    }

    private Bundle invokePrepared(ComponentInvocation invocation) throws Throwable {
        selectSession(invocation);
        if (invocation.immediate != null) return invocation.immediate;
        Bundle early = dispatchEarlyBroadcast(invocation);
        if (early != null) return early;
        GuestSession session = invocation.session;
        String packageName = session.packageName();
        int userId = session.virtualUserId();
        String processName = session.processName();
        Bundle base = brokerState.prepared(processKey(packageName, userId, processName));
        if (base == null) throw new IllegalStateException("PREPARED_SPEC_MISSING");
        if (ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(invocation.operation)) {
            return frameworkServiceRoute(invocation);
        }
        if (ComponentOperations.FRAMEWORK_SERVICE_EVENT.equals(invocation.operation)) {
            return recordFrameworkServiceEvent(session, invocation.request);
        }
        if (isFrameworkOwnedExternalServiceOperation(invocation)) {
            invocation.request.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        }
        Bundle call = new Bundle(base);
        call.putAll(invocation.request);
        // Guest already owns the immutable package universe from PREPARE_GUEST. Keep the
        // operation edge compact; re-sending the Broker's cached projections recreates the
        // same Binder transaction overflow that the NBB/VA route avoids.
        call.remove(RuntimeKeys.PACKAGE_UNIVERSE);
        call.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        call.putInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
        call.putString(RuntimeKeys.PROCESS_NAME, processName);
        restoreTargetSessionIdentity(call, base, session);
        if (invocation.providerRoute != null) {
            call.putString(ComponentOperations.AUTHORITY, invocation.providerRoute.authority());
            call.putString(RuntimeKeys.COMPONENT_CLASS, invocation.providerRoute.entry().component());
            call.putString(RuntimeKeys.URI, invocation.providerRoute.uri());
        }
        invocation.cursorTargetSession = session;
        invocation.fileTargetSession = session;
        ProviderAccess access = invocation.providerRoute == null
                ? null : providerAccess(invocation.providerRoute, invocation.request, session);
        if (ComponentOperations.PROVIDER_OBSERVER_REGISTER.equals(invocation.operation)) {
            return registerObserver(invocation, access);
        }
        reserveTransport(invocation, call, access);
        return runReservedOperation(invocation, call, access);
    }

    private void selectSession(ComponentInvocation invocation) throws Exception {
        Bundle request = invocation.request;
        String operation = invocation.operation;
        String requestedPackage = invocation.requestedPackage;
        int requestedUser = invocation.requestedUser;
        boolean foreignService = ComponentOperations.isServiceOperation(operation)
                && !invocation.targetPackage.isEmpty()
                && !invocation.targetPackage.equals(requestedPackage);
        boolean prepareForeignService = ComponentOperations.START_SERVICE.equals(operation)
                || ComponentOperations.START_FOREGROUND_SERVICE.equals(operation)
                || ComponentOperations.BIND_SERVICE.equals(operation)
                || ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(operation);
        if (ComponentOperations.isProviderTransactionOperation(operation)) {
            if (request.getBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, false)) {
                String process = processName(request, requestedPackage);
                invocation.session = sessions.get(requestedPackage, requestedUser, process);
                if (!RuntimePreparingSessionPolicy.isOperational(invocation.session, request, false)) {
                    Bundle prepared = prepareGuestInternal(request);
                    if (!isPrepared(prepared)) {
                        invocation.immediate = prepared;
                        return;
                    }
                    invocation.session = sessions.get(requestedPackage, requestedUser, process);
                }
                if (invocation.session == null) {
                    throw new IllegalStateException("CROSS_ABI_PROVIDER_TARGET_SESSION_MISSING");
                }
                invocation.providerPreparationReservation = providerRuntime.reservePrepare(
                        request, invocation.session);
                String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, "");
                int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, -1);
                String callerInstance = ownerKey(callerPackage, callerUser);
                invocation.providerRoute = providerRuntime.routeRelayedOperation(request, operation,
                        callerInstance, requestedUser, ownerKey(requestedPackage, requestedUser), now());
                invocation.cursorTargetSession = invocation.session;
                invocation.fileTargetSession = invocation.session;
                return;
            }
            String callerInstance = providerRuntime.requireCallerInstance(request, requestedPackage,
                    requestedUser, owner::sessionById);
            String requestedTarget = ownerKey(requestedPackage, requestedUser);
            invocation.uriGrantAuthorization =
                    beginUriGrantAuthorization(request, callerInstance, requestedTarget);
            UriGrantRegistry.Authorization authorization = invocation.uriGrantAuthorization;
            String authorizationPackage = requestedPackage;
            int authorizationUser = requestedUser;
            invocation.providerRoute = providerRuntime.routeOperation(request, operation, callerInstance,
                    requestedUser, requestedTarget,
                    authorization == null ? null : authorization::allows,
                    permission -> ProviderDeclaredPermissionAuthorizer.allows(request, authorizationPackage,
                            authorizationUser, permission, this::sessionById, brokerState), now());
            if ("URI_GRANT".equals(invocation.providerRoute.permissionBasis())) {
                if (authorization == null) throw new SecurityException("URI_GRANT_CALLER_SESSION_REQUIRED");
                invocation.uriGrantAuthorizationResult = authorization.commit(now());
            }
            ProviderAuthorityRegistry.Entry target = invocation.providerRoute.entry();
            invocation.session = sessionById(target.sessionId(), target.generation());
            requireProviderTargetSession(invocation.session, target, request);
        } else if (isProviderCursorOperation(operation)) {
            invocation.cursorLease = cursorRuntime.require(
                    required(request, RuntimeKeys.CURSOR_TOKEN), now());
            validateCursorRequestIdentity(request, requestedPackage, requestedUser,
                    invocation.cursorLease);
            invocation.session = sessionById(invocation.cursorLease.targetSessionId(),
                    invocation.cursorLease.targetGeneration());
            requireCursorTargetSession(invocation.session, invocation.cursorLease, request);
        } else if (ComponentOperations.isProviderFileLeaseOperation(operation)) {
            invocation.fileLease = fileRuntime.require(required(request, RuntimeKeys.FILE_TOKEN), now());
            validateFileRequestIdentity(request, requestedPackage, requestedUser, invocation.fileLease);
            invocation.session = sessionById(invocation.fileLease.targetSessionId(),
                    invocation.fileLease.targetGeneration());
            requireFileTargetSession(invocation.session, invocation.fileLease, request);
        } else if (invocation.foreignReceiver) {
            invocation.session = callerSession(request, requestedPackage);
        } else {
            String processName = processName(request, requestedPackage);
            invocation.session = sessions.get(requestedPackage, requestedUser, processName);
            if (foreignService && !prepareForeignService && invocation.session == null) {
                invocation.immediate = failure("SERVICE_NOT_RUNNING",
                        "Foreign Service target is not running");
                return;
            }
            if (!RuntimePreparingSessionPolicy.isOperational(invocation.session, request, false)) {
                // ActivityThread may synchronously unregister a dynamic Receiver from
                // Activity.onDestroy while the Broker is already stopping this generation.
                // That callback is a cleanup operation, not a request to revive or rebind the
                // Guest.  Accept only the exact session/generation in a terminal transition;
                // every other non-operational call must still fail closed through prepareGuest.
                if (isTeardownReceiverCleanup(operation, request, invocation.session)) {
                    return;
                }
                Bundle prepared = prepareGuestInternal(request);
                if (!isPrepared(prepared)) {
                    invocation.immediate = prepared;
                    return;
                }
                invocation.session = sessions.get(requestedPackage, requestedUser, processName);
            }
        }
    }

    private static boolean isTeardownReceiverCleanup(String operation, Bundle request,
                                                      GuestSession session) {
        if (!ComponentOperations.UNREGISTER_RECEIVER.equals(operation) || session == null) {
            return false;
        }
        SessionState state = session.state();
        if (state != SessionState.STOPPING && state != SessionState.FAILED
                && state != SessionState.STOPPED) {
            return false;
        }
        return session.sessionId().equals(request.getString(RuntimeKeys.SESSION_ID, ""))
                && session.generation() == request.getLong(RuntimeKeys.GENERATION, -1L);
    }

    private Bundle dispatchEarlyBroadcast(ComponentInvocation invocation) throws Exception {
        Bundle request = invocation.request;
        if (ComponentOperations.SEND_BROADCAST.equals(invocation.operation)
                && !request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                && request.getString(RuntimeKeys.RECEIVER_ID, "").trim().isEmpty()) {
            return receiverCoordinator.dispatchManifestBroadcast(request, invocation.session);
        }
        if ((ComponentOperations.SEND_IMPLICIT_BROADCAST.equals(invocation.operation)
                || ComponentOperations.SEND_ORDERED_BROADCAST.equals(invocation.operation)
                || request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false))
                && request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                && request.getString(RuntimeKeys.RECEIVER_ID, "").trim().isEmpty()) {
            return receiverCoordinator.dispatchImplicitManifestBroadcast(request, invocation.session,
                    ComponentOperations.SEND_ORDERED_BROADCAST.equals(invocation.operation)
                            || request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false));
        }
        return null;
    }

    private Bundle frameworkServiceRoute(ComponentInvocation invocation) {
        GuestSession session = invocation.session;
        Bundle request = invocation.request;
        Bundle routed = new Bundle();
        routed.putString(RuntimeKeys.STATUS, "FRAMEWORK_SERVICE_ROUTE");
        routed.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        routed.putLong(RuntimeKeys.GENERATION, session.generation());
        routed.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        routed.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        routed.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        routed.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        routed.putString(RuntimeKeys.COMPONENT_CLASS, required(request, RuntimeKeys.COMPONENT_CLASS));
        routed.putString("frameworkServiceStubPackage", owner.getPackageName());
        routed.putString("frameworkServiceStubClass",
                RuntimeStubComponents.componentServiceClassFor(session.processSlot()).getName());
        return routed;
    }

    private Bundle registerObserver(ComponentInvocation invocation, ProviderAccess access) {
        if (access == null) throw new IllegalStateException("PROVIDER_OBSERVER_ACCESS_MISSING");
        GuestSession session = invocation.session;
        BrokerObserverRuntime.RegisterResult registration = observerRuntime.register(
                invocation.request, access.callerInstance, access.callerSessionId,
                access.callerGeneration, access.targetInstance, session.sessionId(),
                session.generation(), session.virtualUserId(),
                invocation.providerRoute.authority(), access.uri);
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
        if (invocation.uriGrantAuthorizationResult != null) {
            out.putBoolean(RuntimeKeys.URI_GRANT_CONSUMED_ONE_TIME,
                    invocation.uriGrantAuthorizationResult.oneTimeConsumed());
        }
        providerRuntime.completeOperation(invocation.providerRoute, out, now());
        invocation.providerAuditFinalized = true;
        return out;
    }

    private void reserveTransport(ComponentInvocation invocation, Bundle call, ProviderAccess access) {
        String operation = invocation.operation;
        if (ComponentOperations.PROVIDER_QUERY.equals(operation)) {
            if (access == null) throw new IllegalStateException("PROVIDER_QUERY_ACCESS_MISSING");
            GuestSession session = invocation.session;
            invocation.cursorQueryReservation = cursorRuntime.reserveQuery(access.callerInstance,
                    access.callerSessionId, access.callerGeneration, access.targetInstance,
                    session.packageName(), session.virtualUserId(), session.processName(),
                    session.sessionId(), session.generation(), access.uri, access.flags, now());
            call.putString(RuntimeKeys.CURSOR_TOKEN, invocation.cursorQueryReservation.token());
            call.putLong(RuntimeKeys.CURSOR_TTL_MS, BrokerCursorRuntime.LEASE_TTL_MS);
            String queryId = invocation.request.getString(RuntimeKeys.PROVIDER_QUERY_ID, "").trim();
            if (!queryId.isEmpty()) {
                invocation.queryCancellationId = queryId;
                BrokerProviderQueryCancellation.Handle cancellation = queryCancellations.open(
                        queryId, access.callerInstance, access.callerSessionId, access.callerGeneration,
                        access.targetInstance, session.sessionId(), session.generation());
                call.putBinder(RuntimeKeys.PROVIDER_QUERY_CANCEL_CHANNEL,
                        cancellation.channelBinder());
            }
        } else if (ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)) {
            if (invocation.cursorLease == null) throw new IllegalStateException("CURSOR_LEASE_MISSING");
            invocation.cursorPageReservation = cursorRuntime.reservePage(
                    invocation.cursorLease.token(), invocation.cursorLease.callerSessionId(),
                    invocation.cursorLease.callerGeneration(), invocation.cursorLease.targetSessionId(),
                    invocation.cursorLease.targetGeneration(),
                    invocation.request.getInt(RuntimeKeys.CURSOR_OFFSET, -1),
                    invocation.request.getLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE, -1),
                    invocation.request.getInt(RuntimeKeys.CURSOR_PAGE_SIZE, 64), now());
        } else if (ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation)) {
            if (invocation.cursorLease == null) throw new IllegalStateException("CURSOR_LEASE_MISSING");
            invocation.cursorTerminalReservation = cursorRuntime.reserveTerminal(
                    invocation.cursorLease.token(), invocation.cursorLease.callerSessionId(),
                    invocation.cursorLease.callerGeneration(), invocation.cursorLease.targetSessionId(),
                    invocation.cursorLease.targetGeneration(),
                    invocation.request.getLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE, -1), now());
        } else if (ComponentOperations.isProviderFileOpenOperation(operation)) {
            if (access == null) throw new IllegalStateException("PROVIDER_FILE_ACCESS_MISSING");
            String mode = ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE.equals(operation)
                    ? "r" : required(invocation.request, RuntimeKeys.PROVIDER_FILE_MODE);
            String mimeType = invocation.request.getString(RuntimeKeys.PROVIDER_MIME_TYPE, "");
            GuestSession session = invocation.session;
            invocation.fileOpenReservation = fileRuntime.reserveOpen(operation, access.callerInstance,
                    access.callerSessionId, access.callerGeneration, access.targetInstance,
                    session.packageName(), session.virtualUserId(), session.processName(),
                    session.sessionId(), session.generation(), access.uri, access.flags, mode, mimeType, now());
            call.putString(RuntimeKeys.FILE_TOKEN, invocation.fileOpenReservation.token());
            call.putLong(RuntimeKeys.FILE_TTL_MS, BrokerFileRuntime.LEASE_TTL_MS);
            call.putString(RuntimeKeys.PROVIDER_FILE_MODE, mode);
        } else if (ComponentOperations.PROVIDER_FILE_CLOSE.equals(operation)) {
            if (invocation.fileLease == null) throw new IllegalStateException("PROVIDER_FILE_LEASE_MISSING");
            invocation.fileCloseReservation = fileRuntime.reserveClose(invocation.fileLease.token(),
                    invocation.fileLease.callerSessionId(), invocation.fileLease.callerGeneration(),
                    invocation.fileLease.targetSessionId(), invocation.fileLease.targetGeneration(), now());
        }
    }

    private Bundle runReservedOperation(ComponentInvocation invocation, Bundle call,
                                        ProviderAccess access) throws Throwable {
        BrokerReceiverRuntime.Reservation receiverReservation = null;
        BrokerProviderRuntime.Reservation providerReservation =
                invocation.providerPreparationReservation;
        try {
            if (ComponentOperations.REGISTER_RECEIVER.equals(invocation.operation)) {
                receiverReservation = receiverCoordinator.reserveRegistration(
                        invocation.request, invocation.session);
            } else if (ComponentOperations.UNREGISTER_RECEIVER.equals(invocation.operation)) {
                receiverCoordinator.requireOwnedRegistration(invocation.request, invocation.session);
            } else if (ComponentOperations.PREPARE_PROVIDER.equals(invocation.operation)) {
                providerReservation = providerRuntime.reservePrepare(invocation.request, invocation.session);
            }
            Bundle result = dispatchComponent(invocation, call, access);
            validateBatchResult(invocation, result);
            addResultIdentity(invocation, result);
            if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                finishFailedResult(invocation, result, receiverReservation, providerReservation);
            } else {
                finishSuccessfulResult(invocation, result, receiverReservation, providerReservation);
            }
            return result;
        } catch (Throwable error) {
            rollbackNested(invocation, receiverReservation, providerReservation, error);
            throw error;
        }
    }

    private Bundle dispatchComponent(ComponentInvocation invocation, Bundle call, ProviderAccess access)
            throws Exception {
        if (ComponentOperations.PROVIDER_QUERY_CANCEL.equals(invocation.operation)) {
            if (access == null) throw new IllegalStateException("PROVIDER_QUERY_CANCEL_ACCESS_MISSING");
            String queryId = required(invocation.request, RuntimeKeys.PROVIDER_QUERY_ID);
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, queryCancellations.cancel(queryId,
                    access.callerSessionId, access.callerGeneration, invocation.session.sessionId(),
                    invocation.session.generation()));
            return result;
        }
        if (ComponentOperations.SEND_BROADCAST.equals(invocation.operation)
                && invocation.request.getString(RuntimeKeys.COMPONENT_CLASS, "").trim().isEmpty()
                && invocation.request.getString(RuntimeKeys.RECEIVER_ID, "").trim().isEmpty()) {
            return receiverCoordinator.dispatchDynamicBroadcast(invocation.request, invocation.session);
        }
        if (ComponentOperations.SET_SERVICE_FOREGROUND.equals(invocation.operation)
                && invocation.request.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, false)) {
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "FRAMEWORK_SERVICE_FOREGROUND");
            result.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
            result.putString(RuntimeKeys.COMPONENT_CLASS,
                    required(invocation.request, RuntimeKeys.COMPONENT_CLASS));
            return result;
        }
        return callGuest(invocation.session.processSlot(),
                guest -> guestOperation(guest, RuntimeOperationRequest.INVOKE_COMPONENT, call));
    }

    private boolean isFrameworkOwnedExternalServiceOperation(ComponentInvocation invocation) {
        if (!ComponentOperations.isServiceOperation(invocation.operation)
                || ComponentOperations.START_SERVICE.equals(invocation.operation)
                || ComponentOperations.START_FOREGROUND_SERVICE.equals(invocation.operation)
                || ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(invocation.operation)
                || ComponentOperations.FRAMEWORK_SERVICE_EVENT.equals(invocation.operation)
                || ComponentOperations.RECOVER_FRAMEWORK_SERVICE.equals(invocation.operation)) {
            return false;
        }
        String component = invocation.request.getString(RuntimeKeys.COMPONENT_CLASS, "");
        return !component.trim().isEmpty()
                && serviceCoordinator.isFrameworkOwned(invocation.session, component);
    }

    private void validateBatchResult(ComponentInvocation invocation, Bundle result) {
        if (!ComponentOperations.PROVIDER_APPLY_BATCH.equals(invocation.operation)) return;
        if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
            if (result.getInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, Integer.MIN_VALUE)
                    == Integer.MIN_VALUE) {
                result.putInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, -1);
            }
            return;
        }
        try {
            ProviderBatchRuntime.validateResult(result,
                    invocation.request.getInt(RuntimeKeys.PROVIDER_BATCH_COUNT, -1));
        } catch (ProviderBatchRuntime.BatchException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private void addResultIdentity(ComponentInvocation invocation, Bundle result) {
        GuestSession session = invocation.session;
        result.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        result.putLong(RuntimeKeys.GENERATION, session.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        result.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        if (invocation.uriGrantAuthorizationResult != null) {
            result.putBoolean(RuntimeKeys.URI_GRANT_CONSUMED_ONE_TIME,
                    invocation.uriGrantAuthorizationResult.oneTimeConsumed());
        }
    }

    private void finishFailedResult(ComponentInvocation invocation, Bundle result,
                                    BrokerReceiverRuntime.Reservation receiverReservation,
                                    BrokerProviderRuntime.Reservation providerReservation) {
        cursorRuntime.rollbackQuery(invocation.cursorQueryReservation);
        if (invocation.cursorPageReservation != null) {
            cursorRuntime.abort(invocation.cursorPageReservation.token());
            providerResources.closeCursorBestEffort(invocation.session,
                    invocation.cursorPageReservation.token());
        }
        if (invocation.cursorTerminalReservation != null) {
            cursorRuntime.completeTerminal(invocation.cursorTerminalReservation);
        }
        fileRuntime.rollbackOpen(invocation.fileOpenReservation);
        if (invocation.fileCloseReservation != null) {
            fileRuntime.abort(invocation.fileCloseReservation.token());
            providerResources.closeFileBestEffort(invocation.session,
                    invocation.fileCloseReservation.token());
        }
        if (invocation.providerRoute != null) {
            providerRuntime.completeOperation(invocation.providerRoute, result, now());
            invocation.providerAuditFinalized = true;
        }
        receiverCoordinator.rollbackRegistration(receiverReservation);
        providerRuntime.rollbackPrepare(providerReservation);
    }

    private void finishSuccessfulResult(ComponentInvocation invocation, Bundle result,
                                        BrokerReceiverRuntime.Reservation receiverReservation,
                                        BrokerProviderRuntime.Reservation providerReservation) {
        boolean deferredRecoveryForeground =
                ComponentOperations.SET_SERVICE_FOREGROUND.equals(invocation.operation)
                        && invocation.request.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, false)
                        && invocation.request.getBoolean(RuntimeKeys.SERVICE_RECOVERY, false);
        // ActivityThread Service callbacks commit their authoritative start record through
        // START_BEGIN/START events.  The initial Guest operation only requests that framework
        // transaction; applying it here as well increments the Broker startId twice and makes
        // the final callback fail SERVICE_START_ID_MISMATCH.
        boolean frameworkStartAlreadyRecorded =
                result.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, false)
                        && (ComponentOperations.START_SERVICE.equals(invocation.operation)
                        || ComponentOperations.START_FOREGROUND_SERVICE.equals(invocation.operation));
        if (!deferredRecoveryForeground && !frameworkStartAlreadyRecorded) {
            serviceCoordinator.applySuccessfulOperation(invocation.session, invocation.request, result);
        }
        if (ComponentOperations.UNREGISTER_RECEIVER.equals(invocation.operation)) {
            receiverCoordinator.commitUnregister(invocation.request, invocation.session);
        }
        if (ComponentOperations.PROVIDER_QUERY.equals(invocation.operation)) {
            BrokerCursorRuntime.Lease committed = cursorRuntime.commitQuery(
                    invocation.cursorQueryReservation, result, now());
            result.putString(RuntimeKeys.CURSOR_OWNER_SESSION_ID, committed.callerSessionId());
            result.putLong(RuntimeKeys.CURSOR_OWNER_GENERATION, committed.callerGeneration());
            result.putLong(RuntimeKeys.CURSOR_EXPIRES_AT, committed.expiresAtMs());
        } else if (ComponentOperations.PROVIDER_CURSOR_PAGE.equals(invocation.operation)) {
            BrokerCursorRuntime.Lease committed = cursorRuntime.commitPage(
                    invocation.cursorPageReservation, result, now());
            result.putString(RuntimeKeys.CURSOR_OWNER_SESSION_ID, committed.callerSessionId());
            result.putLong(RuntimeKeys.CURSOR_OWNER_GENERATION, committed.callerGeneration());
        } else if (invocation.cursorTerminalReservation != null) {
            cursorRuntime.completeTerminal(invocation.cursorTerminalReservation);
        }
        if (invocation.fileOpenReservation != null) {
            BrokerFileRuntime.Lease committed = fileRuntime.commitOpen(
                    invocation.fileOpenReservation, result, now());
            result.putString(RuntimeKeys.FILE_OWNER_SESSION_ID, committed.callerSessionId());
            result.putLong(RuntimeKeys.FILE_OWNER_GENERATION, committed.callerGeneration());
            result.putLong(RuntimeKeys.FILE_EXPIRES_AT, committed.expiresAtMs());
        } else if (invocation.fileCloseReservation != null) {
            fileRuntime.completeClose(invocation.fileCloseReservation);
        }
        if (invocation.providerRoute != null) {
            providerRuntime.completeOperation(invocation.providerRoute, result, now());
            invocation.providerAuditFinalized = true;
        }
    }

    private void rollbackNested(ComponentInvocation invocation,
                                BrokerReceiverRuntime.Reservation receiverReservation,
                                BrokerProviderRuntime.Reservation providerReservation,
                                Throwable error) {
        receiverCoordinator.rollbackRegistration(receiverReservation);
        providerRuntime.rollbackPrepare(providerReservation);
        cursorRuntime.rollbackQuery(invocation.cursorQueryReservation);
        if (invocation.cursorPageReservation != null) {
            cursorRuntime.abort(invocation.cursorPageReservation.token());
            providerResources.closeCursorBestEffort(invocation.session,
                    invocation.cursorPageReservation.token());
        }
        if (invocation.cursorTerminalReservation != null) {
            try {
                cursorRuntime.completeTerminal(invocation.cursorTerminalReservation);
            } catch (RuntimeException ignored) {
            }
        }
        if (invocation.cursorQueryReservation != null && invocation.cursorTargetSession != null) {
            providerResources.closeCursorBestEffort(invocation.cursorTargetSession,
                    invocation.cursorQueryReservation.token());
        }
        fileRuntime.rollbackOpen(invocation.fileOpenReservation);
        if (invocation.fileOpenReservation != null && invocation.fileTargetSession != null) {
            providerResources.closeFileBestEffort(invocation.fileTargetSession,
                    invocation.fileOpenReservation.token());
        }
        if (invocation.fileCloseReservation != null) {
            fileRuntime.abort(invocation.fileCloseReservation.token());
            providerResources.closeFileBestEffort(invocation.session,
                    invocation.fileCloseReservation.token());
        }
        if (invocation.providerRoute != null) {
            providerRuntime.failOperation(invocation.providerRoute, error, now());
            invocation.providerAuditFinalized = true;
        }
    }

    private Bundle failureWithCleanup(ComponentInvocation invocation, Throwable error) {
        try {
            if (invocation != null) {
                cursorRuntime.rollbackQuery(invocation.cursorQueryReservation);
                if (invocation.cursorPageReservation != null) {
                    cursorRuntime.abort(invocation.cursorPageReservation.token());
                }
                fileRuntime.rollbackOpen(invocation.fileOpenReservation);
                if (invocation.fileCloseReservation != null) {
                    fileRuntime.abort(invocation.fileCloseReservation.token());
                }
                if (invocation.providerRoute != null && !invocation.providerAuditFinalized) {
                    providerRuntime.failOperation(invocation.providerRoute, error, now());
                }
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

    private static final class ComponentInvocation {
        Bundle request;
        final String operation;
        final String targetPackage;
        String requestedPackage;
        int requestedUser;
        final boolean foreignReceiver;
        Bundle immediate;
        GuestSession session;
        BrokerProviderRuntime.OperationRoute providerRoute;
        boolean providerAuditFinalized;
        BrokerProviderRuntime.Reservation providerPreparationReservation;
        UriGrantRegistry.Authorization uriGrantAuthorization;
        UriGrantRegistry.AuthorizationResult uriGrantAuthorizationResult;
        BrokerCursorRuntime.Lease cursorLease;
        String queryCancellationId;
        BrokerCursorRuntime.QueryReservation cursorQueryReservation;
        BrokerCursorRuntime.PageReservation cursorPageReservation;
        BrokerCursorRuntime.TerminalReservation cursorTerminalReservation;
        GuestSession cursorTargetSession;
        BrokerFileRuntime.Lease fileLease;
        BrokerFileRuntime.OpenReservation fileOpenReservation;
        BrokerFileRuntime.CloseReservation fileCloseReservation;
        GuestSession fileTargetSession;

        ComponentInvocation(Bundle request, String operation, String requestedPackage,
                            int requestedUser, String targetPackage, boolean foreignReceiver) {
            this.request = request;
            this.operation = operation;
            this.requestedPackage = requestedPackage;
            this.requestedUser = requestedUser;
            this.targetPackage = targetPackage;
            this.foreignReceiver = foreignReceiver;
        }

        private ComponentInvocation(Bundle immediate) {
            this(null, null, "", -1, "", false);
            this.immediate = immediate;
        }

        static ComponentInvocation immediate(Bundle result) {
            return new ComponentInvocation(result);
        }

    }

    private Bundle createPendingIntentSender(Bundle request, String requestedPackage,
                                             int requestedUser) throws Exception {
        return owner.createPendingIntentSender(request, requestedPackage, requestedUser);
    }

    private Bundle unregisterProviderObserver(Bundle request, String requestedPackage, int requestedUser) {
        return owner.unregisterProviderObserver(request, requestedPackage, requestedUser);
    }

    private Bundle notifyProviderObservers(Bundle request, String requestedPackage, int requestedUser) {
        return owner.notifyProviderObservers(request, requestedPackage, requestedUser);
    }

    private UriGrantRegistry.Authorization beginUriGrantAuthorization(Bundle request,
                                                                       String callerInstance,
                                                                       String targetInstance) {
        return owner.beginUriGrantAuthorization(request, callerInstance, targetInstance);
    }

    private ProviderAccess providerAccess(BrokerProviderRuntime.OperationRoute route, Bundle request,
                                          GuestSession targetSession) {
        return owner.providerAccess(route, request, targetSession);
    }

    private void validateCursorRequestIdentity(Bundle request, String requestedPackage, int requestedUser,
                                               BrokerCursorRuntime.Lease lease) {
        owner.validateCursorRequestIdentity(request, requestedPackage, requestedUser, lease);
    }

    private void validateFileRequestIdentity(Bundle request, String requestedPackage, int requestedUser,
                                             BrokerFileRuntime.Lease lease) {
        owner.validateFileRequestIdentity(request, requestedPackage, requestedUser, lease);
    }

    private Bundle recordFrameworkServiceEvent(GuestSession session, Bundle request) {
        return owner.recordFrameworkServiceEvent(session, request);
    }

    private Bundle prepareGuestInternal(Bundle request) {
        return owner.prepareGuestInternal(request);
    }

    private Bundle prepareForeignTargetRequest(Bundle request, GuestSession caller,
                                               String targetPackage, VirtualPackageMetadata.Type type,
                                               boolean ensurePrepared) throws Exception {
        return owner.prepareForeignTargetRequest(request, caller, targetPackage, type, ensurePrepared);
    }

    private GuestSession callerSession(Bundle request, String callerPackage) {
        return owner.callerSession(request, callerPackage);
    }

    private GuestSession sessionById(String sessionId, long generation) {
        return owner.sessionById(sessionId, generation);
    }

    private Bundle callGuest(int slot, RuntimeGuestConnectionPool.GuestCall call) throws Exception {
        return owner.callGuest(slot, call);
    }

    private void purgeExpiredResources() {
        owner.purgeExpiredResources();
    }

    private long now() {
        return owner.now();
    }

    private static Bundle guestOperation(IGuestProcess guest, String operation, Bundle payload)
            throws Exception {
        return RuntimeBrokerService.guestOperation(guest, operation, payload);
    }

    private static String targetPackageForRequest(Bundle request, String callerPackage) {
        return RuntimeBrokerService.targetPackageForRequest(request, callerPackage);
    }

    private static String processKey(String packageName, int userId, String processName) {
        return RuntimeBrokerService.processKey(packageName, userId, processName);
    }

    private static String ownerKey(String packageName, int userId) {
        return RuntimeBrokerService.ownerKey(packageName, userId);
    }

    private static String processName(Bundle request, String packageName) {
        return RuntimeBrokerService.processName(request, packageName);
    }

    private static String required(Bundle bundle, String key) {
        return RuntimeBrokerService.required(bundle, key);
    }

    private static boolean isPrepared(Bundle bundle) {
        return RuntimeBrokerService.isPrepared(bundle);
    }

    private static boolean isProviderCursorOperation(String operation) {
        return RuntimeBrokerService.isProviderCursorOperation(operation);
    }

    private static void requireProviderTargetSession(GuestSession session,
            ProviderAuthorityRegistry.Entry target, Bundle request) {
        RuntimeBrokerService.requireProviderTargetSession(session, target, request);
    }

    private static void requireCursorTargetSession(GuestSession session,
            BrokerCursorRuntime.Lease lease, Bundle request) {
        RuntimeBrokerService.requireCursorTargetSession(session, lease, request);
    }

    private static void requireFileTargetSession(GuestSession session,
            BrokerFileRuntime.Lease lease, Bundle request) {
        RuntimeBrokerService.requireFileTargetSession(session, lease, request);
    }

    private static ProviderBatchRuntime.BatchException findBatchException(Throwable error) {
        return RuntimeBrokerService.findBatchException(error);
    }

    private static void restoreTargetSessionIdentity(Bundle target, Bundle prepared,
                                                     GuestSession session) {
        RuntimeBrokerService.restoreTargetSessionIdentity(target, prepared, session);
    }

    private static Bundle failure(Throwable error) {
        return RuntimeBrokerService.failure(error);
    }

    private static Bundle failure(String type, String message) {
        return RuntimeBrokerService.failure(type, message);
    }

}
