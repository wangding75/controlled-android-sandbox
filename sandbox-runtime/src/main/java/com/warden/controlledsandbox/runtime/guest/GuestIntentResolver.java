package com.warden.controlledsandbox.runtime.guest;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resolves explicit and implicit Guest intents strictly against the virtual package metadata. */
final class GuestIntentResolver {
    enum Kind { ACTIVITY, SERVICE, RECEIVER }

    record Target(String className, String processName) {
        Target {
            if (className == null || className.trim().isEmpty()) {
                throw new IllegalArgumentException("component class is required");
            }
            className = className.trim();
            processName = processName == null ? "" : processName.trim();
        }
    }

    private final GuestPackageSpec spec;
    private final PackageManager packageManager;

    GuestIntentResolver(GuestPackageSpec spec, PackageManager packageManager) {
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.packageManager = java.util.Objects.requireNonNull(packageManager, "packageManager");
    }

    Target resolveOne(Intent intent, Kind kind) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        requireGuestScope(intent);
        ResolveInfo resolved;
        switch (kind) {
            case ACTIVITY -> resolved = packageManager.resolveActivity(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            case SERVICE -> resolved = packageManager.resolveService(intent, 0);
            case RECEIVER -> {
                List<ResolveInfo> matches = packageManager.queryBroadcastReceivers(intent, 0);
                resolved = matches == null || matches.isEmpty() ? null : matches.get(0);
            }
            default -> throw new AssertionError(kind);
        }
        if (resolved == null) {
            if (kind == Kind.ACTIVITY) throw new ActivityNotFoundException(intent.toString());
            throw new IllegalArgumentException("NO_GUEST_" + kind.name() + "_MATCH");
        }
        Target target = target(resolved, kind);
        android.util.Log.i("CS_GUEST_RESOLVE", "kind=" + kind + " component="
                + target.className() + " process=" + target.processName());
        return target;
    }

    List<Target> resolveReceivers(Intent intent) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        requireGuestScope(intent);
        List<ResolveInfo> matches = packageManager.queryBroadcastReceivers(intent, 0);
        if (matches == null || matches.isEmpty()) return List.of();
        ArrayList<Target> targets = new ArrayList<>();
        for (ResolveInfo match : matches) targets.add(target(match, Kind.RECEIVER));
        return Collections.unmodifiableList(targets);
    }

    Bundle request(Intent intent, Target target) {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.COMPONENT_CLASS, target.className());
        request.putString(RuntimeKeys.PROCESS_NAME,
                target.processName().isEmpty() ? spec.packageName : target.processName());
        applyIntent(request, intent);
        return request;
    }

    static void applyIntent(Bundle request, Intent intent) {
        com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.encode(request, intent);
    }

    void requireGuestScopeForBroadcast(Intent intent) {
        requireGuestScope(intent);
    }

    boolean isForeignPackage(Intent intent) {
        if (intent == null) return false;
        String packageName = intent.getPackage();
        if (packageName != null && !packageName.isEmpty() && !spec.packageName.equals(packageName)) {
            return true;
        }
        ComponentName component = intent.getComponent();
        return component != null && !spec.packageName.equals(component.getPackageName());
    }

    private void requireGuestScope(Intent intent) {
        String packageName = intent.getPackage();
        if (packageName != null && !packageName.isEmpty() && !spec.packageName.equals(packageName)) {
            throw new SecurityException("CROSS_PACKAGE_INTENT_DENIED:" + packageName);
        }
        ComponentName component = intent.getComponent();
        if (component != null && !spec.packageName.equals(component.getPackageName())) {
            throw new SecurityException("CROSS_PACKAGE_COMPONENT_DENIED:" + component.flattenToShortString());
        }
    }

    private static Target target(ResolveInfo resolved, Kind kind) {
        if (kind == Kind.SERVICE) {
            ServiceInfo info = resolved.serviceInfo;
            if (info == null) throw new IllegalStateException("RESOLVED_SERVICE_INFO_MISSING");
            return new Target(info.name, info.processName);
        }
        ActivityInfo info = resolved.activityInfo;
        if (info == null) throw new IllegalStateException("RESOLVED_ACTIVITY_INFO_MISSING");
        return new Target(info.name, info.processName);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
