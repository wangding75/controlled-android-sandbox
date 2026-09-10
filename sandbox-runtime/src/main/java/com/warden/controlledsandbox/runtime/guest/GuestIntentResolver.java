package com.warden.controlledsandbox.runtime.guest;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
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
    enum Owner { GUEST, HOST_SYSTEM }

    record Target(String packageName, String className, String processName, Owner owner) {
        Target(String packageName, String className, String processName) {
            this(packageName, className, processName, Owner.GUEST);
        }

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
            owner = owner == null ? Owner.GUEST : owner;
        }

        boolean hostOwned() { return owner == Owner.HOST_SYSTEM; }
    }

    private final GuestPackageSpec spec;
    private final PackageManager packageManager;
    private final Object hostPackageManagerService;

    GuestIntentResolver(GuestPackageSpec spec, PackageManager packageManager) {
        this(spec, packageManager, null);
    }

    GuestIntentResolver(GuestPackageSpec spec, PackageManager packageManager,
                        Object hostPackageManagerService) {
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.packageManager = java.util.Objects.requireNonNull(packageManager, "packageManager");
        this.hostPackageManagerService = hostPackageManagerService;
    }

    Target resolveOne(Intent intent, Kind kind) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        if (kind == Kind.SERVICE) {
            Target service = resolveOptionalService(intent);
            if (service != null) return service;
            throw new IllegalArgumentException("NO_GUEST_SERVICE_MATCH");
        }
        ResolveInfo resolved;
        switch (kind) {
            case ACTIVITY -> resolved = packageManager.resolveActivity(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            case RECEIVER -> {
                List<ResolveInfo> matches = packageManager.queryBroadcastReceivers(intent, 0);
                resolved = matches == null || matches.isEmpty() ? null : matches.get(0);
            }
            default -> throw new AssertionError(kind);
        }
        if (resolved == null) {
            logResolutionFailure(intent, kind);
            if (kind == Kind.ACTIVITY) throw new ActivityNotFoundException(intent.toString());
            throw new IllegalArgumentException("NO_GUEST_" + kind.name() + "_MATCH");
        }
        Target target = target(resolved, kind);
        android.util.Log.i("CS_GUEST_RESOLVE", "kind=" + kind + " component="
                + target.className() + " process=" + target.processName()
                + " caller=" + spec.packageName + " processName=" + spec.processName
                + " user=" + spec.virtualUserId + " revision=" + spec.packageRevision);
        return target;
    }

    /**
     * Resolves a Service for Context APIs whose Android contract has an absence return value.
     * A null here means that no virtual or explicitly constrained eligible system owner exists;
     * it does not represent a policy, transport, parse, or permission error.
     */
    Target resolveOptionalService(Intent intent) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        ResolveInfo resolved = packageManager.resolveService(intent, 0);
        if (resolved != null) {
            ServiceInfo info = resolved.serviceInfo;
            if (info == null) throw new IllegalStateException("RESOLVED_SERVICE_INFO_MISSING");
            Target target;
            if (isVirtualPackage(value(info.packageName))) {
                // VA/NBB resolve against the virtual PMS first. A virtual result remains
                // Guest-owned even when the same package is physically installed on Host.
                target = target(resolved, Kind.SERVICE);
            } else if (isAllowedHostService(intent, info)) {
                // The PackageManager adapter may return the explicitly addressed, exported
                // system-owner result after the virtual lookup misses. Preserve that owner
                // decision at the Context boundary; routing it through the Guest Broker would
                // incorrectly require a virtual package record for a Host component.
                target = hostTarget(info);
                android.util.Log.i("CS_GUEST_SERVICE_ROUTE", "owner=HOST_SYSTEM package="
                        + target.packageName() + " component=" + target.className()
                        + " process=" + target.processName() + " caller=" + spec.packageName
                        + " action=" + value(intent.getAction()) + " user=" + spec.virtualUserId);
            } else {
                if (hasHostServiceAddress(intent)) {
                    throw new SecurityException("HOST_SERVICE_OWNER_DENIED");
                }
                logResolutionFailure(intent, Kind.SERVICE);
                return null;
            }
            android.util.Log.i("CS_GUEST_RESOLVE", "kind=SERVICE component="
                    + target.className() + " process=" + target.processName()
                    + " caller=" + spec.packageName + " processName=" + spec.processName
                    + " user=" + spec.virtualUserId + " revision=" + spec.packageRevision);
            return target;
        }
        rejectExplicitVirtualServiceAccess(intent);
        boolean hostAddressed = hasHostServiceAddress(intent);
        HostPackageManagerBridge.Lookup<ServiceInfo> hostLookup = !hostAddressed
                || hostPackageManagerService == null ? null
                : HostPackageManagerBridge.resolveService(hostPackageManagerService, intent,
                        HostPackageManagerBridge.physicalUserId());
        if (hostLookup != null && !hostLookup.isResult() && !hostLookup.isEmpty()) {
            throw new IllegalStateException("HOST_PMS_SERVICE_LOOKUP_FAILED:"
                    + hostLookup.diagnostic());
        }
        ServiceInfo hostService = hostLookup == null ? null : hostLookup.value();
        if (isAllowedHostService(intent, hostService)) {
            Target target = hostTarget(hostService);
            android.util.Log.i("CS_GUEST_SERVICE_ROUTE", "owner=HOST_SYSTEM package="
                    + target.packageName() + " component=" + target.className()
                    + " process=" + target.processName() + " caller=" + spec.packageName
                    + " action=" + value(intent.getAction()) + " user=" + spec.virtualUserId);
            return target;
        }
        if (hostService != null && hostAddressed) {
            // The raw PMS did find a Service, but it is not an eligible system owner. This is a
            // policy denial, not a normal optional-Service absence and must not turn into a bind
            // to the physical user app.
            throw new SecurityException("HOST_SERVICE_OWNER_DENIED");
        }
        logResolutionFailure(intent, Kind.SERVICE);
        return null;
    }

    private void rejectExplicitVirtualServiceAccess(Intent intent) {
        ComponentName component = intent == null ? null : intent.getComponent();
        if (component == null) return;
        ServiceInfo info = explicitServiceInfo(component);
        if (info == null || !isVirtualPackage(value(info.packageName)) || !info.enabled
                || spec.packageName.equals(info.packageName)) return;
        if (!info.exported) throw new SecurityException("VIRTUAL_SERVICE_NOT_EXPORTED");
        if (!value(info.permission).isEmpty()) {
            throw new SecurityException("VIRTUAL_SERVICE_PERMISSION_DENIED");
        }
    }

    private boolean hasHostServiceAddress(Intent intent) {
        return intent != null && (intent.getComponent() != null
                || !value(intent.getPackage()).isEmpty());
    }

    private boolean isAllowedHostService(Intent intent, ServiceInfo info) {
        if (info == null || info.applicationInfo == null || !info.enabled || !info.exported) {
            return false;
        }
        // An unqualified implicit Intent must never enumerate or bind arbitrary host system
        // services. Only an explicit component or a package-constrained lookup can opt into
        // the narrow system-owner route.
        ComponentName requested = intent == null ? null : intent.getComponent();
        String requestedPackage = intent == null ? "" : value(intent.getPackage());
        String packageName = value(info.packageName);
        String applicationPackage = value(info.applicationInfo.packageName);
        if (packageName.isEmpty() || (!applicationPackage.isEmpty()
                && !packageName.equals(applicationPackage))) return false;
        if (requested != null && (!packageName.equals(requested.getPackageName())
                || !value(info.name).equals(requested.getClassName()))) return false;
        if (requested == null && (requestedPackage.isEmpty()
                || !packageName.equals(requestedPackage))) return false;
        // A package already in the virtual universe is Guest-owned even if the raw host PMS
        // still has a physical installation with the same package name.
        if (isVirtualPackage(packageName)) return false;
        return HostPackageManagerBridge.isSystemOwner(info.applicationInfo);
    }

    private boolean isVirtualPackage(String packageName) {
        if (spec.packageName.equals(packageName)) return true;
        for (com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot projection
                : spec.packageUniverse) {
            if (projection != null && packageName.equals(projection.packageState().packageName())) {
                return true;
            }
        }
        return false;
    }

    private static Target hostTarget(ServiceInfo info) {
        return new Target(info.packageName, info.name, info.processName, Owner.HOST_SYSTEM);
    }

    /**
     * Keep a failed Guest resolution actionable without changing the fail-closed result.  The
     * real PackageManager call above is still the authority; this is a bounded diagnostic query
     * made once for the failed Intent so explicit-component failures can be distinguished from
     * missing filters, visibility, enabled/exported, or revision projection defects.
     */
    private void logResolutionFailure(Intent intent, Kind kind) {
        StringBuilder line = new StringBuilder(512);
        line.append("kind=").append(kind)
                .append(" caller=").append(spec.packageName)
                .append(" callerProcess=").append(spec.processName)
                .append(" user=").append(spec.virtualUserId)
                .append(" revision=").append(spec.packageRevision)
                .append(" resolveServiceInput=").append(oneLine(intent.toString()))
                .append(" action=").append(value(intent.getAction()))
                .append(" component=").append(component(intent))
                .append(" package=").append(value(intent.getPackage()))
                .append(" flags=0x").append(Integer.toHexString(intent.getFlags()))
                .append(" data=").append(value(intent.getDataString()))
                .append(" type=").append(value(intent.getType()))
                .append(" categories=").append(categories(intent));
        List<ResolveInfo> candidates = Collections.emptyList();
        try {
            if (kind == Kind.SERVICE) {
                candidates = queryIntentServices(intent);
            } else if (kind == Kind.RECEIVER) {
                candidates = packageManager.queryBroadcastReceivers(intent, 0);
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            line.append(" candidateQueryError=").append(oneLine(error.toString()));
        }
        line.append(" candidates=").append(candidateList(candidates, kind));
        if (kind == Kind.SERVICE && hostPackageManagerService != null
                && hasHostServiceAddress(intent)) {
            HostPackageManagerBridge.Lookup<ServiceInfo> hostLookup =
                    HostPackageManagerBridge.resolveService(hostPackageManagerService, intent,
                            HostPackageManagerBridge.physicalUserId());
            ServiceInfo host = hostLookup.value();
            line.append(" hostCandidate=").append(serviceCandidate(host))
                    .append(" hostOwner=").append(isAllowedHostService(intent, host)
                            ? "HOST_SYSTEM" : "NOT_ROUTABLE")
                    .append(" hostLookup=").append(hostLookup.diagnostic());
        }
        String rejection = candidates == null || candidates.isEmpty()
                ? "NO_FILTER_OR_VISIBLE_CANDIDATE" : "CANDIDATE_REJECTED_BY_RESOLVE";
        if (intent.getComponent() != null) {
            line.append(" explicitComponentInfo=")
                    .append(explicitComponentInfo(intent.getComponent(), kind));
            if (candidates == null || candidates.isEmpty()) {
                rejection = "EXPLICIT_COMPONENT_NOT_PROJECTED_OR_NOT_VISIBLE";
            }
        }
        line.append(" rejection=").append(rejection);
        android.util.Log.e("CS_GUEST_RESOLVE_FAIL", line.toString());
    }

    private String explicitComponentInfo(ComponentName component, Kind kind) {
        if (kind != Kind.SERVICE || component == null) return "NA";
        ServiceInfo info = explicitServiceInfo(component);
        return info == null ? "null" : serviceCandidate(info);
    }

    private ServiceInfo explicitServiceInfo(ComponentName component) {
        try {
            java.lang.reflect.Method method = packageManager.getClass().getMethod(
                    "getServiceInfo", ComponentName.class, int.class);
            Object value = method.invoke(packageManager, component, 0);
            return value instanceof ServiceInfo info ? info : null;
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof PackageManager.NameNotFoundException) return null;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("SERVICE_INFO_QUERY_FAILED", cause);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return null;
        }
    }

    private static String candidateList(List<ResolveInfo> values, Kind kind) {
        if (values == null || values.isEmpty()) return "[]";
        ArrayList<String> result = new ArrayList<>();
        for (ResolveInfo value : values) {
            if (value == null) continue;
            if (kind == Kind.SERVICE && value.serviceInfo != null) {
                result.add(serviceCandidate(value.serviceInfo));
            } else if (kind == Kind.ACTIVITY && value.activityInfo != null) {
                result.add(value.activityInfo.packageName + "/" + value.activityInfo.name);
            } else {
                result.add(oneLine(value.toString()));
            }
        }
        return result.toString();
    }

    private static String serviceCandidate(ServiceInfo info) {
        if (info == null) return "null";
        ApplicationInfo application = info.applicationInfo;
        return info.packageName + "/" + info.name
                + " process=" + value(info.processName)
                + " enabled=" + info.enabled
                + " exported=" + info.exported
                + " permission=" + value(info.permission)
                + " isolated=" + booleanField(info, "isolatedProcess", false)
                + " directBootAware=" + booleanField(info, "directBootAware", false)
                + " foregroundServiceType=" + intField(info, "foregroundServiceType", 0)
                + " appPackage=" + (application == null ? "" : value(application.packageName))
                + " appSource=" + (application == null ? "" : value(application.sourceDir));
    }

    private static String component(Intent intent) {
        ComponentName component = intent.getComponent();
        return component == null ? "" : component.getPackageName() + "/" + component.getClassName();
    }

    private static String categories(Intent intent) {
        return intent.getCategories() == null ? "[]"
                : new ArrayList<>(intent.getCategories()).toString();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
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
        Bundle request = requestWithoutIntent(target);
        com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.encodeComponent(
                request, intent);
        return request;
    }

    private Bundle requestWithoutIntent(Target target) {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, target.packageName());
        request.putString(RuntimeKeys.COMPONENT_CLASS, target.className());
        request.putString(RuntimeKeys.PROCESS_NAME,
                target.processName().isEmpty() ? target.packageName() : target.processName());
        return request;
    }

    Bundle activityRequest(Intent intent, Target target) {
        Bundle request = requestWithoutIntent(target);
        com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.encodeActivity(
                request, intent);
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
        com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.encodeComponent(
                request, intent);
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
            return new Target(info.packageName, info.name, info.processName, Owner.GUEST);
        }
        ActivityInfo info = resolved.activityInfo;
        if (info == null) throw new IllegalStateException("RESOLVED_ACTIVITY_INFO_MISSING");
        return new Target(info.packageName, info.name, info.processName, Owner.GUEST);
    }

    private static String value(String value) { return value == null ? "" : value; }

    @SuppressWarnings("unchecked")
    private List<ResolveInfo> queryIntentServices(Intent intent) {
        try {
            java.lang.reflect.Method method = packageManager.getClass().getMethod(
                    "queryIntentServices", Intent.class, int.class);
            Object result = method.invoke(packageManager, intent, 0);
            return result instanceof List<?> list ? (List<ResolveInfo>) list : Collections.emptyList();
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            return Collections.emptyList();
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return Collections.emptyList();
        }
    }

    private static boolean booleanField(Object target, String name, boolean fallback) {
        try {
            java.lang.reflect.Field field = target.getClass().getField(name);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return fallback;
        }
    }
}
