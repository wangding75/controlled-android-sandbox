package com.warden.controlledsandbox.runtime.capability;

import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fails closed when an effectively granted protected capability has no installed method proxy. */
public final class CapabilityProxyReadiness {
    private CapabilityProxyReadiness() { }

    public static void require(Map<String, Boolean> installed,
                               List<VirtualPermissionSnapshot> permissions) {
        List<String> missing = missing(installed, permissions);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("CAPABILITY_PROXY_UNAVAILABLE:" + String.join(",", missing));
        }
    }

    public static List<String> missing(Map<String, Boolean> installed,
                                       List<VirtualPermissionSnapshot> permissions) {
        boolean camera = granted(permissions, "android.permission.CAMERA");
        boolean microphone = granted(permissions, "android.permission.RECORD_AUDIO");
        boolean location = granted(permissions, "android.permission.ACCESS_FINE_LOCATION")
                || granted(permissions, "android.permission.ACCESS_COARSE_LOCATION");
        ArrayList<String> out = new ArrayList<>();
        if (camera && !Boolean.TRUE.equals(installed.get("camera"))) out.add("camera");
        if (microphone && !Boolean.TRUE.equals(installed.get("audioCapture"))) out.add("audioCapture");
        if (location && !Boolean.TRUE.equals(installed.get("location"))) out.add("location");
        return java.util.Collections.unmodifiableList(out);
    }

    private static boolean granted(List<VirtualPermissionSnapshot> permissions, String name) {
        if (permissions == null) return false;
        for (VirtualPermissionSnapshot permission : permissions) {
            if (permission != null && name.equals(permission.name()) && permission.effectiveGranted()) return true;
        }
        return false;
    }
}
