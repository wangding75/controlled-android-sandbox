package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reads only system-owned host package permission state; caller data is never trusted. */
final class HostPermissionStateResolver {
    static final class HostState {
        final boolean declaredByHost;
        final boolean grantedToHost;
        final boolean runtimeRequestable;
        HostState(boolean declaredByHost, boolean grantedToHost, boolean runtimeRequestable) {
            this.declaredByHost = declaredByHost;
            this.grantedToHost = grantedToHost;
            this.runtimeRequestable = runtimeRequestable;
        }
    }

    private final Context context;
    private volatile Set<String> declared;

    HostPermissionStateResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    HostState resolve(String permission) {
        PermissionCapabilityRegistry.Capability capability = PermissionCapabilityRegistry.resolve(permission);
        boolean hostDeclared = declaredPermissions().contains(permission);
        boolean hostGranted = hostDeclared && context.getPackageManager().checkPermission(
                permission, context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
        return new HostState(hostDeclared, hostGranted,
                hostDeclared && capability.runtimeControlled && !hostGranted);
    }

    private Set<String> declaredPermissions() {
        Set<String> current = declared;
        if (current != null) return current;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info != null && info.requestedPermissions != null) {
                result.addAll(Arrays.asList(info.requestedPermissions));
            }
        } catch (Exception ignored) {
            // Fail closed: missing package metadata means no host capability is available.
        }
        declared = java.util.Collections.unmodifiableSet(result);
        return declared;
    }
}
