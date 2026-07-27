package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Broker-owned Provider file lease authority. */
public final class BrokerFileRuntime {
    public static final long LEASE_TTL_MS = 120_000L;
    static final int MAX_ACTIVE_LEASES = 64;

    public static final class Lease {
        private final String token;
        private final String operation;
        private final String callerInstance;
        private final String callerSessionId;
        private final long callerGeneration;
        private final String targetInstance;
        private final String targetPackage;
        private final int targetVirtualUserId;
        private final String targetProcessName;
        private final String targetSessionId;
        private final long targetGeneration;
        private final String uri;
        private final int flags;
        private final String mode;
        private final String mimeType;
        private final String kind;
        private final long startOffset;
        private final long declaredLength;
        private final long expiresAtMs;

        private Lease(MutableLease source) {
            this.token = source.token;
            this.operation = source.operation;
            this.callerInstance = source.callerInstance;
            this.callerSessionId = source.callerSessionId;
            this.callerGeneration = source.callerGeneration;
            this.targetInstance = source.targetInstance;
            this.targetPackage = source.targetPackage;
            this.targetVirtualUserId = source.targetVirtualUserId;
            this.targetProcessName = source.targetProcessName;
            this.targetSessionId = source.targetSessionId;
            this.targetGeneration = source.targetGeneration;
            this.uri = source.uri;
            this.flags = source.flags;
            this.mode = source.mode;
            this.mimeType = source.mimeType;
            this.kind = source.kind;
            this.startOffset = source.startOffset;
            this.declaredLength = source.declaredLength;
            this.expiresAtMs = source.expiresAtMs;
        }

        public String token() { return token; }
        String operation() { return operation; }
        public String callerInstance() { return callerInstance; }
        public String callerSessionId() { return callerSessionId; }
        public long callerGeneration() { return callerGeneration; }
        public String targetInstance() { return targetInstance; }
        public String targetPackage() { return targetPackage; }
        public int targetVirtualUserId() { return targetVirtualUserId; }
        public String targetProcessName() { return targetProcessName; }
        public String targetSessionId() { return targetSessionId; }
        public long targetGeneration() { return targetGeneration; }
        String uri() { return uri; }
        int flags() { return flags; }
        String mode() { return mode; }
        String mimeType() { return mimeType; }
        String kind() { return kind; }
        long startOffset() { return startOffset; }
        long declaredLength() { return declaredLength; }
        public long expiresAtMs() { return expiresAtMs; }
    }

    public static final class OpenReservation {
        private final String token;
        private OpenReservation(String token) { this.token = token; }
        public String token() { return token; }
    }

    public static final class CloseReservation {
        private final String token;
        private CloseReservation(String token) { this.token = token; }
        public String token() { return token; }
    }

    private static final class MutableLease {
        final String token;
        final String operation;
        final String callerInstance;
        final String callerSessionId;
        final long callerGeneration;
        final String targetInstance;
        final String targetPackage;
        final int targetVirtualUserId;
        final String targetProcessName;
        final String targetSessionId;
        final long targetGeneration;
        final String uri;
        final int flags;
        final String mode;
        final String mimeType;
        final long expiresAtMs;
        boolean committed;
        boolean inFlight;
        String kind = "";
        long startOffset;
        long declaredLength = -1L;
        Closeable resource;

        MutableLease(String token, String operation, String callerInstance, String callerSessionId,
                     long callerGeneration, String targetInstance, String targetPackage,
                     int targetVirtualUserId, String targetProcessName, String targetSessionId,
                     long targetGeneration, String uri, int flags, String mode, String mimeType,
                     long expiresAtMs) {
            this.token = token;
            this.operation = operation;
            this.callerInstance = callerInstance;
            this.callerSessionId = callerSessionId;
            this.callerGeneration = callerGeneration;
            this.targetInstance = targetInstance;
            this.targetPackage = targetPackage;
            this.targetVirtualUserId = targetVirtualUserId;
            this.targetProcessName = targetProcessName;
            this.targetSessionId = targetSessionId;
            this.targetGeneration = targetGeneration;
            this.uri = uri;
            this.flags = flags;
            this.mode = mode;
            this.mimeType = mimeType;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private final Map<String, MutableLease> leases = new LinkedHashMap<>();

    public synchronized OpenReservation reserveOpen(String operation, String callerInstance,
                                             String callerSessionId, long callerGeneration,
                                             String targetInstance, String targetPackage,
                                             int targetVirtualUserId, String targetProcessName,
                                             String targetSessionId, long targetGeneration,
                                             String uri, int flags, String mode, String mimeType,
                                             long nowMs) {
        requireText(operation, "operation");
        requireText(callerInstance, "callerInstance");
        requireText(callerSessionId, "callerSessionId");
        requireText(targetInstance, "targetInstance");
        requireText(targetPackage, "targetPackage");
        requireText(targetProcessName, "targetProcessName");
        requireText(targetSessionId, "targetSessionId");
        requireText(uri, "uri");
        if (!ComponentOperations.isProviderFileOpenOperation(operation)) {
            throw new IllegalArgumentException("NOT_PROVIDER_FILE_OPEN_OPERATION");
        }
        if (callerGeneration < 1 || targetGeneration < 1) throw new IllegalArgumentException("generation must be positive");
        if (targetVirtualUserId < 0) throw new IllegalArgumentException("targetVirtualUserId must be non-negative");
        if (flags <= 0) throw new IllegalArgumentException("flags must be positive");
        if (ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE.equals(operation)) {
            requireText(mimeType, RuntimeKeys.PROVIDER_MIME_TYPE);
            if (!"r".equals(mode)) throw new IllegalArgumentException("TYPED_ASSET_MODE_MUST_BE_READ_ONLY");
        } else {
            ProviderFileModes.requireAllowed(mode);
        }
        if (leases.size() >= MAX_ACTIVE_LEASES) throw new IllegalStateException("BROKER_PROVIDER_FILE_CAPACITY_EXHAUSTED");
        String token;
        do { token = UUID.randomUUID().toString(); } while (leases.containsKey(token));
        MutableLease lease = new MutableLease(token, operation, callerInstance, callerSessionId,
                callerGeneration, targetInstance, targetPackage, targetVirtualUserId, targetProcessName,
                targetSessionId, targetGeneration, uri, flags, mode, mimeType,
                Math.addExact(nowMs, LEASE_TTL_MS));
        leases.put(token, lease);
        return new OpenReservation(token);
    }

    public synchronized Lease commitOpen(OpenReservation reservation, Bundle result, long nowMs) {
        MutableLease lease = requireOpenReservation(reservation);
        requireNotExpired(lease, nowMs);
        String resultToken = result == null ? "" : result.getString(RuntimeKeys.FILE_TOKEN, "");
        if (!lease.token.equals(resultToken)) {
            closeResultResource(result);
            leases.remove(lease.token);
            throw new SecurityException("PROVIDER_FILE_TOKEN_NOT_BROKER_ISSUED");
        }
        String kind = result.getString(RuntimeKeys.FILE_DESCRIPTOR_KIND, "");
        String expectedKind = expectedKind(lease.operation);
        if (!expectedKind.equals(kind)) {
            throw invalidResult(lease, result, "PROVIDER_FILE_DESCRIPTOR_KIND_MISMATCH");
        }
        String resultMode = result.getString(RuntimeKeys.PROVIDER_FILE_MODE, "");
        if (!lease.mode.equals(resultMode)) {
            throw invalidResult(lease, result, "PROVIDER_FILE_MODE_MISMATCH");
        }
        String resultMime = result.getString(RuntimeKeys.PROVIDER_MIME_TYPE, "");
        if (!lease.mimeType.equals(resultMime)) {
            throw invalidResult(lease, result, "PROVIDER_FILE_MIME_MISMATCH");
        }
        Object fileValue = result.get(RuntimeKeys.FILE_DESCRIPTOR);
        Object assetValue = result.get(RuntimeKeys.ASSET_FILE_DESCRIPTOR);
        Closeable resource;
        if ("FILE".equals(kind)) {
            if (!(fileValue instanceof ParcelFileDescriptor) || assetValue != null) {
                throw invalidResult(lease, result, "PROVIDER_FILE_DESCRIPTOR_MISSING_OR_AMBIGUOUS");
            }
            resource = (ParcelFileDescriptor) fileValue;
        } else if ("ASSET".equals(kind) || "TYPED_ASSET".equals(kind)) {
            if (!(assetValue instanceof AssetFileDescriptor) || fileValue != null) {
                throw invalidResult(lease, result, "PROVIDER_ASSET_DESCRIPTOR_MISSING_OR_AMBIGUOUS");
            }
            resource = (AssetFileDescriptor) assetValue;
        } else {
            throw invalidResult(lease, result, "UNKNOWN_PROVIDER_FILE_DESCRIPTOR_KIND");
        }
        long startOffset = result.getLong(RuntimeKeys.FILE_START_OFFSET, -1L);
        long declaredLength = result.getLong(RuntimeKeys.FILE_DECLARED_LENGTH, -2L);
        if (startOffset < 0 || declaredLength < -1L) {
            throw invalidResult(lease, result, "INVALID_PROVIDER_FILE_RANGE");
        }
        if ("FILE".equals(kind) && (startOffset != 0L || declaredLength != -1L)) {
            throw invalidResult(lease, result, "PLAIN_FILE_RANGE_MISMATCH");
        }
        if (resource instanceof AssetFileDescriptor) {
            AssetFileDescriptor asset = (AssetFileDescriptor) resource;
            if (asset.getStartOffset() != startOffset || asset.getDeclaredLength() != declaredLength) {
                throw invalidResult(lease, result, "ASSET_FILE_RANGE_MISMATCH");
            }
        }
        lease.kind = kind;
        lease.startOffset = startOffset;
        lease.declaredLength = declaredLength;
        lease.resource = resource;
        lease.committed = true;
        return new Lease(lease);
    }

    public synchronized void rollbackOpen(OpenReservation reservation) {
        if (reservation == null) return;
        MutableLease lease = leases.get(reservation.token);
        if (lease != null && !lease.committed) leases.remove(reservation.token);
    }

    public synchronized Lease require(String token, long nowMs) {
        MutableLease lease = leases.get(token);
        if (lease == null || !lease.committed) throw new SecurityException("UNKNOWN_BROKER_PROVIDER_FILE_LEASE");
        requireNotExpired(lease, nowMs);
        return new Lease(lease);
    }

    public synchronized CloseReservation reserveClose(String token, String callerSessionId, long callerGeneration,
                                               String targetSessionId, long targetGeneration, long nowMs) {
        MutableLease lease = requireMutable(token, nowMs);
        validateSessions(lease, callerSessionId, callerGeneration, targetSessionId, targetGeneration);
        if (lease.inFlight) throw new IllegalStateException("PROVIDER_FILE_OPERATION_IN_FLIGHT");
        lease.inFlight = true;
        return new CloseReservation(token);
    }

    public synchronized Lease completeClose(CloseReservation reservation) {
        if (reservation == null) throw new IllegalArgumentException("reservation is required");
        MutableLease lease = leases.remove(reservation.token);
        if (lease == null || !lease.committed) throw new SecurityException("UNKNOWN_BROKER_PROVIDER_FILE_LEASE");
        closeQuietly(lease.resource);
        return new Lease(lease);
    }

    public synchronized Lease abort(String token) {
        MutableLease lease = leases.remove(token);
        if (lease != null) closeQuietly(lease.resource);
        return lease == null ? null : new Lease(lease);
    }

    synchronized List<Lease> invalidateSession(String sessionId, long generation) {
        List<Lease> removed = new ArrayList<>();
        for (MutableLease lease : new ArrayList<>(leases.values())) {
            if ((lease.callerSessionId.equals(sessionId) && lease.callerGeneration == generation)
                    || (lease.targetSessionId.equals(sessionId) && lease.targetGeneration == generation)) {
                leases.remove(lease.token);
                closeQuietly(lease.resource);
                removed.add(new Lease(lease));
            }
        }
        return Collections.unmodifiableList(removed);
    }

    synchronized List<Lease> invalidateInstance(String instanceId) {
        List<Lease> removed = new ArrayList<>();
        for (MutableLease lease : new ArrayList<>(leases.values())) {
            if (lease.callerInstance.equals(instanceId) || lease.targetInstance.equals(instanceId)) {
                leases.remove(lease.token);
                closeQuietly(lease.resource);
                removed.add(new Lease(lease));
            }
        }
        return Collections.unmodifiableList(removed);
    }

    synchronized List<Lease> purgeExpired(long nowMs) {
        List<Lease> removed = new ArrayList<>();
        for (MutableLease lease : new ArrayList<>(leases.values())) {
            if (lease.expiresAtMs <= nowMs) {
                leases.remove(lease.token);
                closeQuietly(lease.resource);
                removed.add(new Lease(lease));
            }
        }
        return Collections.unmodifiableList(removed);
    }

    synchronized int size() {
        int count = 0;
        for (MutableLease lease : leases.values()) if (lease.committed) count++;
        return count;
    }

    static void closeResultResource(Bundle result) {
        if (result == null) return;
        Object asset = result.get(RuntimeKeys.ASSET_FILE_DESCRIPTOR);
        Object file = result.get(RuntimeKeys.FILE_DESCRIPTOR);
        if (asset instanceof Closeable) closeQuietly((Closeable) asset);
        if (file instanceof Closeable && file != asset) closeQuietly((Closeable) file);
    }

    private MutableLease requireOpenReservation(OpenReservation reservation) {
        if (reservation == null) throw new IllegalArgumentException("reservation is required");
        MutableLease lease = leases.get(reservation.token);
        if (lease == null || lease.committed) throw new IllegalStateException("PROVIDER_FILE_RESERVATION_MISSING");
        return lease;
    }

    private MutableLease requireMutable(String token, long nowMs) {
        MutableLease lease = leases.get(token);
        if (lease == null || !lease.committed) throw new SecurityException("UNKNOWN_BROKER_PROVIDER_FILE_LEASE");
        requireNotExpired(lease, nowMs);
        return lease;
    }

    private IllegalArgumentException invalidResult(MutableLease lease, Bundle result, String message) {
        closeResultResource(result);
        leases.remove(lease.token);
        return new IllegalArgumentException(message);
    }

    private static String expectedKind(String operation) {
        if (ComponentOperations.PROVIDER_OPEN_FILE.equals(operation)) return "FILE";
        if (ComponentOperations.PROVIDER_OPEN_ASSET_FILE.equals(operation)) return "ASSET";
        if (ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE.equals(operation)) return "TYPED_ASSET";
        throw new IllegalArgumentException("Unknown Provider file operation: " + operation);
    }

    private static void validateSessions(MutableLease lease, String callerSessionId, long callerGeneration,
                                         String targetSessionId, long targetGeneration) {
        if (!lease.callerSessionId.equals(callerSessionId) || lease.callerGeneration != callerGeneration) {
            throw new SecurityException("PROVIDER_FILE_CALLER_SESSION_MISMATCH");
        }
        if (!lease.targetSessionId.equals(targetSessionId) || lease.targetGeneration != targetGeneration) {
            throw new SecurityException("PROVIDER_FILE_TARGET_SESSION_MISMATCH");
        }
    }

    private static void requireNotExpired(MutableLease lease, long nowMs) {
        if (lease.expiresAtMs <= nowMs) throw new SecurityException("BROKER_PROVIDER_FILE_LEASE_EXPIRED");
    }

    private static void closeQuietly(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (IOException ignored) { }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
