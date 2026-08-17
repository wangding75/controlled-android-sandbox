package com.warden.controlledsandbox.framework.packagemanager;

import android.content.pm.PackageManager;

import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;

import java.util.Collections;
import java.util.List;

/**
 * Maps a hidden-package PackageManager call onto the Android contract for that API.
 *
 * <p>IPackageManager is Binder-shaped and does not declare {@code NameNotFoundException}.
 * Named info lookups therefore return {@code null} so ApplicationPackageManager can throw
 * the checked not-found exception. Query methods return an empty collection. Resolve
 * methods return {@code null}. Mutation of a hidden package remains a
 * {@link SecurityException}. This is package-visibility filtering, not a per-app special
 * case.</p>
 */
public final class HiddenPackageResultMapper {
    public interface CollectionAdapter {
        Object adapt(List<?> values, Class<?> returnType);
    }

    private HiddenPackageResultMapper() { }

    public static Object map(String methodName, Class<?> returnType, CollectionAdapter collections) {
        if (isNamedInfoLookup(methodName)
                || isResolveMethod(methodName)
                || isInstallerQuery(methodName)) {
            return null;
        }
        if (isUidLookup(methodName)) {
            return -1;
        }
        if (isGidLookup(methodName)) {
            return null;
        }
        if (isEnabledStateLookup(methodName)) {
            // COMPONENT_ENABLED_STATE_DEFAULT is the only non-error value that does not
            // disclose a hidden package's real state.  Returning -1 makes callers treat the
            // package as malformed and diverges from PackageManager's enabled-setting API.
            return 0;
        }
        if (InvocationMethodMatcher.named(methodName, "isPackageAvailable")) {
            return false;
        }
        if (InvocationMethodMatcher.named(methodName, "checkPermission")) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (isQueryOrInventoryMethod(methodName)) {
            return emptyCollection(returnType, collections);
        }
        if (isMutationMethod(methodName)) {
            throw new SecurityException("HOST_PACKAGE_MUTATION_BLOCKED");
        }
        return defaultHidden(returnType);
    }

    public static boolean isNamedInfoLookup(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "getApplicationInfo",
                "getApplicationInfoAsUser",
                "getPackageInfo",
                "getPackageInfoAsUser",
                "getPackageInfoVersioned",
                "getActivityInfo",
                "getReceiverInfo",
                "getServiceInfo",
                "getProviderInfo",
                "getInstrumentationInfo");
    }

    static boolean isUidLookup(String methodName) {
        return InvocationMethodMatcher.named(methodName, "getPackageUid", "getPackageUidAsUser");
    }

    static boolean isGidLookup(String methodName) {
        return InvocationMethodMatcher.named(methodName, "getPackageGids", "getPackageGidsEtc");
    }

    static boolean isEnabledStateLookup(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "getApplicationEnabledSetting", "getApplicationEnabledSettingAsUser",
                "getComponentEnabledSetting");
    }

    static boolean isResolveMethod(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "resolveIntent",
                "resolveActivity",
                "resolveService",
                "resolveContentProvider");
    }

    static boolean isInstallerQuery(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "getInstallerPackageName",
                "getInstallSourceInfo");
    }

    static boolean isQueryOrInventoryMethod(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "queryIntentActivities",
                "queryIntentActivitiesAsUser",
                "queryIntentActivityOptions",
                "queryIntentReceivers",
                "queryIntentReceiversAsUser",
                "queryBroadcastReceivers",
                "queryBroadcastReceiversAsUser",
                "queryIntentServices",
                "queryIntentServicesAsUser",
                "queryIntentContentProviders",
                "queryIntentContentProvidersAsUser",
                "queryContentProviders",
                "queryInstrumentation",
                "getSharedLibraries",
                "getDeclaredSharedLibraries",
                "getInstalledPackages",
                "getInstalledPackagesAsUser",
                "getInstalledApplications",
                "getInstalledApplicationsAsUser",
                "getPackagesHoldingPermissions",
                "getPackagesHoldingPermissionsAsUser",
                "getSystemAvailableFeatures");
    }

    static boolean isMutationMethod(String methodName) {
        return InvocationMethodMatcher.named(methodName,
                "setApplicationEnabledSetting",
                "setPackageStoppedState",
                "setComponentEnabledSetting")
                || InvocationMethodMatcher.startsWith(methodName,
                "set", "delete", "clear", "grant", "revoke");
    }

    private static Object emptyCollection(Class<?> returnType, CollectionAdapter collections) {
        if (collections != null && returnType != null) {
            return collections.adapt(Collections.emptyList(), returnType);
        }
        return defaultHidden(returnType);
    }

    private static Object defaultHidden(Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) return false;
        if (returnType == int.class || returnType == Integer.class) return -1;
        if (returnType == long.class || returnType == Long.class) return -1L;
        if (returnType != null && returnType.isArray()) {
            return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
        }
        if (returnType != null && List.class.isAssignableFrom(returnType)) {
            return Collections.emptyList();
        }
        return null;
    }
}
