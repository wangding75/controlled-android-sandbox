package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.PackageServiceResult;

/** Isolates runtime-permission orchestration from the central Broker service. */
final class RuntimePermissionCoordinator implements AutoCloseable {
    @FunctionalInterface interface SessionResolver {
        PermissionSession resolve(String sessionId, long generation);
    }
    record PermissionSession(String packageName, int virtualUserId, String sessionId,
                             long generation, boolean ready) { }

    private final RuntimePermissionGateway gateway;
    private final SessionResolver sessions;

    RuntimePermissionCoordinator(RuntimePermissionGateway gateway, SessionResolver sessions) {
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
    }

    PackageServiceResult request(String sessionId, long generation, String permission, int requestCode) {
        try {
            PermissionSession session = requireSession(sessionId, generation);
            return gateway.request(session.packageName(), session.virtualUserId(), value(permission, "permission"),
                    requestCode, session.sessionId(), session.generation());
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure("requestRuntimePermission", error);
        }
    }

    PackageServiceResult report(String sessionId, long generation, String permission, int requestCode,
                                boolean hostGranted, String reason) {
        try {
            PermissionSession session = requireSession(sessionId, generation);
            return gateway.report(session.packageName(), session.virtualUserId(), value(permission, "permission"),
                    requestCode, session.sessionId(), session.generation(), hostGranted,
                    reason == null ? "" : reason);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure("reportRuntimePermissionResult", error);
        }
    }

    private PermissionSession requireSession(String sessionId, long generation) {
        if (sessionId == null || sessionId.trim().isEmpty() || generation < 1) {
            throw new IllegalArgumentException("sessionId and generation are required");
        }
        PermissionSession session = sessions.resolve(sessionId.trim(), generation);
        if (session == null || !session.ready()) {
            throw new SecurityException("RUNTIME_PERMISSION_SESSION_NOT_READY");
        }
        if (!session.sessionId().equals(sessionId.trim()) || session.generation() != generation) {
            throw new SecurityException("RUNTIME_PERMISSION_SESSION_IDENTITY_MISMATCH");
        }
        return session;
    }

    private static String value(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static PackageServiceResult failure(String operation, Throwable error) {
        return PackageServiceResult.failure(operation, error.getClass().getSimpleName(),
                String.valueOf(error.getMessage()));
    }

    @Override public void close() {
        try { gateway.close(); } catch (Exception ignored) { }
    }
}
