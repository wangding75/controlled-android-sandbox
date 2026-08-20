package com.warden.controlledsandbox.framework.binder;

import android.os.Process;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityContext;

import java.util.Objects;

/**
 * The identity carried by every CAS Binder boundary.
 *
 * <p>The physical Binder caller is still the host process.  CAS must not try to forge the
 * kernel caller UID/PID in application code.  The virtual package/UID/process/session fields
 * are the identity used by the transaction policy and by returned/callback Binder leases; the
 * physical PID is retained only as diagnostic context.</p>
 */
public record BinderIdentity(
        String packageName,
        int virtualUid,
        int physicalPid,
        String opPackageName,
        String attributionTag,
        int virtualUserId,
        String sessionId,
        long generation,
        String processName) {

    public BinderIdentity {
        packageName = required(packageName, "packageName");
        opPackageName = required(opPackageName == null ? packageName : opPackageName,
                "opPackageName");
        processName = required(processName, "processName");
        sessionId = required(sessionId, "sessionId");
        attributionTag = normalize(attributionTag);
        if (virtualUid < 0 || physicalPid < 0 || virtualUserId < 0) {
            throw new IllegalArgumentException("Binder identity numbers must be non-negative");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    /** Creates the process-local identity used by framework hooks. */
    public static BinderIdentity fromGuestIdentity(GuestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return forGuest(identity.packageName(), identity.virtualUid(), Process.myPid(),
                identity.packageName(), null, identity.virtualUserId(),
                identity.packageName() + "@" + identity.processName(), identity.generation(),
                identity.processName());
    }

    /** Creates the same boundary identity from the framework-proxy installation contract. */
    public static BinderIdentity fromIdentityContext(IdentityContext context) {
        Objects.requireNonNull(context, "context");
        return forGuest(context.guestPackage(), context.guestUid(), Process.myPid(),
                context.guestPackage(), null, context.virtualUserId(),
                context.guestPackage() + "@" + context.guestProcess(), context.generation(),
                context.guestProcess());
    }

    public static BinderIdentity forGuest(
            String packageName,
            int virtualUid,
            int physicalPid,
            String opPackageName,
            String attributionTag,
            int virtualUserId,
            String sessionId,
            long generation,
            String processName) {
        return new BinderIdentity(packageName, virtualUid, physicalPid, opPackageName,
                attributionTag, virtualUserId, sessionId, generation, processName);
    }

    public BinderIdentity withAttributionTag(String value) {
        return new BinderIdentity(packageName, virtualUid, physicalPid, opPackageName, value,
                virtualUserId, sessionId, generation, processName);
    }

    public String scopeKey() {
        return packageName + ":u" + virtualUserId + ":" + processName + ":"
                + sessionId + ":g" + generation;
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim();
    }
}
