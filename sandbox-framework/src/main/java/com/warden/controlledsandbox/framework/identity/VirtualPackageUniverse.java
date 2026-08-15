package com.warden.controlledsandbox.framework.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Metadata-only virtual PackageManager universe for one virtual user.
 *
 * <p>The current Guest is always present. Other installed Guest packages are projected only
 * when the current package's manifest visibility rules allow them. This keeps package identity,
 * intent resolution and installed-package enumeration on one coherent model instead of mixing
 * a current-package-only projection with raw Host PMS results.</p>
 */
public final class VirtualPackageUniverse {
    private final Map<String, VirtualPackageMetadata> packages;

    public VirtualPackageUniverse(List<VirtualPackageMetadata> values) {
        Map<String, VirtualPackageMetadata> copy = new LinkedHashMap<>();
        for (VirtualPackageMetadata value : values == null ? List.<VirtualPackageMetadata>of() : values) {
            if (value == null) continue;
            copy.putIfAbsent(value.packageName(), value);
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("virtual package universe is empty");
        packages = Collections.unmodifiableMap(copy);
    }

    public static VirtualPackageUniverse single(VirtualPackageMetadata metadata) {
        return new VirtualPackageUniverse(List.of(metadata));
    }

    public List<VirtualPackageMetadata> packages() {
        return Collections.unmodifiableList(new ArrayList<>(packages.values()));
    }

    public VirtualPackageMetadata packageMetadata(String packageName) {
        return packageName == null ? null : packages.get(packageName);
    }

    public boolean isVisibleTo(String callerPackage, String targetPackage) {
        VirtualPackageMetadata caller = packageMetadata(callerPackage);
        VirtualPackageMetadata target = packageMetadata(targetPackage);
        if (caller == null || target == null) return false;
        if (caller.packageName().equals(target.packageName())) return true;
        if (caller.queryPackages().contains(target.packageName())) return true;
        for (VirtualPackageMetadata.Component component : target.components()) {
            if (component.type() == VirtualPackageMetadata.Type.PROVIDER) {
                for (String authority : component.authority().split(";")) {
                    if (caller.queryProviderAuthorities().contains(authority.trim())) return true;
                }
            }
        }
        for (VirtualPackageMetadata.Filter query : caller.queryIntentFilters()) {
            if (target.matchesQueryFilter(query)) return true;
        }
        return false;
    }

    public ApplicationInfo applicationInfo(String callerPackage, String targetPackage) {
        VirtualPackageMetadata target = visibleTarget(callerPackage, targetPackage);
        return target == null ? null : target.applicationInfo();
    }

    public PackageInfo packageInfo(String callerPackage, String targetPackage, long flags) {
        VirtualPackageMetadata target = visibleTarget(callerPackage, targetPackage);
        return target == null ? null : target.packageInfo(flags);
    }

    public ComponentInfo componentInfo(String callerPackage, ComponentName name,
                                       VirtualPackageMetadata.Type type, long flags) {
        if (name == null || !isVisibleTo(callerPackage, name.getPackageName())) return null;
        VirtualPackageMetadata target = packageMetadata(name.getPackageName());
        return target == null ? null : target.componentInfo(name, type, flags);
    }

    public ProviderInfo provider(String callerPackage, String authority, long flags) {
        return provider(callerPackage, authority, flags, Collections.emptySet());
    }

    public ProviderInfo provider(String callerPackage, String authority, long flags,
                                 java.util.Set<String> callerPermissions) {
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            VirtualPackageMetadata.Component component = target.providerComponent(authority);
            // PackageManager.resolveContentProvider() exposes provider metadata after package
            // visibility has been established.  exported/read/write permissions are enforced
            // by the provider transport when the caller actually acquires or uses it; applying
            // that check here hides legitimate non-exported provider metadata and diverges from
            // Android's PMS/ContentResolver split.
            if (component == null) continue;
            ProviderInfo provider = target.provider(authority, flags);
            if (provider != null) return provider;
        }
        return null;
    }

    public ResolveInfo resolve(String callerPackage, Intent intent,
                               VirtualPackageMetadata.Type type, long flags) {
        List<ResolveInfo> values = query(callerPackage, intent, type, flags, Collections.emptySet());
        return values.isEmpty() ? null : values.get(0);
    }

    public ResolveInfo resolve(String callerPackage, Intent intent,
                               VirtualPackageMetadata.Type type, long flags,
                               java.util.Set<String> callerPermissions) {
        List<ResolveInfo> values = query(callerPackage, intent, type, flags, callerPermissions);
        return values.isEmpty() ? null : values.get(0);
    }

    public List<ResolveInfo> query(String callerPackage, Intent intent,
                                   VirtualPackageMetadata.Type type, long flags) {
        return query(callerPackage, intent, type, flags, Collections.emptySet());
    }

    public List<ResolveInfo> query(String callerPackage, Intent intent,
                                   VirtualPackageMetadata.Type type, long flags,
                                   java.util.Set<String> callerPermissions) {
        ArrayList<ResolveInfo> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            for (ResolveInfo value : target.query(intent, type, flags)) {
                if (isAccessible(callerPackage, target, value, type, callerPermissions)) {
                    result.add(value);
                }
            }
        }
        result.sort(Comparator.comparingInt((ResolveInfo value) -> value.priority).reversed()
                .thenComparing(Comparator.comparingInt((ResolveInfo value) -> value.match).reversed())
                .thenComparing(value -> componentName(value, type)));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<ResolveInfo> unique = new ArrayList<>();
        for (ResolveInfo value : result) {
            String name = componentName(value, type);
            if (seen.add(name)) unique.add(value);
        }
        return Collections.unmodifiableList(unique);
    }

    public List<PackageInfo> installedPackages(String callerPackage, long flags) {
        ArrayList<PackageInfo> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            if (!target.enabled() && (flags & VirtualPackageMetadata.MATCH_DISABLED_COMPONENTS) == 0) {
                continue;
            }
            result.add(target.packageInfo(flags));
        }
        return Collections.unmodifiableList(result);
    }

    public List<ApplicationInfo> installedApplications(String callerPackage, long flags) {
        ArrayList<ApplicationInfo> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            if (!target.enabled() && (flags & VirtualPackageMetadata.MATCH_DISABLED_COMPONENTS) == 0) {
                continue;
            }
            result.add(target.applicationInfo());
        }
        return Collections.unmodifiableList(result);
    }

    public List<ProviderInfo> queryContentProviders(String callerPackage, long flags) {
        return queryContentProviders(callerPackage, flags, Collections.emptySet());
    }

    public List<ProviderInfo> queryContentProviders(String callerPackage, long flags,
                                                    java.util.Set<String> callerPermissions) {
        ArrayList<ProviderInfo> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            for (VirtualPackageMetadata.Component component : target.components()) {
                if (component.type() != VirtualPackageMetadata.Type.PROVIDER) continue;
                if (!isAccessible(callerPackage, target, component, callerPermissions)) continue;
                ProviderInfo provider = target.provider(component.authority().split(";")[0], flags);
                if (provider != null) result.add(provider);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public int packageUid(String callerPackage, String targetPackage) {
        ApplicationInfo info = applicationInfo(callerPackage, targetPackage);
        return info == null ? -1 : info.uid;
    }

    public String packageForUid(String callerPackage, int uid) {
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            if (target.applicationInfo().uid == uid) return target.packageName();
        }
        return null;
    }

    public String[] packagesForUid(String callerPackage, int uid) {
        String packageName = packageForUid(callerPackage, uid);
        return packageName == null ? new String[0] : new String[]{packageName};
    }

    private VirtualPackageMetadata visibleTarget(String callerPackage, String targetPackage) {
        return targetPackage == null || !isVisibleTo(callerPackage, targetPackage)
                ? null : packageMetadata(targetPackage);
    }

    private List<VirtualPackageMetadata> visibleTargets(String callerPackage) {
        ArrayList<VirtualPackageMetadata> result = new ArrayList<>();
        for (VirtualPackageMetadata target : packages.values()) {
            if (isVisibleTo(callerPackage, target.packageName())) result.add(target);
        }
        return result;
    }

    private static boolean isAccessible(String callerPackage, VirtualPackageMetadata target,
                                        ResolveInfo value, VirtualPackageMetadata.Type type,
                                        java.util.Set<String> callerPermissions) {
        if (target.packageName().equals(callerPackage)) return true;
        String className;
        if (type == VirtualPackageMetadata.Type.SERVICE) {
            className = value.serviceInfo == null ? "" : value.serviceInfo.name;
        } else {
            className = value.activityInfo == null ? "" : value.activityInfo.name;
        }
        return isAccessible(callerPackage, target, target.component(className, type), callerPermissions);
    }

    private static boolean isAccessible(String callerPackage, VirtualPackageMetadata target,
                                        VirtualPackageMetadata.Component component,
                                        java.util.Set<String> callerPermissions) {
        if (component == null) return false;
        if (target.packageName().equals(callerPackage)) return true;
        if (!component.exported()) return false;
        String permission = component.type() == VirtualPackageMetadata.Type.PROVIDER
                ? component.readPermission() : component.permission();
        return permission == null || permission.isEmpty()
                || (callerPermissions != null && callerPermissions.contains(permission));
    }

    private static String componentName(ResolveInfo value, VirtualPackageMetadata.Type type) {
        if (type == VirtualPackageMetadata.Type.SERVICE && value.serviceInfo != null) {
            return value.serviceInfo.packageName + "/" + value.serviceInfo.name;
        }
        if (type == VirtualPackageMetadata.Type.RECEIVER && value.activityInfo != null) {
            return value.activityInfo.packageName + "/" + value.activityInfo.name;
        }
        if (value.activityInfo == null) return "";
        return value.activityInfo.packageName + "/" + value.activityInfo.name;
    }
}
