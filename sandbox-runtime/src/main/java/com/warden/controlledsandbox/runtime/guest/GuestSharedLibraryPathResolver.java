package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualSharedLibrarySnapshot;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves virtual Java/SDK/static shared-library providers into the Guest dex path.
 *
 * <p>Virtual PMS resolution and class-loader resolution must agree.  Previously a manifest
 * {@code <uses-library>} could be accepted by the package authority and exposed through
 * {@code SharedLibraryInfo}, while the Guest {@code PathClassLoader} still loaded only its own
 * APK/splits.  That made the package look installed but failed as soon as a class was loaded
 * from a virtual library provider.  This resolver consumes only authority-validated package
 * projections and never falls back to a Host package path.</p>
 */
final class GuestSharedLibraryPathResolver {
    private GuestSharedLibraryPathResolver() { }

    static String appendResolvedLibraryPaths(String baseDexPath,
                                             VirtualPackageStateSnapshot state,
                                             List<VirtualPackageProjectionSnapshot> universe) {
        String base = baseDexPath == null ? "" : baseDexPath.trim();
        if (state == null) return base;
        if (universe == null) universe = List.of();
        Set<String> seen = new HashSet<>();
        for (String path : base.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!path.trim().isEmpty()) seen.add(canonicalOrValue(path));
        }
        ArrayList<String> paths = new ArrayList<>();
        for (VirtualPackageProjectionSnapshot projection
                : resolvedJavaLibraryProjections(state, universe)) {
            String provider = projection.packageState().packageName();
            appendArchive(paths, seen, projection.apkPath(), provider);
            android.content.pm.ApplicationInfo info = projection.parsedApplicationInfo();
            if (info != null && info.splitSourceDirs != null) {
                for (String split : info.splitSourceDirs) appendArchive(paths, seen, split, provider);
            }
        }
        if (paths.isEmpty()) return base;
        StringBuilder result = new StringBuilder(base);
        for (String path : paths) {
            if (result.length() > 0) result.append(File.pathSeparator);
            result.append(path);
        }
        return result.toString();
    }

    /**
     * Returns the authority-validated Guest APK projections that satisfy Java/SDK/static shared
     * libraries for {@code state}.  The result deliberately contains projections, not paths:
     * ordinary processes can append canonical APK paths, while isolated processes must obtain
     * the same artifacts as Binder file capabilities through the Runtime Broker.
     */
    static List<VirtualPackageProjectionSnapshot> resolvedJavaLibraryProjections(
            VirtualPackageStateSnapshot state,
            List<VirtualPackageProjectionSnapshot> universe) {
        if (state == null) return List.of();
        List<VirtualPackageProjectionSnapshot> values = universe == null ? List.of() : universe;
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        for (VirtualSharedLibrarySnapshot library : state.sharedLibraryDetails()) {
            if (library == null || !library.resolved() || !javaLibrary(library.kind())) continue;
            String provider = library.providerPackage().trim();
            if (provider.isEmpty() || provider.equals(state.packageName())
                    || isSystemProvider(provider)) continue;
            providers.add(provider);
        }
        ArrayList<VirtualPackageProjectionSnapshot> result = new ArrayList<>();
        for (String provider : providers) {
            VirtualPackageProjectionSnapshot projection = find(values, provider);
            if (projection == null) {
                // System libraries have no virtual APK projection. A resolved Guest provider,
                // however, must be present in the same virtual package universe; otherwise the
                // Package Authority and Guest loader would disagree about installability.
                throw new IllegalStateException(
                        "SHARED_LIBRARY_PROVIDER_PROJECTION_MISSING:" + provider);
            }
            result.add(projection);
        }
        return List.copyOf(result);
    }

    private static boolean javaLibrary(String kind) {
        return VirtualSharedLibrarySnapshot.KIND_JAVA.equals(kind)
                || VirtualSharedLibrarySnapshot.KIND_SDK.equals(kind)
                || VirtualSharedLibrarySnapshot.KIND_STATIC.equals(kind);
    }

    private static VirtualPackageProjectionSnapshot find(
            List<VirtualPackageProjectionSnapshot> universe, String packageName) {
        for (VirtualPackageProjectionSnapshot value : universe) {
            if (value != null && packageName.equals(value.packageState().packageName())) return value;
        }
        return null;
    }

    private static void appendArchive(List<String> paths, Set<String> seen, String rawPath,
                                      String provider) {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.isEmpty()) throw new IllegalStateException(
                "SHARED_LIBRARY_PROVIDER_APK_MISSING:" + provider);
        File archive = new File(value);
        try {
            File canonical = archive.getCanonicalFile();
            if (!canonical.isFile()) throw new IllegalStateException(
                    "SHARED_LIBRARY_PROVIDER_APK_UNAVAILABLE:" + provider);
            String key = canonical.getPath();
            if (seen.add(key)) paths.add(key);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("SHARED_LIBRARY_PROVIDER_APK_UNAVAILABLE:" + provider,
                    error);
        }
    }

    private static String canonicalOrValue(String rawPath) {
        try { return new File(rawPath).getCanonicalPath(); }
        catch (java.io.IOException ignored) { return rawPath; }
    }

    private static boolean isSystemProvider(String packageName) {
        return packageName.equals("android") || packageName.startsWith("android.");
    }
}
