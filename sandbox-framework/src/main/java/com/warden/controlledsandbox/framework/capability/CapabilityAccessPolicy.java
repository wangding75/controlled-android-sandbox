package com.warden.controlledsandbox.framework.capability;

import java.util.List;
import java.util.Map;

/** Dynamic permission plus AppOps decision for protected host capabilities. */
public final class CapabilityAccessPolicy {
    public static final String CAMERA = "camera";
    public static final String MICROPHONE = "microphone";
    public static final String LOCATION = "location";

    private static final Map<String, List<Requirement>> REQUIREMENTS = Map.of(
            CAMERA, List.of(new Requirement("android.permission.CAMERA", "android:camera")),
            MICROPHONE, List.of(new Requirement("android.permission.RECORD_AUDIO", "android:record_audio")),
            LOCATION, List.of(
                    new Requirement("android.permission.ACCESS_FINE_LOCATION", "android:fine_location"),
                    new Requirement("android.permission.ACCESS_COARSE_LOCATION", "android:coarse_location")));

    @FunctionalInterface public interface PermissionAccess { boolean isGranted(String permission); }
    @FunctionalInterface public interface AppOpAccess { String mode(String appOp); }

    private final PermissionAccess permissions;
    private final AppOpAccess appOps;

    public CapabilityAccessPolicy(PermissionAccess permissions, AppOpAccess appOps) {
        this.permissions = java.util.Objects.requireNonNull(permissions, "permissions");
        this.appOps = java.util.Objects.requireNonNull(appOps, "appOps");
    }

    public boolean allowed(String capability) {
        List<Requirement> required = REQUIREMENTS.get(normalize(capability));
        if (required == null || required.isEmpty()) return false;
        for (Requirement item : required) {
            if (permissions.isGranted(item.permission) && appOpAllows(item.appOp)) return true;
        }
        return false;
    }

    public void require(String capability, String operation) {
        if (!allowed(capability)) {
            throw new SecurityException("GUEST_CAPABILITY_CALL_DENIED:" + normalize(capability)
                    + ":" + normalizeOperation(operation));
        }
    }

    public String explanation(String capability) {
        List<Requirement> required = REQUIREMENTS.get(normalize(capability));
        if (required == null) return "UNSUPPORTED_CAPABILITY";
        StringBuilder out = new StringBuilder();
        for (Requirement item : required) {
            if (out.length() > 0) out.append('|');
            out.append(item.permission).append('=')
                    .append(permissions.isGranted(item.permission) ? "GRANTED" : "DENIED")
                    .append(',').append(item.appOp).append('=').append(appOps.mode(item.appOp));
        }
        return out.toString();
    }

    private boolean appOpAllows(String name) {
        String mode = appOps.mode(name);
        return "DEFAULT".equals(mode) || "ALLOWED".equals(mode);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeOperation(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }

    private record Requirement(String permission, String appOp) { }
}
