package com.warden.controlledsandbox.framework.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
    // Keep the Binder/API contract available to the API-32 source harness, whose compact
    // PackageManager stubs intentionally omit these public constants. Values are stable in the
    // Android framework since the original signature result API.
    public static final int SIGNATURE_FIRST_NOT_SIGNED = -1;
    public static final int SIGNATURE_MATCH = 0;
    public static final int SIGNATURE_NEITHER_SIGNED = 1;
    public static final int SIGNATURE_NO_MATCH = -3;
    public static final int SIGNATURE_SECOND_NOT_SIGNED = -2;
    public static final int SIGNATURE_UNKNOWN_PACKAGE = -4;

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
        // Android's package visibility is not only an explicit <queries> graph.  Packages
        // sharing an UID/signature are visible to one another, and legacy applications below
        // the Android 11 target level retain the broad legacy query behavior.  Keep these rules
        // in the virtual universe so PackageManager, resolver and provider lookups agree.
        ApplicationInfo callerInfo = caller.applicationInfo();
        ApplicationInfo targetInfo = target.applicationInfo();
        if (callerInfo.uid > 0 && callerInfo.uid == targetInfo.uid) return true;
        if (sameSignature(caller, target)) return true;
        // A resolved <uses-library> is an explicit package dependency, not an ordinary
        // package-query request. Android's PackageManager makes the provider part of the
        // caller's loaded package universe even when the caller has no matching <queries>
        // entry. Keep this edge in the same graph used by PackageManager and the Runtime
        // Broker; otherwise an install-time-resolved library can fail when LoadedApk requests
        // the provider APK capability.
        for (VirtualPackageMetadata.SharedLibrary library : caller.sharedLibraryDetails()) {
            if (library.resolved() && target.packageName().equals(library.providerPackage())) {
                return true;
            }
        }
        if (callerInfo.targetSdkVersion > 0 && callerInfo.targetSdkVersion < 30) return true;
        if ((targetInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
        if (caller.queryPackages().contains(target.packageName())) return true;
        for (VirtualPackageMetadata.Component component : target.components()) {
            if (component.type() == VirtualPackageMetadata.Type.PROVIDER
                    && component.exported()) {
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

    /**
     * Returns a component enabled-state value from the same visibility graph as component info.
     *
     * <p>A visible peer query must not escape to the host PMS merely because the component is
     * owned by another package. Keeping this lookup in the virtual universe also prevents a host
     * stub with the same class name from being projected as Guest state.</p>
     */
    public int componentEnabledSetting(String callerPackage, ComponentName name) {
        if (name == null || !isVisibleTo(callerPackage, name.getPackageName())) return 0;
        VirtualPackageMetadata target = packageMetadata(name.getPackageName());
        return target == null ? 0 : target.componentEnabledSetting(name);
    }

    public boolean activitySupportsIntent(String callerPackage, ComponentName name,
                                          Intent intent, long flags) {
        if (name == null || !isVisibleTo(callerPackage, name.getPackageName())) return false;
        VirtualPackageMetadata target = packageMetadata(name.getPackageName());
        return target != null && target.activitySupportsIntent(name, intent, flags);
    }

    /** Resolves a custom permission only from packages visible to the Guest caller. */
    public Object permissionInfo(String callerPackage, String name, long flags) {
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            Object info = target.permissionInfo(name, flags);
            if (info != null) return info;
        }
        return null;
    }

    /** Returns all visible declarations in the requested group, preserving PMS package order. */
    public List<Object> queryPermissionsByGroup(String callerPackage, String group, long flags) {
        ArrayList<Object> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            result.addAll(target.queryPermissionsByGroup(group, flags));
        }
        return Collections.unmodifiableList(result);
    }

    public Object permissionGroupInfo(String callerPackage, String name, long flags) {
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            Object info = target.permissionGroupInfo(name, flags);
            if (info != null) return info;
        }
        return null;
    }

    public List<Object> permissionGroupInfos(String callerPackage, long flags) {
        ArrayList<Object> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            result.addAll(target.permissionGroupInfos(flags));
        }
        return Collections.unmodifiableList(result);
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
        return type == VirtualPackageMetadata.Type.ACTIVITY
                ? chooseBestActivity(values) : (values.isEmpty() ? null : values.get(0));
    }

    public ResolveInfo resolve(String callerPackage, Intent intent,
                               VirtualPackageMetadata.Type type, long flags,
                               java.util.Set<String> callerPermissions) {
        List<ResolveInfo> values = query(callerPackage, intent, type, flags, callerPermissions);
        return type == VirtualPackageMetadata.Type.ACTIVITY
                ? chooseBestActivity(values) : (values.isEmpty() ? null : values.get(0));
    }

    /** Same tie semantics as Android's chooseBestActivity used by VA/NBB. */
    private static ResolveInfo chooseBestActivity(List<ResolveInfo> query) {
        if (query == null || query.isEmpty()) return null;
        if (query.size() == 1) return query.get(0);
        ResolveInfo first = query.get(0);
        ResolveInfo second = query.get(1);
        if (first.priority != second.priority
                || preferredOrder(first) != preferredOrder(second)
                || first.isDefault != second.isDefault) {
            return first;
        }
        return null;
    }

    /** preferredOrder is optional in the compact source harness and present on Android. */
    private static int preferredOrder(ResolveInfo value) {
        if (value == null) return 0;
        try {
            java.lang.reflect.Field field = value.getClass().getField("preferredOrder");
            return field.getInt(value);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return 0;
        }
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

    public List<PackageInfo> packagesHoldingPermissions(String callerPackage,
                                                        String[] requested, long flags) {
        if (requested == null || requested.length == 0) return List.of();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String permission : requested) if (permission != null && !permission.isEmpty()) {
            wanted.add(permission);
        }
        if (wanted.isEmpty()) return List.of();
        ArrayList<PackageInfo> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            for (String permission : target.requestedPermissions()) {
                if (wanted.contains(permission)) {
                    result.add(target.packageInfo(flags));
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<ProviderInfo> queryContentProviders(String callerPackage, long flags) {
        return queryContentProviders(callerPackage, null, -1, flags, Collections.emptySet());
    }

    public List<ProviderInfo> queryContentProviders(String callerPackage, long flags,
                                                    java.util.Set<String> callerPermissions) {
        return queryContentProviders(callerPackage, null, -1, flags, callerPermissions);
    }

    /**
     * Implements the full hidden-PackageManager provider inventory shape:
     * {@code queryContentProviders(processName, uid, flags, userId)}.  The process and UID
     * filters are part of the PMS contract, not optional host-side hints.  Keeping them here
     * also prevents a Guest from observing every visible package's provider transport when the
     * framework is asking only for providers belonging to the current process/UID.
     */
    public List<ProviderInfo> queryContentProviders(String callerPackage, String processName,
                                                    int uid, long flags,
                                                    java.util.Set<String> callerPermissions) {
        ArrayList<ProviderEntry> entries = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            for (VirtualPackageMetadata.Component component : target.components()) {
                if (component.type() != VirtualPackageMetadata.Type.PROVIDER) continue;
                if (!isAccessible(callerPackage, target, component, callerPermissions)) continue;
                String declaredProcess = component.processName().isEmpty()
                        ? target.packageName() : component.processName();
                if (processName != null && !processName.isEmpty()
                        && !processName.equals(declaredProcess)) continue;
                if (uid >= 0 && target.applicationInfo().uid != uid) continue;
                ProviderInfo provider = target.providerForClass(component.className(), flags);
                if (provider != null) entries.add(new ProviderEntry(provider, component.initOrder()));
            }
        }
        // VA keeps the same ordering used by Android's provider installation path: higher
        // initOrder providers are installed first.  Ascending order is observably wrong for
        // apps whose later providers depend on a lower-priority bootstrap provider.
        entries.sort(Comparator.comparingInt((ProviderEntry value) -> value.initOrder).reversed()
                .thenComparing(value -> value.provider.packageName)
                .thenComparing(value -> value.provider.name));
        ArrayList<ProviderInfo> result = new ArrayList<>(entries.size());
        for (ProviderEntry entry : entries) result.add(entry.provider);
        return Collections.unmodifiableList(result);
    }

    public int packageUid(String callerPackage, String targetPackage) {
        ApplicationInfo info = applicationInfo(callerPackage, targetPackage);
        return info == null ? -1 : info.uid;
    }

    /**
     * Implements the virtual-PMS side of PackageManager.checkPermission().  The queried package
     * owns the permission decision; the caller's permission policy must not be reused here.
     */
    public int checkPermission(String callerPackage, String targetPackage, String permission) {
        VirtualPackageMetadata target = visibleTarget(callerPackage, targetPackage);
        if (target == null || permission == null || permission.trim().isEmpty()) {
            return PackageManager.PERMISSION_DENIED;
        }
        return target.permissionGranted(permission)
                ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    public String packageForUid(String callerPackage, int uid) {
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            if (target.applicationInfo().uid == uid) return target.packageName();
        }
        return null;
    }

    public String[] packagesForUid(String callerPackage, int uid) {
        ArrayList<String> result = new ArrayList<>();
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            if (target.applicationInfo().uid == uid) result.add(target.packageName());
        }
        return result.toArray(new String[0]);
    }

    /**
     * Applies the PackageManager signature result contract inside the same visibility graph as
     * package and component queries.  Returning a host result here would let a Guest infer the
     * signing identity of an invisible package, while returning only a boolean would lose the
     * Android distinction between an unknown package, an unsigned package and a mismatch.
     */
    public int checkSignatures(String callerPackage, String firstPackage, String secondPackage) {
        if (!isVisibleTo(callerPackage, firstPackage)
                || !isVisibleTo(callerPackage, secondPackage)) {
            return SIGNATURE_UNKNOWN_PACKAGE;
        }
        return signatureResult(packageMetadata(firstPackage), packageMetadata(secondPackage));
    }

    /** Resolves virtual UIDs before applying the same signature result contract. */
    public int checkUidSignatures(String callerPackage, int firstUid, int secondUid) {
        VirtualPackageMetadata first = visiblePackageForUid(callerPackage, firstUid);
        VirtualPackageMetadata second = visiblePackageForUid(callerPackage, secondUid);
        if (first == null || second == null) return SIGNATURE_UNKNOWN_PACKAGE;
        return signatureResult(first, second);
    }

    private VirtualPackageMetadata visiblePackageForUid(String callerPackage, int uid) {
        for (VirtualPackageMetadata target : visibleTargets(callerPackage)) {
            if (target.applicationInfo().uid == uid) return target;
        }
        return null;
    }

    private static int signatureResult(VirtualPackageMetadata first,
                                       VirtualPackageMetadata second) {
        if (first == null || second == null) return SIGNATURE_UNKNOWN_PACKAGE;
        boolean firstSigned = first.signatureSha256() != null
                && !first.signatureSha256().trim().isEmpty();
        boolean secondSigned = second.signatureSha256() != null
                && !second.signatureSha256().trim().isEmpty();
        if (!firstSigned && !secondSigned) return SIGNATURE_NEITHER_SIGNED;
        if (!firstSigned) return SIGNATURE_FIRST_NOT_SIGNED;
        if (!secondSigned) return SIGNATURE_SECOND_NOT_SIGNED;
        return sameSignature(first, second)
                ? SIGNATURE_MATCH : SIGNATURE_NO_MATCH;
    }

    private static boolean sameSignature(VirtualPackageMetadata left,
                                         VirtualPackageMetadata right) {
        String first = left.signatureSha256();
        String second = right.signatureSha256();
        return first != null && !first.isEmpty() && first.equalsIgnoreCase(second);
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
        } else if (type == VirtualPackageMetadata.Type.PROVIDER) {
            className = value.providerInfo == null ? "" : value.providerInfo.name;
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
        if (type == VirtualPackageMetadata.Type.PROVIDER && value.providerInfo != null) {
            return value.providerInfo.packageName + "/" + value.providerInfo.name;
        }
        if (type == VirtualPackageMetadata.Type.RECEIVER && value.activityInfo != null) {
            return value.activityInfo.packageName + "/" + value.activityInfo.name;
        }
        if (value.activityInfo == null) return "";
        return value.activityInfo.packageName + "/" + value.activityInfo.name;
    }

    private static final class ProviderEntry {
        final ProviderInfo provider;
        final int initOrder;

        ProviderEntry(ProviderInfo provider, int initOrder) {
            this.provider = provider;
            this.initOrder = initOrder;
        }
    }
}
