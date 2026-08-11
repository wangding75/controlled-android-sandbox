package com.warden.controlledsandbox.sdk;

import java.util.Objects;

/**
 * The single SX-facing identity value translated to the runtime boundary.
 * Unknown runtime values use the documented sentinel values until a session exists.
 */
public final class SandboxIdentity {
    public static final int UNKNOWN_VIRTUAL_UID = -1;
    public static final int UNKNOWN_SLOT = -1;

    private final String packageName;
    private final String instanceId;
    private final int virtualUserId;
    private final int virtualUid;
    private final String processIdentity;
    private final String storageNamespace;
    private final String runtimeSession;
    private final long generation;
    private final int slot;
    private final String androidIdentityProfile;
    private final String appDataScope;

    public SandboxIdentity(String packageName, String instanceId, int virtualUserId,
                           int virtualUid, String processIdentity, String storageNamespace,
                           String runtimeSession, long generation, int slot,
                           String androidIdentityProfile, String appDataScope) {
        this.packageName = required(packageName, "packageName");
        this.instanceId = required(instanceId, "instanceId");
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        if (virtualUid < UNKNOWN_VIRTUAL_UID) throw new IllegalArgumentException("virtualUid is invalid");
        if (slot < UNKNOWN_SLOT || slot > 7) throw new IllegalArgumentException("slot is invalid");
        if (generation < 0) throw new IllegalArgumentException("generation is invalid");
        this.virtualUserId = virtualUserId;
        this.virtualUid = virtualUid;
        this.processIdentity = required(processIdentity, "processIdentity");
        this.storageNamespace = required(storageNamespace, "storageNamespace");
        this.runtimeSession = required(runtimeSession, "runtimeSession");
        this.generation = generation;
        this.slot = slot;
        this.androidIdentityProfile = required(androidIdentityProfile, "androidIdentityProfile");
        this.appDataScope = required(appDataScope, "appDataScope");
    }

    public static SandboxIdentity forInstance(String packageName, int virtualUserId,
                                              String processName, long versionCode,
                                              String apkSha256) {
        String instance = "u" + virtualUserId + ":" + packageName;
        String digest = required(apkSha256, "apkSha256");
        String revision = digest.length() > 12 ? digest.substring(0, 12) : digest;
        return new SandboxIdentity(packageName, instance, virtualUserId,
                UNKNOWN_VIRTUAL_UID, processName == null || processName.isBlank()
                        ? packageName : processName,
                "u" + virtualUserId + "/p/" + packageName,
                "pending:" + instance, 0L, UNKNOWN_SLOT,
                "android:" + packageName + ":u" + virtualUserId + ":v" + versionCode,
                "data/u" + virtualUserId + "/" + packageName + "/" + revision);
    }

    public String packageName() { return packageName; }
    public String instanceId() { return instanceId; }
    public int virtualUserId() { return virtualUserId; }
    public int virtualUid() { return virtualUid; }
    public String processIdentity() { return processIdentity; }
    public String storageNamespace() { return storageNamespace; }
    public String runtimeSession() { return runtimeSession; }
    public long generation() { return generation; }
    public int slot() { return slot; }
    public String androidIdentityProfile() { return androidIdentityProfile; }
    public String appDataScope() { return appDataScope; }

    public SandboxIdentity withRuntime(String session, long nextGeneration, int nextSlot,
                                       int nextVirtualUid, String processName) {
        return new SandboxIdentity(packageName, instanceId, virtualUserId,
                nextVirtualUid, processName, storageNamespace, session,
                nextGeneration, nextSlot, androidIdentityProfile, appDataScope);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof SandboxIdentity that)) return false;
        return virtualUserId == that.virtualUserId && packageName.equals(that.packageName)
                && instanceId.equals(that.instanceId) && processIdentity.equals(that.processIdentity)
                && storageNamespace.equals(that.storageNamespace) && runtimeSession.equals(that.runtimeSession)
                && generation == that.generation && slot == that.slot;
    }

    @Override public int hashCode() {
        return Objects.hash(packageName, instanceId, virtualUserId, processIdentity,
                storageNamespace, runtimeSession, generation, slot);
    }

    @Override public String toString() {
        return packageName + "@" + instanceId + "/" + processIdentity
                + " session=" + runtimeSession + " generation=" + generation;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
