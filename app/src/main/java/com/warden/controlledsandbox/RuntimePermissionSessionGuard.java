package com.warden.controlledsandbox;

/** PID/UID ownership guard for one Runtime Broker permission capability. */
final class RuntimePermissionSessionGuard {
    private final int ownerUid;
    private final int ownerPid;
    private boolean closed;
    RuntimePermissionSessionGuard(int ownerUid, int ownerPid) {
        if (ownerUid < 0 || ownerPid <= 0) throw new IllegalArgumentException("invalid session owner");
        this.ownerUid = ownerUid; this.ownerPid = ownerPid;
    }
    synchronized void requireOwner(int callingUid, int callingPid) {
        if (closed) throw new SecurityException("RUNTIME_PERMISSION_SESSION_CLOSED");
        if (callingUid != ownerUid || callingPid != ownerPid) {
            throw new SecurityException("RUNTIME_PERMISSION_CAPABILITY_NOT_OWNED");
        }
    }
    synchronized void close(){closed=true;}
}
