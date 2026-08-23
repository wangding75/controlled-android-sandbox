package com.warden.controlledsandbox.domain.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Desensitized SX sx_config document. License/time-guard keys are dropped. */
public final class SxLegacyConfigDocument {
    public static final String SOURCE_SCHEMA = "sx-config-v1";
    public static final String TARGET_SCHEMA = "cas-instance-profile-v1";

    public final String packageName;
    public final int virtualUserId;
    public final String displayName;
    public final Map<String, String> location;
    public final Map<String, String> device;
    public final Map<String, String> network;
    public final Map<String, String> camera;
    public final Map<String, String> bluetooth;
    public final byte[] mediaBytes;
    public final String mediaKind;
    public final String sourceHash;

    public SxLegacyConfigDocument(String packageName, int virtualUserId, String displayName,
            Map<String, String> location, Map<String, String> device, Map<String, String> network,
            Map<String, String> camera, Map<String, String> bluetooth, byte[] mediaBytes,
            String mediaKind) {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("packageName is required");
        }
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range");
        }
        this.packageName = packageName.trim();
        this.virtualUserId = virtualUserId;
        this.displayName = displayName == null ? "" : displayName.trim();
        this.location = copy(location);
        this.device = copy(device);
        this.network = copy(network);
        this.camera = copy(camera);
        this.bluetooth = copy(bluetooth);
        this.mediaBytes = mediaBytes == null ? new byte[0] : mediaBytes.clone();
        this.mediaKind = mediaKind == null ? "" : mediaKind.trim().toLowerCase(Locale.ROOT);
        this.sourceHash = sha256(canonical());
    }

    public String canonical() {
        return SOURCE_SCHEMA + "|" + packageName + "|" + virtualUserId + "|" + displayName
                + "|loc=" + location + "|dev=" + device + "|net=" + network
                + "|cam=" + camera + "|bt=" + bluetooth
                + "|mediaKind=" + mediaKind + "|mediaBytes=" + mediaBytes.length;
    }

    private static Map<String, String> copy(Map<String, String> values) {
        return Map.copyOf(new LinkedHashMap<>(values == null ? Map.of() : values));
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte item : hashed) hex.append(String.format(Locale.ROOT, "%02x", item));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof SxLegacyConfigDocument that
                && sourceHash.equals(that.sourceHash);
    }

    @Override public int hashCode() {
        return Objects.hash(sourceHash);
    }
}
