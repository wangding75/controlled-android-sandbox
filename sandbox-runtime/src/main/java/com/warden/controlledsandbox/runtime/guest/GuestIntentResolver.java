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

    record Target(String packageName, String className, String processName) {
        Target {
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new IllegalArgumentException("component package is required");
            }
            if (className == null || className.trim().isEmpty()) {
                throw new IllegalArgumentException("component class is required");
            }
            packageName = packageName.trim();
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
        List<ResolveInfo> matches = packageManager.queryBroadcastReceivers(intent, 0);
        if (matches == null || matches.isEmpty()) return List.of();
        ArrayList<Target> targets = new ArrayList<>();
        for (ResolveInfo match : matches) targets.add(target(match, Kind.RECEIVER));
        return Collections.unmodifiableList(targets);
    }

    Bundle request(Intent intent, Target target) {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, target.packageName());
        request.putString(RuntimeKeys.COMPONENT_CLASS, target.className());
        request.putString(RuntimeKeys.PROCESS_NAME,
                target.processName().isEmpty() ? target.packageName() : target.processName());
        applyIntent(request, intent);
        return request;
    }

    Bundle activityRequest(Intent intent, Target target) {
        Bundle request = request(intent, target);
        projectActivityLaunchContract(request, intent, target);
        return request;
    }

    /**
     * The virtual PackageManager result is also the input to the Broker task transaction.
     * Keeping this projection here makes the resolver the single source of truth: a caller that
     * launches an explicit or resolved Activity gets the same launch contract it just queried.
     * Previously only the PackageManager-facing ActivityInfo was correct; the Broker silently
     * defaulted every real launch to STANDARD/NONE and therefore could not reproduce singleTask,
     * document tasks, affinity or ActivityInfo noHistory/exclude-from-recents semantics.
     */
    private void projectActivityLaunchContract(Bundle request, Intent intent, Target target) {
        try {
            java.lang.reflect.Method getActivityInfo;
            try {
                getActivityInfo = packageManager.getClass().getMethod(
                        "getActivityInfo", ComponentName.class, int.class);
            } catch (NoSuchMethodException unavailableOnStub) {
                // Host-side source harnesses intentionally expose only the resolver surface. A
                // real Android PackageManager always has getActivityInfo; keep the typed request
                // compatible with the reduced harness while using the authoritative projection
                // whenever the platform method is present.
                return;
            }
            Object info = getActivityInfo.invoke(packageManager, new ComponentName(
                    target.packageName(), target.className()), PackageManager.GET_META_DATA);
            if (info == null) throw new ActivityNotFoundException(target.packageName()
                    + "/" + target.className());
            request.putString(RuntimeKeys.ACTIVITY_LAUNCH_MODE,
                    launchModeName(intField(info, "launchMode", 0)));
            request.putString(RuntimeKeys.DOCUMENT_LAUNCH_MODE,
                    documentLaunchModeName(intField(info, "documentLaunchMode", 0)));
            request.putString(RuntimeKeys.TASK_AFFINITY,
                    stringField(info, "taskAffinity", target.packageName()));
            request.putString(RuntimeKeys.DOCUMENT_KEY, documentKey(intent));

            int flags = request.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0);
            if (hasActivityFlag(info, "FLAG_NO_HISTORY")) {
                flags |= com.warden.controlledsandbox.framework.activity.LaunchFlags.NO_HISTORY;
            }
            if (hasActivityFlag(info, "FLAG_EXCLUDE_FROM_RECENTS")) {
                flags |= com.warden.controlledsandbox.framework.activity.LaunchFlags.EXCLUDE_FROM_RECENTS;
            }
            request.putInt(RuntimeKeys.ACTIVITY_FLAGS, flags);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            throw new ActivityNotFoundException(target.packageName() + "/" + target.className());
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("GUEST_ACTIVITY_CONTRACT_QUERY_FAILED", error);
        }
    }

    private static boolean hasActivityFlag(Object info, String fieldName) {
        if (info == null) return false;
        try {
            java.lang.reflect.Field field = ActivityInfo.class.getField(fieldName);
            return (intField(info, "flags", 0) & field.getInt(null)) != 0;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return false;
        }
    }

    private static int intField(Object target, String name, int fallback) {
        try {
            java.lang.reflect.Field field = target.getClass().getField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return fallback;
        }
    }

    private static String stringField(Object target, String name, String fallback) {
        try {
            java.lang.reflect.Field field = target.getClass().getField(name);
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof String string && !string.trim().isEmpty()
                    ? string : fallback;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return fallback;
        }
    }

    private static String launchModeName(int value) {
        return switch (value) {
            case 1 -> "SINGLE_TOP";
            case 2 -> "SINGLE_TASK";
            case 3 -> "SINGLE_INSTANCE";
            case 4 -> "SINGLE_INSTANCE_PER_TASK";
            default -> "STANDARD";
        };
    }

    private static String documentLaunchModeName(int value) {
        return switch (value) {
            case 1 -> "INTO_EXISTING";
            case 2 -> "ALWAYS";
            case 3 -> "NEVER";
            default -> "NONE";
        };
    }

    private static String documentKey(Intent intent) {
        if (intent == null) return "";
        StringBuilder key = new StringBuilder(256);
        key.append(intent.getAction() == null ? "" : intent.getAction());
        key.append('|').append(intent.getData() == null ? "" : intent.getData());
        key.append('|').append(intent.getType() == null ? "" : intent.getType());
        ComponentName component = intent.getComponent();
        key.append('|').append(component == null ? ""
                : component.getPackageName() + "/" + component.getClassName());
        if (intent.getCategories() != null) {
            java.util.ArrayList<String> categories = new java.util.ArrayList<>(intent.getCategories());
            java.util.Collections.sort(categories);
            key.append('|').append(String.join(",", categories));
        }
        return key.toString();
    }

    static void applyIntent(Bundle request, Intent intent) {
        com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.encode(request, intent);
    }

    void requireGuestScopeForBroadcast(Intent intent) {
        // Package visibility, exported and permission checks are owned by the virtual
        // PackageManager resolver and the Broker target-session admission path.  Do not reject
        // a cross-package intent here: Android resolves it in the caller's virtual package
        // universe and delivers it to the selected target package.
    }

    private static Target target(ResolveInfo resolved, Kind kind) {
        if (kind == Kind.SERVICE) {
            ServiceInfo info = resolved.serviceInfo;
            if (info == null) throw new IllegalStateException("RESOLVED_SERVICE_INFO_MISSING");
            return new Target(info.packageName, info.name, info.processName);
        }
        ActivityInfo info = resolved.activityInfo;
        if (info == null) throw new IllegalStateException("RESOLVED_ACTIVITY_INFO_MISSING");
        return new Target(info.packageName, info.name, info.processName);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
