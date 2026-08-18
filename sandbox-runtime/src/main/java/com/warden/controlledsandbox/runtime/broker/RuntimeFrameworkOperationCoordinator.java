package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.VirtualPendingIntentSnapshot;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.identity.VirtualUidRegistry;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

final class RuntimeFrameworkOperationCoordinator {
    private final RuntimeBrokerService brokerOwner;
    private final SessionRegistry sessions;
    private final BrokerStateStore brokerState;
    private final RuntimeServiceCoordinator serviceCoordinator;
    private final ConcurrentHashMap<String, PendingIntentOwner> systemHeldOwners = new ConcurrentHashMap<>();

    RuntimeFrameworkOperationCoordinator(RuntimeBrokerService brokerOwner) {
        this.brokerOwner = brokerOwner;
        this.sessions = brokerOwner.sessions;
        this.brokerState = brokerOwner.brokerState;
        this.serviceCoordinator = brokerOwner.serviceCoordinator;
    }

    Bundle recordFrameworkServiceEvent(GuestSession session, Bundle request) {
        String event = required(request, RuntimeKeys.FRAMEWORK_SERVICE_EVENT);
        String component = required(request, RuntimeKeys.COMPONENT_CLASS);
        Bundle stateRequest = new Bundle(request);
        String stateOperation;
        switch (event) {
            case ComponentOperations.FRAMEWORK_SERVICE_EVENT_START,
                    ComponentOperations.FRAMEWORK_SERVICE_EVENT_START_BEGIN -> stateOperation =
                    request.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_FOREGROUND, false)
                            ? ComponentOperations.START_FOREGROUND_SERVICE
                            : ComponentOperations.START_SERVICE;
            case ComponentOperations.FRAMEWORK_SERVICE_EVENT_BIND -> stateOperation =
                    ComponentOperations.BIND_SERVICE;
            case ComponentOperations.FRAMEWORK_SERVICE_EVENT_UNBIND -> stateOperation =
                    ComponentOperations.UNBIND_SERVICE;
            case ComponentOperations.FRAMEWORK_SERVICE_EVENT_STOP -> stateOperation =
                    ComponentOperations.STOP_SERVICE;
            default -> throw new IllegalArgumentException("Unknown framework Service event: " + event);
        }
        stateRequest.putString(ComponentOperations.OPERATION, stateOperation);
        stateRequest.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        if (ComponentOperations.FRAMEWORK_SERVICE_EVENT_START.equals(event)
                || ComponentOperations.FRAMEWORK_SERVICE_EVENT_START_BEGIN.equals(event)) {
            stateRequest.putString(ComponentOperations.ACTION,
                    request.getString(ComponentOperations.ACTION, ""));
        }
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "FRAMEWORK_SERVICE_EVENT_RECORDED");
        result.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        result.putString(RuntimeKeys.COMPONENT_CLASS, component);
        if (ComponentOperations.FRAMEWORK_SERVICE_EVENT_START.equals(event)
                || ComponentOperations.FRAMEWORK_SERVICE_EVENT_START_BEGIN.equals(event)) {
            result.putInt("onStartCommandResult", request.getInt(RuntimeKeys.SERVICE_START_RESULT,
                    Service.START_NOT_STICKY));
            result.putInt(RuntimeKeys.SERVICE_START_ID,
                    request.getInt(RuntimeKeys.SERVICE_START_ID, -1));
            result.putBoolean(RuntimeKeys.SERVICE_REDELIVERED,
                    request.getBoolean(RuntimeKeys.SERVICE_REDELIVERED, false));
        }
        if (ComponentOperations.FRAMEWORK_SERVICE_EVENT_START_BEGIN.equals(event)) {
            serviceCoordinator.beginFrameworkStart(session, stateRequest, result);
        } else if (ComponentOperations.FRAMEWORK_SERVICE_EVENT_START.equals(event)
                && request.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, false)) {
            serviceCoordinator.completeFrameworkStart(session, stateRequest, result);
        } else {
            serviceCoordinator.applySuccessfulOperation(session, stateRequest, result);
        }
        result.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        result.putLong(RuntimeKeys.GENERATION, session.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        result.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        result.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        result.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        RuntimeEventLog.event("FRAMEWORK_SERVICE_EVENT", result);
        return result;
    }

    /**
     * Converts a durable Guest PendingIntent record into a Binder whose lifetime is owned by the
     * Broker process.  The record is revalidated against the generation-scoped virtual system
     * service before the Binder is exposed, so a caller cannot manufacture a relay for an
     * arbitrary token or another process.
     */
    Bundle createPendingIntentSender(Bundle request, String requestedPackage,
                                             int requestedUser) throws Exception {
        if (!requestedPackage.equals(request.getString(RuntimeKeys.PACKAGE_NAME, ""))) {
            throw new SecurityException("PENDING_INTENT_CREATOR_PACKAGE_MISMATCH");
        }
        String sessionId = required(request, RuntimeKeys.SESSION_ID);
        long generation = request.getLong(RuntimeKeys.GENERATION, -1L);
        GuestSession session = sessionById(sessionId, generation);
        if (!RuntimePreparingSessionPolicy.isOperational(session, request, false)) {
            throw new SecurityException("PENDING_INTENT_CREATOR_SESSION_NOT_READY");
        }
        if (session.virtualUserId() != requestedUser
                || !session.processName().equals(processName(request, requestedPackage))) {
            throw new SecurityException("PENDING_INTENT_CREATOR_IDENTITY_MISMATCH");
        }
        String tokenId = required(request, RuntimeKeys.PENDING_INTENT_TOKEN_ID);
        int virtualUid = uidRegistry().uidFor(session.packageName(), session.virtualUserId());
        IVirtualSystemServiceSession systemServices = brokerOwner.systemServiceCoordinator.sessionFor(session);
        VirtualPendingIntentSnapshot matched = null;
        for (VirtualPendingIntentSnapshot value : systemServices.listPendingIntents()) {
            if (value != null && tokenId.equals(value.tokenId())) {
                matched = value;
                break;
            }
        }
        if (matched == null || matched.cancelled()
                || !session.packageName().equals(matched.creatorPackage())
                || virtualUid != matched.creatorUid()
                || !session.processName().equals(matched.ownerProcessName())
                || session.generation() != matched.ownerGeneration()
                || !session.packageRevision().equals(matched.packageRevision())) {
            throw new SecurityException("PENDING_INTENT_TOKEN_OWNER_MISMATCH");
        }
        PendingIntentOwner owner = new PendingIntentOwner(session.packageName(),
                session.virtualUserId(), session.processName(), session.generation(),
                session.packageRevision());
        systemHeldOwners.put(tokenId, owner);
        RuntimePendingIntentSender sender = new RuntimePendingIntentSender(tokenId,
                request.getString("pendingIntentSenderDescriptor", "android.content.IIntentSender"),
                (id, resultCode, fillIn, flagsMask, flagsValues, permission) -> dispatchPendingIntent(
                        owner, id, resultCode, fillIn, flagsMask, flagsValues, permission));
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "PENDING_INTENT_SENDER_CREATED");
        result.putBinder(RuntimeKeys.PENDING_INTENT_SENDER_BINDER, sender);
        result.putString(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
        result.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        result.putLong(RuntimeKeys.GENERATION, session.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        result.putString(RuntimeKeys.PROCESS_NAME, session.processName());
        Bundle audit = sessionBundle(session, "PENDING_INTENT_SENDER_CREATED");
        audit.putString(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
        audit.putBoolean("brokerOwned", true);
        RuntimeEventLog.event("PENDING_INTENT_BROKER_RELAY_CREATED", audit);
        return result;
    }

    void dispatchSystemHeld(String tokenId) throws Exception {
        if (tokenId == null || tokenId.trim().isEmpty()) {
            throw new SecurityException("PENDING_INTENT_TOKEN_REQUIRED");
        }
        PendingIntentOwner owner = systemHeldOwners.get(tokenId.trim());
        if (owner == null) throw new SecurityException("PENDING_INTENT_SYSTEM_HOLDER_UNKNOWN");
        dispatchPendingIntent(owner, tokenId.trim(), 0, null, 0, 0, "");
    }

    /** Handles an IIntentSender.send from any system process, including after Guest death. */
    private RuntimePendingIntentSender.DispatchResult dispatchPendingIntent(
            PendingIntentOwner owner, String tokenId, int resultCode, Intent fillIn,
            int flagsMask, int flagsValues, String permission) throws Exception {
        Throwable firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            GuestSession session = sessions.get(owner.packageName, owner.virtualUserId,
                    owner.processName);
            try {
                if (session == null || session.state() == SessionState.RECOVERING
                        || session.state() == SessionState.ALLOCATED
                        || session.state() == SessionState.PREPARING) {
                    Bundle prepared = brokerState.prepared(processKey(owner.packageName,
                            owner.virtualUserId, owner.processName));
                    if (prepared == null) throw new SecurityException("PENDING_INTENT_OWNER_NOT_INSTALLED");
                    Bundle coldBind = prepareGuestInternal(new Bundle(prepared));
                    if (!isPrepared(coldBind)) {
                        throw new IllegalStateException("PENDING_INTENT_OWNER_RECOVERY_FAILED:" +
                                coldBind.getString(RuntimeKeys.ERROR_TYPE, "FAILED"));
                    }
                    session = sessions.get(owner.packageName, owner.virtualUserId, owner.processName);
                }
                if (!RuntimePreparingSessionPolicy.isOperational(session, new Bundle(), false)) {
                    throw new SecurityException("PENDING_INTENT_OWNER_SESSION_NOT_READY");
                }
                if (!owner.packageRevision.isEmpty()
                        && !owner.packageRevision.equals(session.packageRevision())) {
                    throw new SecurityException("PENDING_INTENT_OWNER_REVISION_MISMATCH");
                }
                // The relay Binder may outlive both the Guest process and the PendingIntent
                // record.  Creation-time validation is not sufficient: cancellation, one-shot
                // delivery, clear-data, or reinstall can retire the token while a system
                // process still retains this IIntentSender. Re-check the Broker-owned durable
                // record immediately before delivery so a stale sender cannot trigger a cold
                // bind or route into a replacement package revision.
                VirtualPendingIntentSnapshot currentToken =
                        requireLivePendingIntent(session, tokenId);
                if (!session.processName().equals(currentToken.ownerProcessName())
                        || !session.packageRevision().equals(currentToken.packageRevision())
                        || currentToken.creatorUid() != uidRegistry().uidFor(
                                session.packageName(), session.virtualUserId())) {
                    throw new SecurityException("PENDING_INTENT_TOKEN_OWNER_MISMATCH");
                }
                Bundle base = brokerState.prepared(processKey(session.packageName(),
                        session.virtualUserId(), session.processName()));
                if (base == null) throw new SecurityException("PENDING_INTENT_OWNER_SPEC_MISSING");
                Bundle request = new Bundle(base);
                request.putString(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
                request.putString(RuntimeKeys.PENDING_INTENT_SENDER_PERMISSION,
                        permission == null ? "" : permission);
                request.putInt(RuntimeKeys.PENDING_INTENT_FLAGS_MASK, flagsMask);
                request.putInt(RuntimeKeys.PENDING_INTENT_FLAGS_VALUES, flagsValues);
                request.putInt(RuntimeKeys.PENDING_INTENT_RESULT_CODE, resultCode);
                if (fillIn != null) {
                    com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.encode(
                            request, fillIn);
                    request.putBoolean(RuntimeKeys.PENDING_INTENT_FILL_IN, true);
                }
                Bundle result = callGuest(session.processSlot(), guest -> guestOperation(
                        guest, RuntimeOperationRequest.SEND_PENDING_INTENT, request));
                if (result == null || "FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                    throw new IllegalStateException("PENDING_INTENT_DELIVERY_FAILED:" +
                            (result == null ? "NO_RESULT" : result.getString(RuntimeKeys.ERROR_TYPE, "FAILED")));
                }
                Bundle audit = sessionBundle(session, "PENDING_INTENT_DELIVERED");
                audit.putString(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
                audit.putBoolean("brokerOwned", true);
                audit.putBoolean("reboundGeneration", session.generation() != owner.generation);
                RuntimeEventLog.event("PENDING_INTENT_BROKER_RELAY_DELIVERED", audit);
                Intent delivered = result.getBoolean(RuntimeKeys.PENDING_INTENT_DELIVERED_INTENT, false)
                        ? RuntimeIntentWireCodec.decode(result) : fillIn;
                return new RuntimePendingIntentSender.DispatchResult(
                        result.getInt("pendingIntentSendResult", 0), delivered);
            } catch (Throwable error) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
                if (firstFailure == null) firstFailure = error;
                if (attempt == 0) {
                    Bundle prepared = brokerState.prepared(processKey(owner.packageName,
                            owner.virtualUserId, owner.processName));
                    if (prepared != null) {
                        Bundle coldBind = prepareGuestInternal(new Bundle(prepared));
                        if (isPrepared(coldBind)) continue;
                    }
                }
                if (error instanceof Exception exception) throw exception;
                throw new IllegalStateException(error);
            }
        }
        throw new IllegalStateException("PENDING_INTENT_DELIVERY_FAILED", firstFailure);
    }

    private VirtualPendingIntentSnapshot requireLivePendingIntent(GuestSession session,
                                                                  String tokenId) throws Exception {
        if (session == null) throw new SecurityException("PENDING_INTENT_OWNER_SESSION_NOT_READY");
        IVirtualSystemServiceSession systemServices = brokerOwner.systemServiceCoordinator.sessionFor(session);
        for (VirtualPendingIntentSnapshot value : systemServices.listPendingIntents()) {
            if (value != null && tokenId.equals(value.tokenId())) {
                if (value.cancelled()) {
                    throw new SecurityException("PENDING_INTENT_CANCELLED");
                }
                return value;
            }
        }
        throw new SecurityException("PENDING_INTENT_NOT_FOUND");
    }

    private record PendingIntentOwner(String packageName, int virtualUserId, String processName,
                                      long generation, String packageRevision) { }

    static void restoreTargetSessionIdentity(Bundle target, Bundle prepared,
                                             GuestSession session) {
        copyInt(target, prepared, RuntimeKeys.PROTOCOL);
        target.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        target.putLong(RuntimeKeys.GENERATION, session.generation());
        copyString(target, prepared, RuntimeKeys.PACKAGE_NAME);
        copyInt(target, prepared, RuntimeKeys.VIRTUAL_USER_ID);
        copyInt(target, prepared, RuntimeKeys.VIRTUAL_UID);
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
        copyBoolean(target, prepared, RuntimeKeys.NATIVE_CODE_PRESENT);
        copyBoolean(target, prepared, RuntimeKeys.ISOLATED_PROCESS);
        if (prepared.containsKey(RuntimeKeys.PERMISSIONS)) {
            target.putStringArrayList(RuntimeKeys.PERMISSIONS,
                    prepared.getStringArrayList(RuntimeKeys.PERMISSIONS));
        }
        copyStringList(target, prepared, RuntimeKeys.SPLIT_NAMES);
        copyStringList(target, prepared, RuntimeKeys.SPLIT_TYPES);
        copyStringList(target, prepared, RuntimeKeys.SPLIT_CONFIG_FOR);
        copyStringList(target, prepared, RuntimeKeys.SPLIT_USES);
        copyStringList(target, prepared, RuntimeKeys.SPLIT_PATHS);
        copyStringList(target, prepared, RuntimeKeys.SPLIT_SHA256S);
        copyString(target, prepared, RuntimeKeys.SHARED_LIBRARIES);
        if (prepared.containsKey(RuntimeKeys.PACKAGE_STATE)) {
            target.putParcelable(RuntimeKeys.PACKAGE_STATE,
                    prepared.getParcelable(RuntimeKeys.PACKAGE_STATE));
        }
        if (prepared.containsKey(RuntimeKeys.PACKAGE_UNIVERSE)) {
            target.putParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE,
                    prepared.getParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE));
        }
        if (prepared.containsKey(RuntimeKeys.ISOLATED_SPLIT_FDS)) {
            target.putParcelableArrayList(RuntimeKeys.ISOLATED_SPLIT_FDS,
                    prepared.getParcelableArrayList(RuntimeKeys.ISOLATED_SPLIT_FDS));
        }
        if (prepared.containsKey(RuntimeKeys.ISOLATED_SPLIT_ENTRY_NAMES)) {
            target.putStringArrayList(RuntimeKeys.ISOLATED_SPLIT_ENTRY_NAMES,
                    prepared.getStringArrayList(RuntimeKeys.ISOLATED_SPLIT_ENTRY_NAMES));
        }
        if (prepared.containsKey(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER)) {
            target.putBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER,
                    prepared.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER));
        }
        if (prepared.containsKey(RuntimeKeys.RUNTIME_BROKER_BINDER)) {
            target.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER,
                    prepared.getBinder(RuntimeKeys.RUNTIME_BROKER_BINDER));
        }
        if (prepared.containsKey(RuntimeKeys.RUNTIME_STORAGE_BINDER)) {
            target.putBinder(RuntimeKeys.RUNTIME_STORAGE_BINDER,
                    prepared.getBinder(RuntimeKeys.RUNTIME_STORAGE_BINDER));
        }
    }

    private static void copyString(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putString(key, source.getString(key, ""));
    }

    private static void copyLong(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putLong(key, source.getLong(key));
    }

    private static void copyInt(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putInt(key, source.getInt(key));
    }

    private static void copyBoolean(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) target.putBoolean(key, source.getBoolean(key));
    }

    private static void copyStringList(Bundle target, Bundle source, String key) {
        if (source.containsKey(key)) {
            ArrayList<String> values = source.getStringArrayList(key);
            if (values != null) target.putStringArrayList(key, new ArrayList<>(values));
        }
    }

    private GuestSession sessionById(String sessionId, long generation) {
        return brokerOwner.sessionById(sessionId, generation);
    }

    private Bundle prepareGuestInternal(Bundle request) {
        return brokerOwner.prepareGuestInternal(request);
    }

    private Bundle callGuest(int slot, RuntimeGuestConnectionPool.GuestCall call) throws Exception {
        return brokerOwner.callGuest(slot, call);
    }

    private Bundle sessionBundle(GuestSession session, String status) {
        return brokerOwner.sessionBundle(session, status);
    }

    private VirtualUidRegistry uidRegistry() {
        return brokerOwner.uidRegistry();
    }

    private long now() {
        return brokerOwner.now();
    }

    private static Bundle guestOperation(com.warden.controlledsandbox.contract.IGuestProcess guest,
                                         String operation, Bundle payload) throws Exception {
        return RuntimeBrokerService.guestOperation(guest, operation, payload);
    }

    private static String required(Bundle bundle, String key) {
        return RuntimeBrokerService.required(bundle, key);
    }

    private static String processName(Bundle bundle, String packageName) {
        return RuntimeBrokerService.processName(bundle, packageName);
    }

    private static String processKey(String packageName, int userId, String processName) {
        return RuntimeBrokerService.processKey(packageName, userId, processName);
    }

    private static boolean isPrepared(Bundle bundle) {
        return RuntimeBrokerService.isPrepared(bundle);
    }

}
