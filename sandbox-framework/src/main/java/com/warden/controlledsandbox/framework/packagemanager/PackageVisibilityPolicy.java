package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Classifies a queried package for Guest PackageManager identity methods.
 *
 * <p>This is an Android role table, not a production-app special case. Play Services and
 * similar shared system dependencies are treated as {@link PackageVisibilityClass#SYSTEM_DEPENDENCY_PROJECTED}
 * so the handler can report them as absent instead of leaking Host package state. A later
 * sanitized projector can fill that class without changing the taxonomy.</p>
 */
public final class PackageVisibilityPolicy {
    private static final Set<String> PLATFORM_PACKAGES = Set.of("android");

    private static final Set<String> SYSTEM_PROJECTED_PACKAGES = Set.of(
            "android",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.externalstorage",
            "com.android.documentsui",
            "com.android.webview",
            "com.google.android.webview");

    private static final Set<String> SYSTEM_DEPENDENCY_PACKAGES = Set.of(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.android.vending");

    private PackageVisibilityPolicy() { }

    public static PackageVisibilityClass classify(GuestIdentity identity, String packageName) {
        return classify(identity, packageName, webViewProviderPackage(identity));
    }

    public static PackageVisibilityClass classify(GuestIdentity identity, String packageName,
                                                  String webViewProviderPackage) {
        if (packageName == null || packageName.trim().isEmpty()) return null;
        String target = packageName.trim();
        if (identity != null && target.equals(identity.packageName())) {
            return PackageVisibilityClass.GUEST_OWNED;
        }
        if (webViewProviderPackage != null && !webViewProviderPackage.isEmpty()
                && target.equals(webViewProviderPackage)) {
            return PackageVisibilityClass.SYSTEM_PROJECTED;
        }
        if (isExplicitlyDenied(identity, target)) return PackageVisibilityClass.EXPLICITLY_DENIED;
        if (identity != null && identity.packageMetadata().queryPackages().contains(target)) {
            return PackageVisibilityClass.QUERY_DECLARED;
        }
        if (PLATFORM_PACKAGES.contains(target) || SYSTEM_PROJECTED_PACKAGES.contains(target)) {
            return PackageVisibilityClass.SYSTEM_PROJECTED;
        }
        if (SYSTEM_DEPENDENCY_PACKAGES.contains(target)) {
            return PackageVisibilityClass.SYSTEM_DEPENDENCY_PROJECTED;
        }
        return PackageVisibilityClass.HOST_USER_APP_HIDDEN;
    }

    public static boolean reportsAbsentWithoutProjector(PackageVisibilityClass visibility) {
        return visibility == PackageVisibilityClass.SYSTEM_DEPENDENCY_PROJECTED
                || visibility == PackageVisibilityClass.SYSTEM_PROJECTED;
    }

    public static boolean deniesIdentity(PackageVisibilityClass visibility) {
        return visibility == PackageVisibilityClass.HOST_USER_APP_HIDDEN
                || visibility == PackageVisibilityClass.EXPLICITLY_DENIED;
    }

    /** Returns whether an APK explicitly declared visibility for a package target. */
    public static boolean allowsDeclaredPackageQuery(GuestIdentity identity, String packageName) {
        return identity != null && packageName != null
                && identity.packageMetadata().queryPackages().contains(packageName.trim());
    }

    static Set<String> systemProjectedPackages() {
        return Collections.unmodifiableSet(SYSTEM_PROJECTED_PACKAGES);
    }

    static Set<String> systemDependencyPackages() {
        return Collections.unmodifiableSet(SYSTEM_DEPENDENCY_PACKAGES);
    }

    private static boolean isExplicitlyDenied(GuestIdentity identity, String target) {
        if (identity == null) return false;
        Set<String> hidden = new LinkedHashSet<>();
        try {
            com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot policy =
                    identity.virtualServices().compatibilityProfile().detection();
            if (!com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot.MODE_HOST
                    .equals(policy.mode())) {
                hidden.addAll(policy.hiddenPackageNames());
                if (policy.hideHostPackage() && identity.hostPackageName() != null) {
                    hidden.add(identity.hostPackageName());
                }
            }
        } catch (IllegalStateException ignored) {
            if (identity.hostPackageName() != null && !identity.hostPackageName().isEmpty()) {
                hidden.add(identity.hostPackageName());
            }
        }
        return hidden.contains(target);
    }

    private static String webViewProviderPackage(GuestIdentity identity) {
        if (identity == null) return "";
        try {
            return identity.virtualServices().compatibilityProfile().webView().providerPackage();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
