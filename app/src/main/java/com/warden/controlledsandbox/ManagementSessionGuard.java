package com.warden.controlledsandbox;

/** PID/UID ownership guard for one Binder capability object. */
final class ManagementSessionGuard {
    private final int ownerUid;
    private final int ownerPid;
    private boolean closed;

    ManagementSessionGuard(int ownerUid, int ownerPid) {
        if (ownerUid < 0 || ownerPid <= 0) throw new IllegalArgumentException("invalid session owner");
        this.ownerUid = ownerUid;
        this.ownerPid = ownerPid;
    }

    synchronized void requireOwner(int callingUid, int callingPid) {
        if (closed) throw new SecurityException("PACKAGE_MANAGEMENT_SESSION_CLOSED");
        if (callingUid != ownerUid || callingPid != ownerPid) {
            throw new SecurityException("PACKAGE_MANAGEMENT_CAPABILITY_NOT_OWNED");
        }
    }

    synchronized void close() { closed = true; }
    synchronized boolean isClosed() { return closed; }
}
