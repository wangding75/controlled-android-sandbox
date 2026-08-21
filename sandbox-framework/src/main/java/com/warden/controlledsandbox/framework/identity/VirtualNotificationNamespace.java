package com.warden.controlledsandbox.framework.identity;

/** Stable host namespace used for notification channels owned by a Guest identity. */
public final class VirtualNotificationNamespace {
    private VirtualNotificationNamespace() { }

    public static String hostChannelId(String packageName, int virtualUserId, String guestId) {
        String prefix = prefix(packageName, virtualUserId);
        String value = guestId == null ? "" : guestId;
        return value.startsWith(prefix) ? value : prefix + value;
    }

    public static String guestChannelId(String packageName, int virtualUserId, String hostId) {
        String prefix = prefix(packageName, virtualUserId);
        String value = hostId == null ? "" : hostId;
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static String prefix(String packageName, int virtualUserId) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId is negative");
        return "cs.u" + virtualUserId + "." + packageName + ".";
    }
}
