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
        for (String path : resolvedSharedLibraryFiles(state, universe)) {
            appendArchive(paths, seen, path, "shared-library");
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
     * Mirrors the native-element portion of platform {@code LoadedApk.makePaths}: a resolved
     * shared-library APK contributes {@code apk!/lib/<primaryCpuAbi>} to the defining loader.
     * CAS constructs that loader directly, so projecting {@code sharedLibraryFiles} alone is
     * insufficient.  Sources still come exclusively from the package authority's already
     * resolved shared-library projection; this is not a Host library-directory fallback.
     */
    static String appendResolvedNativeLibraryPaths(String baseNativePath,
                                                   VirtualPackageStateSnapshot state,
                                                   List<VirtualPackageProjectionSnapshot> universe,
                                                   String nativeAbi) {
        String base = baseNativePath == null ? "" : baseNativePath.trim();
        String abi = nativeAbi == null ? "" : nativeAbi.trim();
        if (state == null || abi.isEmpty()) return base;
        android.content.pm.ApplicationInfo applicationInfo = state.applicationInfo();
        if (applicationInfo == null || applicationInfo.targetSdkVersion < 26) return base;
        Set<String> seen = new HashSet<>();
        if (!base.isEmpty()) {
            for (String path : base.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!path.trim().isEmpty()) seen.add(path.trim());
            }
        }
        StringBuilder result = new StringBuilder(base);
        int added = 0;
        for (String archive : resolvedSharedLibraryFiles(state, universe)) {
            String element = nativeArchiveElement(archive, abi);
            if (element.isEmpty() || !seen.add(element)) continue;
            if (result.length() > 0) result.append(File.pathSeparator);
            result.append(element);
            added++;
        }
        if (added > 0) {
            android.util.Log.i("CS_SHARED_LIBRARY_ROUTE", "nativeElements=" + added
                    + " abi=" + abi);
        }
        return result.toString();
    }

    /**
     * Projects the same immutable provider APK set into ApplicationInfo.sharedLibraryFiles.
     * Chromium and other platform-aware loaders consult that field independently of the
     * ClassLoader dex path, so leaving it null creates a split/shared-library mismatch even when
     * the files were already appended to the loader.  The result contains only Guest projections
     * from the virtual universe or authority-approved Host provider source files.
     */
    static List<String> resolvedSharedLibraryFiles(
            VirtualPackageStateSnapshot state,
            List<VirtualPackageProjectionSnapshot> universe) {
        if (state == null) return List.of();
        List<VirtualPackageProjectionSnapshot> values = universe == null ? List.of() : universe;
        ArrayList<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> projectedProviders = new HashSet<>();
        for (VirtualPackageProjectionSnapshot projection
                : resolvedJavaLibraryProjections(state, values)) {
            String provider = projection.packageState().packageName();
            projectedProviders.add(provider);
            appendArchive(result, seen, projection.apkPath(), provider);
            android.content.pm.ApplicationInfo info = projection.parsedApplicationInfo();
            if (info != null && info.splitSourceDirs != null) {
                for (String split : info.splitSourceDirs) {
                    appendArchive(result, seen, split, provider);
                }
            }
        }
        // Host-owned system/static providers are not Guest packages and therefore must not be
        // assigned a synthetic virtual UID.  Their exact PMS source projection is the only
        // Host path allowed to cross this boundary.
        for (VirtualSharedLibrarySnapshot library : state.sharedLibraryDetails()) {
            if (library == null || !library.resolved() || !javaLibrary(library.kind())) continue;
            String provider = library.providerPackage().trim();
            if (provider.isEmpty() || provider.equals(state.packageName())
                    || isSystemProvider(provider) || projectedProviders.contains(provider)) continue;
            for (String source : library.providerSourceFiles()) {
                appendArchive(result, seen, source, provider);
            }
            if (!library.providerSourceFiles().isEmpty()) {
                android.util.Log.i("CS_SHARED_LIBRARY_ROUTE", "owner=HOST_SYSTEM provider="
                        + provider + " library=" + library.name() + " sourceCount="
                        + library.providerSourceFiles().size());
            }
        }
        return List.copyOf(result);
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
                // A resolved host-owned system/static library is represented by its immutable
                // source projection rather than a virtual Guest package.  Ordinary processes
                // consume those sources in appendResolvedLibraryPaths(); isolated processes
                // require a separate fd capability and must fail with an actionable boundary.
                if (hasHostSourceProjection(state, provider)) continue;
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

    private static boolean hasHostSourceProjection(VirtualPackageStateSnapshot state,
                                                    String provider) {
        for (VirtualSharedLibrarySnapshot library : state.sharedLibraryDetails()) {
            if (library != null && library.resolved() && provider.equals(library.providerPackage())
                    && !library.providerSourceFiles().isEmpty()) return true;
        }
        return false;
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

    private static String nativeArchiveElement(String rawPath, String abi) {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.isEmpty() || abi == null || abi.trim().isEmpty()) return "";
        try {
            File archive = new File(value).getCanonicalFile();
            if (!archive.isFile() || !archive.getName().endsWith(".apk")) return "";
            return archive.getPath() + "!/lib/" + abi.trim();
        } catch (java.io.IOException ignored) {
            return "";
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
