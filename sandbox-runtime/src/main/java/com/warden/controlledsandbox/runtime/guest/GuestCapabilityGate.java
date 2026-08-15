package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed gate for protected system-service operations. */
final class GuestCapabilityGate {
    private static final Map<String, List<String>> REQUIRED_ANY = requirements();
    private volatile Map<String, Boolean> effectivePermissions = Map.of();

    GuestCapabilityGate(List<VirtualPermissionSnapshot> permissions) { replace(permissions); }

    synchronized void replace(List<VirtualPermissionSnapshot> permissions) {
        Map<String, Boolean> updated = new LinkedHashMap<>();
        if (permissions != null) {
            for (VirtualPermissionSnapshot permission : permissions) {
                if (permission != null) {
                    updated.put(permission.name(), permission.effectiveGranted());
                }
            }
        }
        effectivePermissions = java.util.Collections.unmodifiableMap(updated);
    }

    void requireService(String serviceName) {
        List<String> required = REQUIRED_ANY.get(value(serviceName));
        if (required == null || required.isEmpty()) return;
        for (String permission : required) {
            if (Boolean.TRUE.equals(effectivePermissions.get(permission))) return;
        }
        throw new SecurityException("GUEST_CAPABILITY_NOT_GRANTED:" + serviceName
                + ":" + String.join("|", required));
    }

    int checkPermission(String permission) {
        return Boolean.TRUE.equals(effectivePermissions.get(permission))
                ? android.content.pm.PackageManager.PERMISSION_GRANTED
                : android.content.pm.PackageManager.PERMISSION_DENIED;
    }

    private static Map<String, List<String>> requirements() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("camera", List.of("android.permission.CAMERA"));
        result.put("location", List.of("android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
