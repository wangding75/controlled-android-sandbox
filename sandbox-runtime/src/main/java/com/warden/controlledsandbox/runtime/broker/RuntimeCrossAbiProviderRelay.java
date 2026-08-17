package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Host-owned capability table for Provider resources executed by the native Companion.
 *
 * <p>Provider cursors, file leases and observer registrations are created in the Companion
 * process, but Guest calls continue to arrive at the Host Broker.  The Guest therefore receives
 * an opaque Host token.  This class is the only translation point between that token and the
 * Companion token; the Companion token is never treated as Guest authority.</p>
 */
final class RuntimeCrossAbiProviderRelay implements AutoCloseable {
    static final class RemoteRequest {
        private final String targetPackage;
        private final int virtualUserId;
        private final Bundle request;

        private RemoteRequest(String targetPackage, int virtualUserId, Bundle request) {
            this.targetPackage = targetPackage;
            this.virtualUserId = virtualUserId;
            this.request = request;
        }

        String targetPackage() { return targetPackage; }
        int virtualUserId() { return virtualUserId; }
        Bundle request() { return request; }
    }

    private static final class Binding {
        private final String localToken;
        private final String remoteToken;
        private final String targetPackage;
        private final int targetUser;
        private final String callerPackage;
        private final int callerUser;
        private final String callerSessionId;
        private final long callerGeneration;
        private final Bundle targetRequest;

        private Binding(String localToken, String remoteToken, String targetPackage, int targetUser,
                        String callerPackage, int callerUser, String callerSessionId,
                        long callerGeneration, Bundle targetRequest) {
            this.localToken = localToken;
            this.remoteToken = remoteToken;
            this.targetPackage = targetPackage;
            this.targetUser = targetUser;
            this.callerPackage = callerPackage;
            this.callerUser = callerUser;
            this.callerSessionId = callerSessionId;
            this.callerGeneration = callerGeneration;
            this.targetRequest = new Bundle(targetRequest);
        }
    }

    private final Map<String, Binding> cursors = new HashMap<>();
    private final Map<String, Binding> files = new HashMap<>();
    private final Map<String, Binding> observers = new HashMap<>();

    /** Returns a remote request for an already-issued Host-visible cursor/file lease. */
    synchronized RemoteRequest prepareExisting(Bundle request, String operation) {
        if (ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)
                || ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation)) {
            String local = required(request, RuntimeKeys.CURSOR_TOKEN);
            Binding binding = cursors.get(local);
            validate(binding, request, local, "CURSOR");
            Bundle remote = relayRequest(request);
            remote.putString(RuntimeKeys.CURSOR_TOKEN, binding.remoteToken);
            return new RemoteRequest(binding.targetPackage, binding.targetUser, remote);
        }
        if (ComponentOperations.PROVIDER_FILE_CLOSE.equals(operation)) {
            String local = required(request, RuntimeKeys.FILE_TOKEN);
            Binding binding = files.get(local);
            validate(binding, request, local, "FILE");
            Bundle remote = relayRequest(request);
            remote.putString(RuntimeKeys.FILE_TOKEN, binding.remoteToken);
            return new RemoteRequest(binding.targetPackage, binding.targetUser, remote);
        }
        throw new IllegalArgumentException("Unsupported existing cross-ABI Provider resource: "
                + operation);
    }

    /** Returns a remote request for an observer whose public id was issued by this relay. */
    synchronized RemoteRequest prepareObserverUnregister(Bundle request) {
        String local = required(request, RuntimeKeys.OBSERVER_ID);
        Binding binding = observers.get(local);
        validate(binding, request, local, "OBSERVER");
        Bundle remote = new Bundle(binding.targetRequest);
        remote.putString(ComponentOperations.OPERATION,
                ComponentOperations.PROVIDER_OBSERVER_UNREGISTER);
        remote.putString(RuntimeKeys.OBSERVER_ID, binding.remoteToken);
        remote.putBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, true);
        copyCallerIdentity(request, remote);
        return new RemoteRequest(binding.targetPackage, binding.targetUser, remote);
    }

    synchronized boolean hasObserver(String observerId) {
        return observerId != null && observers.containsKey(observerId.trim());
    }

    /** Replaces a Companion cursor token with a Host-owned opaque token. */
    synchronized Bundle exposeCursor(Bundle result, Bundle targetRequest) {
        String remote = required(result, RuntimeKeys.CURSOR_TOKEN);
        String local = localToken("cursor");
        Binding binding = binding(local, remote, targetRequest);
        cursors.put(local, binding);
        Bundle out = new Bundle(result);
        out.putString(RuntimeKeys.CURSOR_TOKEN, local);
        out.putString(RuntimeKeys.CURSOR_OWNER_SESSION_ID,
                targetRequest.getString(RuntimeKeys.CALLER_SESSION_ID, ""));
        out.putLong(RuntimeKeys.CURSOR_OWNER_GENERATION,
                targetRequest.getLong(RuntimeKeys.CALLER_GENERATION, -1L));
        return out;
    }

    /** Keeps the Host token stable while a remote cursor page is fetched. */
    synchronized Bundle preserveCursorToken(Bundle result, Bundle request) {
        String local = required(request, RuntimeKeys.CURSOR_TOKEN);
        Binding binding = cursors.get(local);
        validate(binding, request, local, "CURSOR");
        Bundle out = new Bundle(result);
        out.putString(RuntimeKeys.CURSOR_TOKEN, local);
        return out;
    }

    /** Removes a cursor binding only after the Companion has accepted its terminal operation. */
    synchronized Bundle finishCursor(Bundle result, Bundle request) {
        String local = required(request, RuntimeKeys.CURSOR_TOKEN);
        Binding binding = cursors.get(local);
        validate(binding, request, local, "CURSOR");
        cursors.remove(local);
        Bundle out = new Bundle(result);
        out.putString(RuntimeKeys.CURSOR_TOKEN, local);
        return out;
    }

    /** Replaces a Companion file token while keeping the returned descriptor untouched. */
    synchronized Bundle exposeFile(Bundle result, Bundle targetRequest) {
        String remote = required(result, RuntimeKeys.FILE_TOKEN);
        String local = localToken("file");
        files.put(local, binding(local, remote, targetRequest));
        Bundle out = new Bundle(result);
        out.putString(RuntimeKeys.FILE_TOKEN, local);
        out.putString(RuntimeKeys.FILE_OWNER_SESSION_ID,
                targetRequest.getString(RuntimeKeys.CALLER_SESSION_ID, ""));
        out.putLong(RuntimeKeys.FILE_OWNER_GENERATION,
                targetRequest.getLong(RuntimeKeys.CALLER_GENERATION, -1L));
        return out;
    }

    synchronized Bundle finishFile(Bundle result, Bundle request) {
        String local = required(request, RuntimeKeys.FILE_TOKEN);
        Binding binding = files.get(local);
        validate(binding, request, local, "FILE");
        files.remove(local);
        Bundle out = new Bundle(result);
        out.putString(RuntimeKeys.FILE_TOKEN, local);
        return out;
    }

    /** Replaces a remote observer id with an opaque Host id. */
    synchronized Bundle exposeObserver(Bundle result, Bundle targetRequest) {
        String remote = required(result, RuntimeKeys.OBSERVER_ID);
        String local = localToken("observer");
        observers.put(local, binding(local, remote, targetRequest));
        Bundle out = new Bundle(result);
        out.putString(RuntimeKeys.OBSERVER_ID, local);
        return out;
    }

    synchronized Bundle finishObserverUnregister(Bundle result, Bundle request) {
        String local = required(request, RuntimeKeys.OBSERVER_ID);
        Binding binding = observers.get(local);
        validate(binding, request, local, "OBSERVER");
        observers.remove(local);
        Bundle out = new Bundle(result);
        out.putString(RuntimeKeys.OBSERVER_ID, local);
        return out;
    }

    synchronized void invalidateCaller(String packageName, int virtualUserId,
                                        String sessionId, long generation) {
        removeMatching(cursors, packageName, virtualUserId, sessionId, generation);
        removeMatching(files, packageName, virtualUserId, sessionId, generation);
        removeMatching(observers, packageName, virtualUserId, sessionId, generation);
    }

    synchronized void invalidateTarget(String packageName, int virtualUserId) {
        removeTarget(cursors, packageName, virtualUserId);
        removeTarget(files, packageName, virtualUserId);
        removeTarget(observers, packageName, virtualUserId);
    }

    /** Invalidates every Host token after the remote Companion generation is lost. */
    synchronized void invalidateAll() {
        cursors.clear();
        files.clear();
        observers.clear();
    }

    synchronized int size() { return cursors.size() + files.size() + observers.size(); }

    @Override public synchronized void close() {
        cursors.clear();
        files.clear();
        observers.clear();
    }

    private static Binding binding(String local, String remote, Bundle request) {
        return new Binding(local, remote,
                required(request, RuntimeKeys.TARGET_PACKAGE_NAME,
                        request.getString(RuntimeKeys.PACKAGE_NAME, "")),
                request.getInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID,
                        request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1)),
                required(request, RuntimeKeys.CALLER_PACKAGE_NAME,
                        request.getString(RuntimeKeys.PACKAGE_NAME, "")),
                request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID,
                        request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1)),
                required(request, RuntimeKeys.CALLER_SESSION_ID),
                request.getLong(RuntimeKeys.CALLER_GENERATION, -1L), request);
    }

    private static Bundle relayRequest(Bundle request) {
        Bundle remote = new Bundle(request);
        remote.putBoolean(RuntimeKeys.CROSS_ABI_PROVIDER_RELAY, true);
        return remote;
    }

    private static void copyCallerIdentity(Bundle source, Bundle target) {
        target.putString(RuntimeKeys.CALLER_PACKAGE_NAME,
                required(source, RuntimeKeys.CALLER_PACKAGE_NAME));
        target.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID,
                source.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, -1));
        target.putString(RuntimeKeys.CALLER_SESSION_ID,
                required(source, RuntimeKeys.CALLER_SESSION_ID));
        target.putLong(RuntimeKeys.CALLER_GENERATION,
                source.getLong(RuntimeKeys.CALLER_GENERATION, -1L));
    }

    private static void validate(Binding binding, Bundle request, String token, String kind) {
        if (binding == null) throw new SecurityException("UNKNOWN_CROSS_ABI_" + kind + "_RELAY");
        String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME,
                request.getString(RuntimeKeys.PACKAGE_NAME, ""));
        int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID,
                request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1));
        String callerSession = request.getString(RuntimeKeys.CALLER_SESSION_ID, "");
        long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION, -1L);
        if (!binding.callerPackage.equals(callerPackage) || binding.callerUser != callerUser
                || !binding.callerSessionId.equals(callerSession)
                || binding.callerGeneration != callerGeneration) {
            throw new SecurityException("CROSS_ABI_" + kind + "_CALLER_IDENTITY_MISMATCH");
        }
        String targetPackage = request.getString(RuntimeKeys.TARGET_PACKAGE_NAME,
                request.getString(RuntimeKeys.PACKAGE_NAME, ""));
        int targetUser = request.getInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID,
                request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1));
        if (!binding.targetPackage.equals(targetPackage) || binding.targetUser != targetUser) {
            throw new SecurityException("CROSS_ABI_" + kind + "_TARGET_IDENTITY_MISMATCH");
        }
    }

    private static void removeMatching(Map<String, Binding> values, String packageName, int user,
                                       String sessionId, long generation) {
        values.entrySet().removeIf(entry -> {
            Binding binding = entry.getValue();
            return binding.callerPackage.equals(packageName) && binding.callerUser == user
                    && binding.callerSessionId.equals(sessionId)
                    && binding.callerGeneration == generation;
        });
    }

    private static void removeTarget(Map<String, Binding> values, String packageName, int user) {
        values.entrySet().removeIf(entry -> {
            Binding binding = entry.getValue();
            return binding.targetPackage.equals(packageName) && binding.targetUser == user;
        });
    }

    private static String localToken(String kind) {
        return "cross-abi-" + kind + "-" + UUID.randomUUID();
    }

    private static String required(Bundle bundle, String key) {
        return required(bundle, key, "");
    }

    private static String required(Bundle bundle, String key, String fallback) {
        String value = bundle.getString(key, fallback);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }
}
