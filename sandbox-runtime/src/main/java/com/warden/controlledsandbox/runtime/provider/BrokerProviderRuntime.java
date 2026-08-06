package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.domain.component.provider.ProviderAuthorityRegistration;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.component.provider.ProviderAuthorityRegistry;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Broker-owned authority for Provider namespace ownership, routing, authorization and audit. */
public final class BrokerProviderRuntime {
    private static final int MAX_AUDIT_ENTRIES = 256;

    public interface PermissionChecker {
        boolean allows(String callerInstance, String uri, int flags);
    }

    public interface DeclaredPermissionChecker {
        boolean allows(String permission);
    }

    public interface SessionLookup {
        GuestSession find(String sessionId, long generation);
    }

    public static final class Reservation {
        private final int virtualUserId;
        private final String instanceId;
        private final String sessionId;
        private final long generation;
        private final Set<String> createdAuthorities;

        private Reservation(int virtualUserId, String instanceId, String sessionId, long generation,
                            Set<String> createdAuthorities) {
            this.virtualUserId = virtualUserId;
            this.instanceId = instanceId;
            this.sessionId = sessionId;
            this.generation = generation;
            this.createdAuthorities = createdAuthorities;
        }
    }

    public static final class OperationRoute {
        private final String auditId;
        private final String operation;
        private final String callerInstance;
        private final String targetInstance;
        private final String authority;
        private final String uri;
        private final int flags;
        private final String permissionBasis;
        private final ProviderAuthorityRegistry.Entry entry;

        private OperationRoute(String auditId, String operation, String callerInstance, String targetInstance,
                               String authority, String uri, int flags, String permissionBasis,
                               ProviderAuthorityRegistry.Entry entry) {
            this.auditId = auditId;
            this.operation = operation;
            this.callerInstance = callerInstance;
            this.targetInstance = targetInstance;
            this.authority = authority;
            this.uri = uri;
            this.flags = flags;
            this.permissionBasis = permissionBasis;
            this.entry = entry;
        }

        String auditId() { return auditId; }
        String operation() { return operation; }
        public String callerInstance() { return callerInstance; }
        public String targetInstance() { return targetInstance; }
        public String authority() { return authority; }
        public String uri() { return uri; }
        public int flags() { return flags; }
        public String permissionBasis() { return permissionBasis; }
        public ProviderAuthorityRegistry.Entry entry() { return entry; }
    }

    static final class AuditEntry {
        private final String auditId;
        private final String operation;
        private final String callerInstance;
        private final String targetInstance;
        private final String authority;
        private final String uri;
        private final int flags;
        private final String permissionBasis;
        private final boolean success;
        private final String status;
        private final String errorType;
        private final int failureIndex;
        private final long timestampMs;

        private AuditEntry(String auditId, String operation, String callerInstance, String targetInstance,
                           String authority, String uri, int flags, String permissionBasis, boolean success,
                           String status, String errorType, int failureIndex, long timestampMs) {
            this.auditId = auditId;
            this.operation = operation;
            this.callerInstance = callerInstance;
            this.targetInstance = targetInstance;
            this.authority = authority;
            this.uri = uri;
            this.flags = flags;
            this.permissionBasis = permissionBasis;
            this.success = success;
            this.status = status;
            this.errorType = errorType;
            this.failureIndex = failureIndex;
            this.timestampMs = timestampMs;
        }

        String auditId() { return auditId; }
        String operation() { return operation; }
        String callerInstance() { return callerInstance; }
        String targetInstance() { return targetInstance; }
        String authority() { return authority; }
        String uri() { return uri; }
        int flags() { return flags; }
        String permissionBasis() { return permissionBasis; }
        boolean success() { return success; }
        String status() { return status; }
        String errorType() { return errorType; }
        int failureIndex() { return failureIndex; }
        long timestampMs() { return timestampMs; }
    }

    private final ProviderAuthorityRegistry registry = new ProviderAuthorityRegistry();
    private final Deque<AuditEntry> auditEntries = new ArrayDeque<>();
    private long auditSuccessCount;
    private long auditFailureCount;

    public synchronized Reservation reservePrepare(Bundle request, GuestSession session) {
        String authority = required(request, ComponentOperations.AUTHORITY);
        String componentClass = required(request, RuntimeKeys.COMPONENT_CLASS);
        ProviderManifestAuthorityResolver.Metadata provider =
                ProviderManifestAuthorityResolver.resolve(request, componentClass, authority);
        ProviderAuthorityRegistration registration = registry.registerSession(
                instanceId(session), session.virtualUserId(), authority, componentClass,
                session.processName(), provider.exported(), provider.readPermission(),
                provider.writePermission(), provider.grantUriPermissions(), provider.pathRules(),
                session.sessionId(), session.generation());
        return new Reservation(session.virtualUserId(), instanceId(session), session.sessionId(),
                session.generation(), registration.createdAuthorities());
    }

    public synchronized void rollbackPrepare(Reservation reservation) {
        if (reservation == null || reservation.createdAuthorities.isEmpty()) return;
        registry.rollback(reservation.virtualUserId, reservation.instanceId, reservation.sessionId,
                reservation.generation, reservation.createdAuthorities);
    }

    public synchronized ProviderAuthorityRegistry.Entry requireOwned(Bundle request, GuestSession session) {
        String authority = required(request, ComponentOperations.AUTHORITY);
        validateUriAuthority(request.getString(RuntimeKeys.URI, ""), authority);
        return registry.requireOwned(session.virtualUserId(), instanceId(session), authority,
                session.sessionId(), session.generation());
    }

    public synchronized ProviderAuthorityRegistry.Entry requireGrantOwner(String uri, int virtualUserId,
                                                                    String ownerInstance) {
        String authority = authorityFromUri(uri);
        ProviderAuthorityRegistry.Entry entry = registry.resolveAuthority(virtualUserId, authority);
        if (entry == null) throw new IllegalArgumentException("UNKNOWN_PROVIDER_AUTHORITY:" + authority);
        if (!entry.instanceId().equals(ownerInstance)) {
            throw new SecurityException("URI_GRANT_PROVIDER_OWNER_MISMATCH");
        }
        return entry;
    }

    public String requireCallerInstance(Bundle request, String targetPackage, int targetUser,
                                 SessionLookup sessionLookup) {
        String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, targetPackage);
        int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, targetUser);
        if (callerUser < 0) throw new IllegalArgumentException("callerVirtualUserId must be non-negative");
        String callerInstance = instanceId(callerPackage, callerUser);
        if (callerInstance.equals(instanceId(targetPackage, targetUser))) return callerInstance;

        String callerSessionId = required(request, RuntimeKeys.CALLER_SESSION_ID);
        long callerGeneration = request.getLong(RuntimeKeys.CALLER_GENERATION, -1);
        GuestSession caller = sessionLookup == null ? null : sessionLookup.find(callerSessionId, callerGeneration);
        if (caller == null || (caller.state() != SessionState.READY && caller.state() != SessionState.ACTIVE)) {
            throw new SecurityException("PROVIDER_CALLER_SESSION_NOT_READY");
        }
        if (!caller.packageName().equals(callerPackage) || caller.virtualUserId() != callerUser) {
            throw new SecurityException("PROVIDER_CALLER_IDENTITY_MISMATCH");
        }
        return callerInstance;
    }

    public synchronized OperationRoute routeOperation(Bundle request, String operation, String callerInstance,
                                                int targetVirtualUserId, String requestedTargetInstance,
                                                PermissionChecker permissionChecker, long nowMs) {
        return routeOperation(request, operation, callerInstance, targetVirtualUserId,
                requestedTargetInstance, permissionChecker, null, nowMs);
    }

    public synchronized OperationRoute routeOperation(Bundle request, String operation, String callerInstance,
                                                int targetVirtualUserId, String requestedTargetInstance,
                                                PermissionChecker permissionChecker,
                                                DeclaredPermissionChecker declaredPermissionChecker,
                                                long nowMs) {
        if (!ComponentOperations.isProviderTransactionOperation(operation)) {
            throw new IllegalArgumentException("Provider operation is not transaction-routable: " + operation);
        }
        String authority = required(request, ComponentOperations.AUTHORITY);
        ProviderAuthorityRegistry.Entry entry = requireRouteEntry(request, operation, callerInstance,
                targetVirtualUserId, requestedTargetInstance, authority, nowMs);
        OperationInput input = operationInput(request, operation, callerInstance, entry, authority, nowMs);
        String permissionBasis = permissionBasis(entry, input, callerInstance, permissionChecker,
                declaredPermissionChecker, operation, authority, nowMs);
        return new OperationRoute(UUID.randomUUID().toString(), operation, callerInstance,
                entry.instanceId(), authority, input.uri, input.flags, permissionBasis, entry);
    }

    private ProviderAuthorityRegistry.Entry requireRouteEntry(Bundle request, String operation,
            String callerInstance, int targetVirtualUserId, String requestedTargetInstance,
            String authority, long nowMs) {
        ProviderAuthorityRegistry.Entry entry = registry.resolveAuthority(targetVirtualUserId, authority);
        if (entry == null) {
            recordDenied(operation, callerInstance, requestedTargetInstance, authority,
                    request.getString(RuntimeKeys.URI, ""), 0, "UNKNOWN_AUTHORITY", nowMs);
            throw new IllegalArgumentException("UNKNOWN_PROVIDER_AUTHORITY:" + authority);
        }
        if (requestedTargetInstance != null && !requestedTargetInstance.trim().isEmpty()
                && !entry.instanceId().equals(requestedTargetInstance)) {
            recordDenied(operation, callerInstance, entry.instanceId(), authority,
                    request.getString(RuntimeKeys.URI, ""), 0, "TARGET_MISMATCH", nowMs);
            throw new SecurityException("PROVIDER_TARGET_INSTANCE_MISMATCH");
        }
        return entry;
    }

    private OperationInput operationInput(Bundle request, String operation, String callerInstance,
            ProviderAuthorityRegistry.Entry entry, String authority, long nowMs) {
        String uri = request.getString(RuntimeKeys.URI, "");
        ProviderBatchRuntime.Batch batch = null;
        if (ComponentOperations.PROVIDER_CALL.equals(operation)) {
            required(request, RuntimeKeys.PROVIDER_METHOD);
            uri = defaultProviderUri(uri, authority);
        } else if (ComponentOperations.PROVIDER_APPLY_BATCH.equals(operation)) {
            uri = defaultProviderUri(uri, authority);
            try {
                batch = ProviderBatchRuntime.validate(request, authority);
            } catch (ProviderBatchRuntime.BatchException error) {
                recordDenied(operation, callerInstance, entry.instanceId(), authority, uri, 0,
                        "BATCH_INVALID_INDEX_" + error.operationIndex(), nowMs);
                throw new IllegalArgumentException(error.getMessage(), error);
            }
        } else {
            uri = required(request, RuntimeKeys.URI);
        }
        validateUriAuthority(uri, authority);
        int flags = batch == null ? requiredFlags(operation, request) : batch.requiredFlags();
        return new OperationInput(uri, flags, batch);
    }

    private String permissionBasis(ProviderAuthorityRegistry.Entry entry, OperationInput input,
            String callerInstance, PermissionChecker permissionChecker,
            DeclaredPermissionChecker declaredPermissionChecker, String operation,
            String authority, long nowMs) {
        if (entry.instanceId().equals(callerInstance)) return "OWNER";
        if (entry.exported() && declaredPermissionsAllow(entry, input.uri, input.flags, input.batch,
                declaredPermissionChecker)) {
            return hasDeclaredPermission(entry, input.uri, input.flags, input.batch)
                    ? "DECLARED_PERMISSION" : "EXPORTED";
        }
        if (input.batch != null && permissionChecker != null
                && allBatchUrisGrantable(entry, input.batch)
                && allBatchUrisAllowed(input.batch, callerInstance, permissionChecker)) return "URI_GRANT";
        if (input.batch == null && permissionChecker != null && entry.allowsUriGrant(input.uri)
                && permissionChecker.allows(callerInstance, input.uri, input.flags)) return "URI_GRANT";
        recordDenied(operation, callerInstance, entry.instanceId(), authority, input.uri, input.flags,
                "PERMISSION_DENIED", nowMs);
        throw new SecurityException("PROVIDER_PERMISSION_DENIED");
    }

    private static String defaultProviderUri(String uri, String authority) {
        return uri == null || uri.trim().isEmpty() ? "content://" + authority : uri;
    }

    private record OperationInput(String uri, int flags, ProviderBatchRuntime.Batch batch) { }

    public synchronized void completeOperation(OperationRoute route, Bundle result, long nowMs) {
        if (route == null) return;
        boolean success = result != null && !"FAILED".equals(result.getString(RuntimeKeys.STATUS, ""));
        String status = result == null ? "NULL_RESULT" : result.getString(RuntimeKeys.STATUS, "");
        String errorType = result == null ? "NULL_RESULT" : result.getString(RuntimeKeys.ERROR_TYPE, "");
        int failureIndex = result == null ? -1
                : result.getInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, -1);
        appendAudit(new AuditEntry(route.auditId, route.operation, route.callerInstance, route.targetInstance,
                route.authority, route.uri, route.flags, route.permissionBasis, success, status, errorType,
                failureIndex, nowMs));
        if (result != null) {
            result.putString(RuntimeKeys.PROVIDER_AUDIT_ID, route.auditId);
            result.putString(RuntimeKeys.PROVIDER_PERMISSION_BASIS, route.permissionBasis);
        }
    }

    public synchronized void failOperation(OperationRoute route, Throwable error, long nowMs) {
        if (route == null) return;
        String errorType = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        appendAudit(new AuditEntry(route.auditId, route.operation, route.callerInstance, route.targetInstance,
                route.authority, route.uri, route.flags, route.permissionBasis, false,
                "BROKER_CALL_FAILED", errorType, batchFailureIndex(error), nowMs));
    }

    synchronized int processRecovered(GuestSession stale, GuestSession current) {
        if (!instanceId(stale).equals(instanceId(current)) || !stale.processName().equals(current.processName())) {
            throw new IllegalArgumentException("Provider recovery owner mismatch");
        }
        return registry.rebindSession(instanceId(stale), stale.virtualUserId(), stale.processName(),
                stale.sessionId(), stale.generation(), current.sessionId(), current.generation());
    }

    synchronized int invalidate(GuestSession session) {
        return registry.removeSession(session.sessionId(), session.generation());
    }

    synchronized int invalidateInstance(String packageName, int virtualUserId) {
        return registry.unregisterInstance(virtualUserId, instanceId(packageName, virtualUserId));
    }

    synchronized int size() { return registry.size(); }
    public synchronized int auditSize() { return auditEntries.size(); }
    public synchronized long auditSuccessCount() { return auditSuccessCount; }
    public synchronized long auditFailureCount() { return auditFailureCount; }
    synchronized List<AuditEntry> auditSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(auditEntries));
    }

    private void recordDenied(String operation, String callerInstance, String targetInstance, String authority,
                              String uri, int flags, String reason, long nowMs) {
        appendAudit(new AuditEntry(UUID.randomUUID().toString(), operation, safe(callerInstance),
                safe(targetInstance), safe(authority), safe(uri), flags, "DENIED", false,
                "DENIED", reason, failureIndexFromReason(reason), nowMs));
    }

    private static int batchFailureIndex(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof ProviderBatchRuntime.BatchException) {
                return ((ProviderBatchRuntime.BatchException) cursor).operationIndex();
            }
            if (cursor.getCause() == cursor) break;
            cursor = cursor.getCause();
        }
        return -1;
    }

    private static int failureIndexFromReason(String reason) {
        String prefix = "BATCH_INVALID_INDEX_";
        if (reason == null || !reason.startsWith(prefix)) return -1;
        try { return Integer.parseInt(reason.substring(prefix.length())); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private void appendAudit(AuditEntry entry) {
        if (entry.success) auditSuccessCount++; else auditFailureCount++;
        auditEntries.addLast(entry);
        while (auditEntries.size() > MAX_AUDIT_ENTRIES) auditEntries.removeFirst();
    }


    private static boolean declaredPermissionsAllow(ProviderAuthorityRegistry.Entry entry,
                                                     String uri, int flags,
                                                     ProviderBatchRuntime.Batch batch,
                                                     DeclaredPermissionChecker checker) {
        if (batch != null) {
            for (ProviderBatchRuntime.Operation operation : batch.operations()) {
                if (!declaredPermissionsAllow(entry, operation.uri(), operation.flags(), null, checker)) {
                    return false;
                }
            }
            return true;
        }
        String read = (flags & UriGrantRegistry.READ) != 0 ? entry.requiredReadPermission(uri) : "";
        String write = (flags & UriGrantRegistry.WRITE) != 0 ? entry.requiredWritePermission(uri) : "";
        if (!read.isEmpty() && (checker == null || !checker.allows(read))) return false;
        return write.isEmpty() || (checker != null && checker.allows(write));
    }

    private static boolean hasDeclaredPermission(ProviderAuthorityRegistry.Entry entry, String uri,
                                                 int flags, ProviderBatchRuntime.Batch batch) {
        if (batch != null) {
            for (ProviderBatchRuntime.Operation operation : batch.operations()) {
                if (hasDeclaredPermission(entry, operation.uri(), operation.flags(), null)) return true;
            }
            return false;
        }
        return ((flags & UriGrantRegistry.READ) != 0 && !entry.requiredReadPermission(uri).isEmpty())
                || ((flags & UriGrantRegistry.WRITE) != 0
                && !entry.requiredWritePermission(uri).isEmpty());
    }

    private static boolean allBatchUrisGrantable(ProviderAuthorityRegistry.Entry entry,
                                                  ProviderBatchRuntime.Batch batch) {
        for (ProviderBatchRuntime.Operation operation : batch.operations()) {
            if (!entry.allowsUriGrant(operation.uri())) return false;
        }
        return true;
    }

    private static boolean allBatchUrisAllowed(ProviderBatchRuntime.Batch batch, String callerInstance,
                                               PermissionChecker permissionChecker) {
        for (ProviderBatchRuntime.Operation operation : batch.operations()) {
            if (!permissionChecker.allows(callerInstance, operation.uri(), operation.flags())) return false;
        }
        return true;
    }

    private static int requiredFlags(String operation, Bundle request) {
        if (ComponentOperations.PROVIDER_CALL.equals(operation)) {
            return UriGrantRegistry.READ | UriGrantRegistry.WRITE;
        }
        if (ComponentOperations.PROVIDER_APPLY_BATCH.equals(operation)) {
            try {
                return ProviderBatchRuntime.validate(request, required(request, ComponentOperations.AUTHORITY))
                        .requiredFlags();
            } catch (ProviderBatchRuntime.BatchException error) {
                throw new IllegalArgumentException(error.getMessage(), error);
            }
        }
        if (ComponentOperations.PROVIDER_OPEN_FILE.equals(operation)
                || ComponentOperations.PROVIDER_OPEN_ASSET_FILE.equals(operation)) {
            return ProviderFileModes.flags(required(request, RuntimeKeys.PROVIDER_FILE_MODE));
        }
        if (ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE.equals(operation)) {
            required(request, RuntimeKeys.PROVIDER_MIME_TYPE);
            return UriGrantRegistry.READ;
        }
        if (ComponentOperations.requiresProviderWrite(operation)) return UriGrantRegistry.WRITE;
        if (ComponentOperations.requiresProviderRead(operation)) return UriGrantRegistry.READ;
        throw new IllegalArgumentException("Provider operation has no permission classification: " + operation);
    }

    private static void validateUriAuthority(String uri, String expectedAuthority) {
        if (uri == null || uri.trim().isEmpty()) return;
        String prefix = "content://";
        if (!uri.startsWith(prefix)) throw new IllegalArgumentException("Provider URI must use content scheme");
        int slash = uri.indexOf('/', prefix.length());
        String actual = slash < 0 ? uri.substring(prefix.length()) : uri.substring(prefix.length(), slash);
        if (!expectedAuthority.equals(actual)) throw new SecurityException("PROVIDER_URI_AUTHORITY_MISMATCH");
    }

    private static String authorityFromUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) throw new IllegalArgumentException("uri is required");
        String prefix = "content://";
        if (!uri.startsWith(prefix)) throw new IllegalArgumentException("Provider URI must use content scheme");
        int slash = uri.indexOf('/', prefix.length());
        String authority = slash < 0 ? uri.substring(prefix.length()) : uri.substring(prefix.length(), slash);
        if (authority.trim().isEmpty()) throw new IllegalArgumentException("Provider URI authority is required");
        return authority;
    }

    public static String instanceId(GuestSession session) {
        return instanceId(session.packageName(), session.virtualUserId());
    }

    public static String instanceId(String packageName, int virtualUserId) {
        return "u" + virtualUserId + ":" + packageName;
    }

    private static String required(Bundle value, String key) {
        String result = value.getString(key, "");
        if (result == null || result.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
