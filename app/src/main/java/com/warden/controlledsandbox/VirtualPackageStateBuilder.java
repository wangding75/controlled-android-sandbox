package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.PackageAppOpSnapshot;
import com.warden.controlledsandbox.contract.VirtualComponentMetadataSnapshot;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentDataSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentFilterSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualSharedLibrarySnapshot;
import com.warden.controlledsandbox.contract.VirtualInstrumentationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionDeclarationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionGroupSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageQuerySnapshot;
import com.warden.controlledsandbox.contract.VirtualProviderPathRuleSnapshot;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.packageinfo.SharedLibraryResolver;
import com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.runtime.guest.GuestResourceLoader;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds immutable Binder package state from every artifact in the trusted revision. */
final class VirtualPackageStateBuilder {
    private final Map<String, ManifestSet> manifestsByRevision = new ConcurrentHashMap<>();
    private final HostPermissionStateResolver hostPermissions;
    private final Context context;

    VirtualPackageStateBuilder(Context context) {
        this.context = context;
        hostPermissions = new HostPermissionStateResolver(context);
    }

    VirtualPackageStateSnapshot build(SandboxRecord record, int virtualUserId,
                                      SandboxPolicyState policy,
                                      SandboxCatalogState catalog) throws Exception {
        if (record == null) throw new IllegalArgumentException("Package is not installed");
        if (policy == null) throw new IllegalArgumentException("policy is required");
        ManifestSet set = manifestsByRevision.get(record.sha256);
        if (set == null) {
            set = parseForPackageState(record);
            manifestsByRevision.put(record.sha256, set);
        }
        if (!record.packageName.equals(set.packageName)) {
            throw new SecurityException("CATALOG_MANIFEST_PACKAGE_MISMATCH");
        }
        List<VirtualComponentSnapshot> components = new ArrayList<>();
        append(components, record.packageName, set.applicationProcessName,
                set.activities, "ACTIVITY", policy, set.componentMetadata);
        append(components, record.packageName, set.applicationProcessName,
                set.services, "SERVICE", policy, set.componentMetadata);
        append(components, record.packageName, set.applicationProcessName,
                set.receivers, "RECEIVER", policy, set.componentMetadata);
        append(components, record.packageName, set.applicationProcessName,
                set.providers, "PROVIDER", policy, set.componentMetadata);

        List<VirtualPermissionSnapshot> permissions = new ArrayList<>();
        Map<String, String> effectiveAppOps = new java.util.TreeMap<>(policy.appOpModes());
        for (String permission : set.permissions) {
            String decision = policy.permissionDecision(permission);
            PermissionCapabilityRegistry.Capability capability =
                    PermissionCapabilityRegistry.resolve(permission);
            HostPermissionStateResolver.HostState host = hostPermissions.resolve(permission);
            boolean granted = !SandboxPolicyState.PERMISSION_DENIED.equals(decision)
                    && host.grantedToHost
                    && (!capability.runtimeControlled
                    || SandboxPolicyState.PERMISSION_GRANTED.equals(decision));
            String requestState = catalog.latestPermissionRequestState(
                    record.packageName, virtualUserId, permission);
            permissions.add(new VirtualPermissionSnapshot(permission, decision, granted,
                    host.declaredByHost, host.grantedToHost, host.runtimeRequestable,
                    capability.appOpName, requestState));
            if (!capability.appOpName.isEmpty()) {
                String configuredMode = effectiveAppOps.get(capability.appOpName);
                effectiveAppOps.put(capability.appOpName,
                        effectiveAppOpMode(granted, configuredMode));
            }
        }
        List<PackageAppOpSnapshot> appOps = new ArrayList<>();
        for (Map.Entry<String, String> item : effectiveAppOps.entrySet()) {
            appOps.add(new PackageAppOpSnapshot(item.getKey(), item.getValue()));
        }
        List<VirtualPermissionDeclarationSnapshot> permissionDeclarations = new ArrayList<>();
        for (ManifestModel.PermissionDeclaration declaration : set.permissionDeclarations) {
            permissionDeclarations.add(new VirtualPermissionDeclarationSnapshot(
                    declaration.name(), declaration.group(), declaration.label(),
                    declaration.description(), declaration.labelRes(), declaration.descriptionRes(),
                    declaration.icon(), declaration.protectionLevel(), declaration.flags(),
                    declaration.tree()));
        }
        List<VirtualPermissionGroupSnapshot> permissionGroups = new ArrayList<>();
        for (ManifestModel.PermissionGroupDeclaration group : set.permissionGroups) {
            permissionGroups.add(new VirtualPermissionGroupSnapshot(
                    group.name(), group.label(), group.description(), group.labelRes(),
                    group.descriptionRes(), group.icon(), group.requestRes(), group.priority(),
                    group.flags()));
        }

        SharedLibraryResolver resolver = new SharedLibraryResolver(availableLibraries(catalog,
                record.packageName, set.sharedLibraryDependencies));
        SharedLibraryResolver.Resolution libraryResolution = resolver.resolve(set.sharedLibraryDependencies);
        libraryResolution.requireSuccessful();
        Map<String, HostSharedLibraryProjection> hostLibraryProjections =
                hostSharedLibraryProjections(record.packageName, set.sharedLibraryDependencies);
        List<VirtualSharedLibrarySnapshot> librarySnapshots = new ArrayList<>();
        for (ManifestModel.SharedLibraryDependency dependency : set.sharedLibraryDependencies) {
            SharedLibraryResolver.AvailableLibrary match = null;
            for (SharedLibraryResolver.AvailableLibrary candidate : libraryResolution.resolved()) {
                if (candidate.kind() == dependency.kind() && candidate.name().equals(dependency.name())) {
                    match = candidate;
                    break;
                }
            }
            HostSharedLibraryProjection hostProjection = hostLibraryProjections.get(dependency.name());
            if (hostProjection != null && match != null
                    && !hostProjection.packageName.equals(match.providerPackage())) {
                hostProjection = null;
            }
            librarySnapshots.add(new VirtualSharedLibrarySnapshot(dependency.kind().name(),
                    dependency.name(), dependency.required(), dependency.version(),
                    dependency.certificateDigest(), match != null,
                    match == null ? "" : match.providerPackage(),
                    hostProjection == null ? List.of() : hostProjection.sourceFiles,
                    hostProjection == null ? null : hostProjection.applicationInfo));
        }
        List<VirtualInstrumentationSnapshot> instrumentationSnapshots = new ArrayList<>();
        for (ManifestModel.Instrumentation instrumentation : set.instrumentations) {
            instrumentationSnapshots.add(new VirtualInstrumentationSnapshot(
                    instrumentation.className(), instrumentation.targetPackage(),
                    instrumentation.targetProcesses(), instrumentation.handleProfiling(),
                    instrumentation.functionalTest(), instrumentation.enabled()));
        }
        List<VirtualPackageQuerySnapshot> queries = new ArrayList<>();
        for (String packageName : set.queryPackages) {
            queries.add(new VirtualPackageQuerySnapshot(VirtualPackageQuerySnapshot.PACKAGE,
                    packageName, null));
        }
        for (String authority : set.queryProviderAuthorities) {
            queries.add(new VirtualPackageQuerySnapshot(VirtualPackageQuerySnapshot.PROVIDER,
                    authority, null));
        }
        for (ManifestModel.QueryIntent query : set.queryIntents) {
            List<VirtualIntentDataSnapshot> data = new ArrayList<>();
            for (ManifestModel.DataRule rule : query.dataRules()) {
                data.add(new VirtualIntentDataSnapshot(rule.scheme(), rule.host(), rule.port(), rule.path(),
                        rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
            }
            queries.add(new VirtualPackageQuerySnapshot(VirtualPackageQuerySnapshot.INTENT, "",
                    new VirtualIntentFilterSnapshot(0, new ArrayList<>(query.actions()),
                            new ArrayList<>(query.categories()), data)));
        }
        return new VirtualPackageStateSnapshot(record.packageName, virtualUserId,
                record.label, record.versionName, record.versionCode,
                record.signatureSha256, record.sha256, set.launcherActivity,
                set.applicationClass, effectivePackageEnabled(policy.packageState()),
                record.firstInstallAt, record.lastUpdateAt,
                "com.warden.virtualinstaller", record.splitNames(),
                new ArrayList<>(set.sharedLibraries), librarySnapshots, instrumentationSnapshots,
                queries,
                components, permissions, permissionDeclarations, permissionGroups, appOps,
                applicationInfoTemplate(record, set), signingCertificates(record));
    }

    static boolean effectivePackageEnabled(String state) {
        return !SandboxPolicyState.COMPONENT_DISABLED.equals(
                SandboxPolicyState.componentStateValue(state));
    }

    static String effectiveAppOpMode(boolean permissionGranted, String configuredMode) {
        String configured = configuredMode == null
                ? SandboxPolicyState.APP_OP_DEFAULT
                : SandboxPolicyState.appOpModeValue(configuredMode);
        if (!permissionGranted) {
            return SandboxPolicyState.APP_OP_ERRORED.equals(configured)
                    ? SandboxPolicyState.APP_OP_ERRORED
                    : SandboxPolicyState.APP_OP_IGNORED;
        }
        return SandboxPolicyState.APP_OP_DEFAULT.equals(configured)
                ? SandboxPolicyState.APP_OP_ALLOWED : configured;
    }

    boolean declaresPermission(SandboxRecord record, String permission) throws Exception {
        ManifestSet set = manifestsByRevision.get(record.sha256);
        if (set == null) {
            set = parseForPackageState(record);
            manifestsByRevision.put(record.sha256, set);
        }
        return set.permissions.contains(permission);
    }

    boolean declaresComponent(SandboxRecord record, String className) throws Exception {
        ManifestSet set = manifestsByRevision.get(record.sha256);
        if (set == null) {
            set = parseForPackageState(record);
            manifestsByRevision.put(record.sha256, set);
        }
        for (ManifestModel.Component component : set.allComponents()) {
            if (component.className().equals(className)) return true;
        }
        return false;
    }

    void invalidate(String revisionSha256) {
        if (revisionSha256 != null) manifestsByRevision.remove(revisionSha256);
    }

    private ManifestSet parseForPackageState(SandboxRecord record) throws Exception {
        return parseManifest(record, context);
    }

    private static ManifestSet parse(SandboxRecord record) throws Exception {
        return parseManifest(record, null);
    }

    private static ManifestSet parseManifest(SandboxRecord record, Context context) throws Exception {
        ManifestSet set = new ManifestSet();
        for (PackageArtifactRecord artifact : record.artifacts) {
            File apk = new File(artifact.path).getCanonicalFile();
            if (!apk.isFile()) throw new IllegalStateException("Trusted APK artifact is missing: " + apk);
            ManifestModel manifest;
            try (ZipFile archive = new ZipFile(apk)) {
                ZipEntry entry = archive.getEntry("AndroidManifest.xml");
                if (entry == null) throw new IllegalArgumentException("APK manifest is missing");
                try (InputStream input = archive.getInputStream(entry)) {
                    manifest = new BinaryXmlManifestParser().parse(input);
                }
            }
            if (set.packageName.isEmpty()) set.packageName = manifest.packageName();
            if (!set.packageName.equals(manifest.packageName())) {
                throw new SecurityException("REVISION_ARTIFACT_PACKAGE_MISMATCH");
            }
            if (artifact.base()) {
                set.applicationClass = manifest.applicationClass();
                set.launcherActivity = manifest.launcherActivity();
                set.applicationProcessName = manifest.applicationProcessName();
                set.applicationComponentFactory = manifest.applicationComponentFactory();
                set.applicationDebuggable = manifest.applicationDebuggable();
                set.applicationDirectBootAware = manifest.applicationDirectBootAware();
                set.applicationExtractNativeLibs = manifest.applicationExtractNativeLibs();
                set.applicationUsesCleartextTraffic = manifest.applicationUsesCleartextTraffic();
                set.applicationLargeHeap = manifest.applicationLargeHeap();
                set.applicationHardwareAccelerated = manifest.applicationHardwareAccelerated();
                set.applicationNetworkSecurityConfigResId = manifest.applicationNetworkSecurityConfigResId();
                set.minSdk = manifest.minSdk();
                set.targetSdk = manifest.targetSdk();
                if (context != null) {
                    try {
                        set.applicationMetadata = GuestResourceLoader.readApplicationMetadata(
                                context, apk.getPath());
                    } catch (Throwable metadataUnavailable) {
                        // PackageManager metadata is an optional projection. LoadedApk still owns
                        // the authoritative bootstrap path and will surface a hard resource error
                        // there; do not make unrelated package queries fail because this projection
                        // is unavailable on an older/OEM AssetManager.
                        set.applicationMetadata = null;
                    }
                }
            } else if (set.launcherActivity.isEmpty() && !manifest.launcherActivity().isEmpty()) {
                set.launcherActivity = manifest.launcherActivity();
            }
            if (context != null) {
                try {
                    mergeComponentMetadata(set.componentMetadata,
                            GuestResourceLoader.readComponentMetadata(context, apk.getPath()));
                    android.util.Log.i("CS_PMS_METADATA", "parsed package=" + set.packageName
                            + " artifact=" + apk.getName() + " components="
                            + set.componentMetadata.size());
                } catch (Throwable metadataUnavailable) {
                    // The runtime LoadedApk path remains authoritative.  A reduced/OEM
                    // AssetManager may not expose every split's XML metadata; component queries
                    // still retain their structural PackageParser fields in that case.
                    android.util.Log.w("CS_PMS_METADATA", "component metadata unavailable package="
                            + set.packageName + " artifact=" + apk.getName(), metadataUnavailable);
                }
            }
            appendComponents(set.activities, manifest.activities());
            appendComponents(set.services, manifest.services());
            appendComponents(set.receivers, manifest.receivers());
            appendComponents(set.providers, manifest.providers());
            set.permissions.addAll(manifest.permissions()); set.sharedLibraries.addAll(manifest.sharedLibraries());
            appendPermissionDeclarations(set.permissionDeclarations, manifest.permissionDeclarations());
            appendPermissionGroups(set.permissionGroups, manifest.permissionGroups());
            set.sharedLibraryDependencies.addAll(manifest.sharedLibraryDependencies());
            set.providedSharedLibraries.addAll(manifest.providedSharedLibraries());
            set.instrumentations.addAll(manifest.instrumentations());
            set.queryPackages.addAll(manifest.queryPackages());
            set.queryProviderAuthorities.addAll(manifest.queryProviderAuthorities());
            set.queryIntents.addAll(manifest.queryIntents());
        }
        return set;
    }

    private static void appendComponents(List<ManifestModel.Component> target,
                                         List<ManifestModel.Component> incoming) {
        for (ManifestModel.Component component : incoming) {
            ManifestModel.Component existing = null;
            for (ManifestModel.Component candidate : target) {
                if (candidate.className().equals(component.className())) {
                    existing = candidate;
                    break;
                }
            }
            if (existing != null) {
                existing.mergeFrom(component);
                continue;
            }
            // Keep every declared provider for class-based PackageManager queries, including
            // manifests that repeat an authority. VirtualPackageMetadata and the runtime
            // provider router retain first-owner semantics for authority lookup, matching the
            // device PackageManager while preserving both ProviderInfo records.
            target.add(component);
        }
    }

    private static void appendPermissionDeclarations(List<ManifestModel.PermissionDeclaration> target,
                                                      List<ManifestModel.PermissionDeclaration> incoming) {
        for (ManifestModel.PermissionDeclaration declaration : incoming) {
            boolean present = false;
            for (ManifestModel.PermissionDeclaration existing : target) {
                if (existing.name().equals(declaration.name())) {
                    if (!existing.equals(declaration)) {
                        throw new IllegalArgumentException(
                                "Conflicting permission declaration: " + declaration.name());
                    }
                    present = true;
                    break;
                }
            }
            if (!present) target.add(declaration);
        }
    }

    private static void appendPermissionGroups(List<ManifestModel.PermissionGroupDeclaration> target,
                                                List<ManifestModel.PermissionGroupDeclaration> incoming) {
        for (ManifestModel.PermissionGroupDeclaration group : incoming) {
            boolean present = false;
            for (ManifestModel.PermissionGroupDeclaration existing : target) {
                if (existing.name().equals(group.name())) {
                    if (!existing.equals(group)) {
                        throw new IllegalArgumentException(
                                "Conflicting permission-group declaration: " + group.name());
                    }
                    present = true;
                    break;
                }
            }
            if (!present) target.add(group);
        }
    }

    private static boolean hasProviderAuthority(List<ManifestModel.Component> target,
                                                 ManifestModel.Component incoming) {
        for (ManifestModel.Component candidate : target) {
            for (String existing : candidate.authorities().split(";")) {
                String normalizedExisting = existing.trim();
                if (normalizedExisting.isEmpty()) continue;
                for (String declared : incoming.authorities().split(";")) {
                    if (normalizedExisting.equals(declared.trim())) return true;
                }
            }
        }
        return false;
    }

    private List<SharedLibraryResolver.AvailableLibrary> availableLibraries(
            SandboxCatalogState catalog, String packageName,
            List<ManifestModel.SharedLibraryDependency> dependencies) throws Exception {
        List<SharedLibraryResolver.AvailableLibrary> available = baseAvailableLibraries();
        appendHostSharedLibraries(available, context, packageName, dependencies);
        if (catalog != null) {
            for (SandboxRecord installed : catalog.records()) {
                ManifestSet installedSet = manifestsByRevision.get(installed.sha256);
                if (installedSet == null) {
                    installedSet = parseForPackageState(installed);
                    manifestsByRevision.put(installed.sha256, installedSet);
                }
                appendProvidedLibraries(available, installedSet, installed.packageName);
            }
        }
        return available;
    }

    static void requireInstallableSharedLibraries(Context context, SandboxRecord candidate,
                                                   SandboxCatalogState current) throws Exception {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        ManifestSet candidateSet = parse(candidate);
        List<SharedLibraryResolver.AvailableLibrary> available = baseAvailableLibraries();
        appendHostSharedLibraries(available, context, candidate.packageName,
                candidateSet.sharedLibraryDependencies);
        if (current != null) {
            for (SandboxRecord installed : current.records()) {
                if (candidate.packageName.equals(installed.packageName)) continue;
                appendProvidedLibraries(available, parse(installed), installed.packageName);
            }
        }
        appendProvidedLibraries(available, candidateSet, candidate.packageName);
        new SharedLibraryResolver(available).resolve(candidateSet.sharedLibraryDependencies)
                .requireSuccessful();
    }

    /**
     * Android's PackageManager owns the active shared-library revision set.  A Guest package
     * must resolve against that set during import as well; hard-coding a small framework list
     * rejects real static libraries such as Trichrome even though PMS has already accepted the
     * package.  The returned provider identity is retained for the later Guest loader contract;
     * no package-name special case is used here.
     */
    private static void appendHostSharedLibraries(
            List<SharedLibraryResolver.AvailableLibrary> available, Context context,
            String packageName, List<ManifestModel.SharedLibraryDependency> dependencies) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        try {
            PackageManager packageManager = context.getPackageManager();
            List<SharedLibraryInfo> libraries = packageManager.getSharedLibraries(0);
            if (libraries != null) {
                for (SharedLibraryInfo library : libraries) {
                    if (library == null || library.getName() == null
                            || library.getName().trim().isEmpty()) continue;
                    ManifestModel.SharedLibraryDependency.Kind kind = sharedLibraryKind(library);
                    long version = library.getLongVersion();
                    if (version < 0) version = 0;
                    String provider = "android";
                    if (library.getDeclaringPackage() != null
                            && library.getDeclaringPackage().getPackageName() != null
                            && !library.getDeclaringPackage().getPackageName().trim().isEmpty()) {
                        provider = library.getDeclaringPackage().getPackageName();
                    }
                    String certificate = "";
                    List<String> digests = sharedLibraryCertDigests(library);
                    if (digests != null) {
                        for (String digest : digests) {
                            String normalized = digest == null ? ""
                                    : digest.replace(":", "").trim();
                            if (normalized.matches("[0-9a-fA-F]{64}")) {
                                certificate = normalized;
                                break;
                            }
                        }
                    }
                    available.add(new SharedLibraryResolver.AvailableLibrary(
                            kind, library.getName(), version, certificate, provider));
                    android.util.Log.i("CS_SHARED_LIBRARY", "host name=" + library.getName()
                            + " kind=" + kind + " version=" + version + " provider=" + provider);
                }
            }
            appendHostSharedLibraryFiles(available, packageManager, packageName, dependencies);
        } catch (RuntimeException unavailable) {
            // A device that cannot expose the host catalog remains fail-closed for required
            // declarations.  Optional libraries still retain their explicit unresolved state.
            android.util.Log.w("CS_SHARED_LIBRARY", "host shared-library catalog unavailable",
                    unavailable);
        }
    }

    /**
     * getCertDigests is not present on every API level that exposes SharedLibraryInfo. The host
     * PMS catalog remains authoritative, so an unavailable optional accessor must mean "no
     * digest projection", not a LinkageError that kills package import.
     */
    @SuppressWarnings("unchecked")
    private static List<String> sharedLibraryCertDigests(SharedLibraryInfo library) {
        if (library == null) return List.of();
        try {
            java.lang.reflect.Method getter = SharedLibraryInfo.class.getMethod("getCertDigests");
            Object result = getter.invoke(library);
            return result instanceof List<?> values ? (List<String>) values : List.of();
        } catch (NoSuchMethodException | IllegalAccessException unavailable) {
            return List.of();
        } catch (java.lang.reflect.InvocationTargetException unavailable) {
            Throwable cause = unavailable.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            return List.of();
        }
    }

    /**
     * Static libraries are not returned by getSharedLibraries(0) on every Android 14+ build.
     * PackageManager still publishes the resolved provider APKs through the importing package's
     * ApplicationInfo.sharedLibraryFiles when GET_SHARED_LIBRARY_FILES is requested.  Use the
     * dependency kind/certificate from the trusted manifest and recover the provider/version
     * from the PMS-owned APK path; this keeps the import check tied to the host's accepted graph.
     */
    private static void appendHostSharedLibraryFiles(
            List<SharedLibraryResolver.AvailableLibrary> available, PackageManager packageManager,
            String packageName, List<ManifestModel.SharedLibraryDependency> dependencies) {
        if (packageName == null || packageName.trim().isEmpty() || dependencies == null
                || dependencies.isEmpty()) return;
        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    packageName, PackageManager.GET_SHARED_LIBRARY_FILES);
            String[] files = packageInfo.applicationInfo == null
                    ? null : packageInfo.applicationInfo.sharedLibraryFiles;
            android.util.Log.i("CS_SHARED_LIBRARY", "host package=" + packageName
                    + " sharedLibraryFiles=" + (files == null ? 0 : files.length));
            if (files == null) return;
            for (String file : files) {
                HostSharedLibraryPath provider = parseHostSharedLibraryPath(file);
                if (provider == null) continue;
                for (ManifestModel.SharedLibraryDependency dependency : dependencies) {
                    if (!dependency.name().equals(provider.name)) continue;
                    long version = provider.version > 0 ? provider.version : dependency.version();
                    available.add(new SharedLibraryResolver.AvailableLibrary(
                            dependency.kind(), dependency.name(), version,
                            dependency.certificateDigest(), provider.packageName));
                    android.util.Log.i("CS_SHARED_LIBRARY", "host file name="
                            + dependency.name() + " kind=" + dependency.kind() + " version="
                            + version + " provider=" + provider.packageName + " path=" + file);
                }
            }
        } catch (Exception unavailable) {
            android.util.Log.w("CS_SHARED_LIBRARY",
                    "host shared-library file projection unavailable package=" + packageName,
                    unavailable);
        }
    }

    private static HostSharedLibraryPath parseHostSharedLibraryPath(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent == null) return null;
        String directory = parent.getName();
        int hyphen = directory.lastIndexOf('-');
        int underscore = hyphen < 0 ? -1 : directory.lastIndexOf('_', hyphen);
        if (underscore <= 0 || hyphen <= underscore + 1) return null;
        String name = directory.substring(0, underscore);
        long version;
        try {
            version = Long.parseLong(directory.substring(underscore + 1, hyphen));
        } catch (NumberFormatException invalidVersion) {
            return null;
        }
        return new HostSharedLibraryPath(name, version, name, path);
    }

    private static final class HostSharedLibraryPath {
        final String name;
        final long version;
        final String packageName;
        final String sourcePath;

        HostSharedLibraryPath(String name, long version, String packageName, String sourcePath) {
            this.name = name;
            this.version = version;
            this.packageName = packageName;
            this.sourcePath = sourcePath;
        }
    }

    /**
     * Captures the host PMS source projection for a static/SDK/JAVA provider.  The normal Guest
     * package universe contains only imported Guest packages, so a system-owned shared library
     * cannot be represented by a synthetic Guest UID without corrupting ownership.  Keeping the
     * immutable provider APK set beside the resolved dependency preserves the real split/source
     * chain while leaving Package Owner and Runtime Owner distinct.
     */
    private Map<String, HostSharedLibraryProjection> hostSharedLibraryProjections(
            String packageName, List<ManifestModel.SharedLibraryDependency> dependencies) {
        Map<String, HostSharedLibraryProjection> result = new LinkedHashMap<>();
        if (context == null || packageName == null || packageName.trim().isEmpty()
                || dependencies == null || dependencies.isEmpty()) return result;
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo importing = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_SHARED_LIBRARY_FILES);
            String[] files = importing == null || importing.applicationInfo == null
                    ? null : importing.applicationInfo.sharedLibraryFiles;
            if (files == null) return result;
            Set<String> wanted = new LinkedHashSet<>();
            for (ManifestModel.SharedLibraryDependency dependency : dependencies) {
                if (dependency != null) wanted.add(dependency.name());
            }
            for (String file : files) {
                HostSharedLibraryPath parsed = parseHostSharedLibraryPath(file);
                if (parsed == null || !wanted.contains(parsed.name)
                        || result.containsKey(parsed.name)) continue;
                ArrayList<String> sourceFiles = new ArrayList<>();
                addSourceFile(sourceFiles, parsed.sourcePath);
                ApplicationInfo provider = null;
                try {
                    provider = packageManager.getApplicationInfo(parsed.packageName,
                            PackageManager.GET_SHARED_LIBRARY_FILES);
                    if (provider != null) {
                        addSourceFile(sourceFiles, provider.sourceDir);
                        addSourceFiles(sourceFiles, provider.splitSourceDirs);
                        addSourceFiles(sourceFiles, provider.splitPublicSourceDirs);
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    // The importing package's PMS path remains authoritative even when the
                    // provider package cannot be queried through a restricted API surface.
                }
                result.put(parsed.name, new HostSharedLibraryProjection(parsed.packageName,
                        sourceFiles, provider));
            }
        } catch (Throwable unavailable) {
            android.util.Log.w("CS_SHARED_LIBRARY",
                    "host provider source projection unavailable package=" + packageName,
                    unavailable);
        }
        return result;
    }

    private static void addSourceFiles(List<String> target, String[] values) {
        if (values == null) return;
        for (String value : values) addSourceFile(target, value);
    }

    private static void addSourceFile(List<String> target, String value) {
        if (value == null || value.trim().isEmpty() || target.contains(value.trim())) return;
        target.add(value.trim());
    }

    private static final class HostSharedLibraryProjection {
        final String packageName;
        final List<String> sourceFiles;
        final ApplicationInfo applicationInfo;

        HostSharedLibraryProjection(String packageName, List<String> sourceFiles,
                                    ApplicationInfo applicationInfo) {
            this.packageName = packageName;
            this.sourceFiles = List.copyOf(sourceFiles);
            this.applicationInfo = applicationInfo == null ? null : new ApplicationInfo(applicationInfo);
        }
    }

    private static ManifestModel.SharedLibraryDependency.Kind sharedLibraryKind(
            SharedLibraryInfo library) {
        if (library.getType() == SharedLibraryInfo.TYPE_STATIC) {
            return ManifestModel.SharedLibraryDependency.Kind.STATIC;
        }
        if (library.getType() == SharedLibraryInfo.TYPE_SDK_PACKAGE) {
            return ManifestModel.SharedLibraryDependency.Kind.SDK;
        }
        String name = library.getName();
        if (name != null && name.startsWith("lib") && name.endsWith(".so")) {
            return ManifestModel.SharedLibraryDependency.Kind.NATIVE;
        }
        return ManifestModel.SharedLibraryDependency.Kind.JAVA;
    }

    private static List<SharedLibraryResolver.AvailableLibrary> baseAvailableLibraries() {
        List<SharedLibraryResolver.AvailableLibrary> available = new ArrayList<>();
        for (String name : List.of("org.apache.http.legacy", "android.test.base",
                "android.test.mock", "android.test.runner", "android.ext.shared",
                "android.ext.services")) {
            available.add(new SharedLibraryResolver.AvailableLibrary(
                    ManifestModel.SharedLibraryDependency.Kind.JAVA, name, 0L, "", "android"));
        }
        for (String name : List.of("libandroid.so", "libc.so", "libdl.so", "libEGL.so",
                "libGLESv2.so", "libGLESv3.so", "libjnigraphics.so", "liblog.so",
                "libm.so", "libOpenMAXAL.so", "libOpenSLES.so", "libvulkan.so", "libz.so")) {
            available.add(new SharedLibraryResolver.AvailableLibrary(
                    ManifestModel.SharedLibraryDependency.Kind.NATIVE, name, 0L, "", "android"));
        }
        return available;
    }

    private static void appendProvidedLibraries(
            List<SharedLibraryResolver.AvailableLibrary> available, ManifestSet set,
            String providerPackage) {
        for (String provided : set.providedSharedLibraries) {
            available.add(new SharedLibraryResolver.AvailableLibrary(
                    ManifestModel.SharedLibraryDependency.Kind.JAVA, provided, 0L, "",
                    providerPackage));
        }
    }

    private static void append(List<VirtualComponentSnapshot> output, String packageName,
                               String applicationProcessName,
                               List<ManifestModel.Component> components, String type,
                               SandboxPolicyState policy,
                               Map<String, Bundle> componentMetadata) {
        for (ManifestModel.Component component : components) {
            String enabledSetting = policy.componentState(component.className());
            boolean enabled = effectiveComponentEnabled(component.enabled(), enabledSetting);
            List<VirtualIntentFilterSnapshot> filters = new ArrayList<>();
            for (ManifestModel.IntentFilter filter : component.intentFilters()) {
                List<VirtualIntentDataSnapshot> data = new ArrayList<>();
                for (ManifestModel.DataRule rule : filter.dataRules()) {
                    data.add(new VirtualIntentDataSnapshot(rule.scheme(), rule.host(), rule.port(), rule.path(),
                            rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
                }
                filters.add(new VirtualIntentFilterSnapshot(filter.priority(),
                        new ArrayList<>(filter.actions()), new ArrayList<>(filter.categories()), data));
            }
            List<VirtualProviderPathRuleSnapshot> providerPathRules = new ArrayList<>();
            for (ManifestModel.ProviderPathRule rule : component.providerPathRules()) {
                providerPathRules.add(new VirtualProviderPathRuleSnapshot(rule.path(), rule.pathPrefix(),
                        rule.pathPattern(), rule.readPermission(), rule.writePermission(),
                        rule.uriGrantRule()));
            }
            output.add(new VirtualComponentSnapshot(type, component.className(),
                    processName(packageName, applicationProcessName, component),
                    component.exported(), enabled,
                    component.isolatedProcess(), component.authorities(), component.permission(),
                    component.readPermission(), component.writePermission(), component.grantUriPermissions(),
                    enabledSetting, component.actions(), filters, providerPathRules,
                    component.themeResId(), component.launchMode(), component.taskAffinity(),
                    component.documentLaunchMode(), component.configChanges(),
                    component.screenOrientation(), component.windowSoftInputMode(), component.flags(),
                    component.excludeFromRecents(), component.noHistory(),
                    component.finishOnTaskLaunch(), component.clearTaskOnLaunch(),
                    component.alwaysRetainTaskState(), component.allowTaskReparenting(),
                    component.resizeMode(), component.maxAspectRatio(), component.minAspectRatio(),
                    component.supportsPictureInPicture(), component.foregroundServiceType(),
                    component.stopWithTask(), component.directBootAware(), component.multiprocess(),
                    component.initOrder(), component.syncable(), component.persistableMode(),
                    component.targetActivity(), toMetadataSnapshots(componentMetadata == null ? null
                            : componentMetadata.get(component.className()))));
            if ("SERVICE".equals(type) && component.foregroundServiceType() != 0) {
                android.util.Log.i("CS_FGS_PROJECTION", "PACKAGE_STATE service="
                        + component.className() + " declaredType="
                        + component.foregroundServiceType());
            }
        }
    }

    static List<VirtualComponentMetadataSnapshot> toMetadataSnapshots(Bundle bundle) {
        if (bundle == null || bundle.isEmpty()) return List.of();
        List<VirtualComponentMetadataSnapshot> list = new ArrayList<>();
        for (String key : bundle.keySet()) {
            if (key == null) continue;
            Object value = bundle.get(key);
            if (value instanceof String) {
                list.add(new VirtualComponentMetadataSnapshot(key, (String) value));
            } else if (value instanceof Integer) {
                list.add(new VirtualComponentMetadataSnapshot(key, ((Integer) value).intValue()));
            } else if (value instanceof Boolean) {
                list.add(new VirtualComponentMetadataSnapshot(key, ((Boolean) value).booleanValue()));
            } else if (value instanceof Float) {
                list.add(new VirtualComponentMetadataSnapshot(key, ((Float) value).floatValue()));
            } else if (value != null) {
                list.add(new VirtualComponentMetadataSnapshot(key, String.valueOf(value)));
            }
        }
        return list;
    }

    private static void mergeComponentMetadata(Map<String, Bundle> target,
                                                Map<String, Bundle> incoming) {
        if (target == null || incoming == null) return;
        for (Map.Entry<String, Bundle> entry : incoming.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            Bundle current = target.get(entry.getKey());
            if (current == null) target.put(entry.getKey(), new Bundle(entry.getValue()));
            else current.putAll(entry.getValue());
        }
    }

    static boolean effectiveComponentEnabled(boolean manifestEnabled, String setting) {
        if (SandboxPolicyState.COMPONENT_ENABLED.equals(setting)) return true;
        if (SandboxPolicyState.COMPONENT_DISABLED.equals(setting)) return false;
        return manifestEnabled;
    }

    private static String processName(String packageName, String applicationProcessName,
                                      ManifestModel.Component component) {
        if (component.isolatedProcess()) {
            return packageName + ":isolated_" + component.className().replaceAll("[^A-Za-z0-9_]", "_");
        }
        String declared = component.processName();
        return normalizeProcessName(packageName,
                declared == null || declared.trim().isEmpty()
                        ? applicationProcessName : declared);
    }

    private static final class ManifestSet {
        String packageName = ""; String applicationClass = ""; String launcherActivity = "";
        String applicationProcessName = ""; String applicationComponentFactory = "";
        boolean applicationDebuggable; boolean applicationDirectBootAware;
        boolean applicationExtractNativeLibs = true; boolean applicationUsesCleartextTraffic = true;
        boolean applicationLargeHeap; boolean applicationHardwareAccelerated = true;
        int applicationNetworkSecurityConfigResId; int minSdk; int targetSdk;
        Bundle applicationMetadata;
        final Map<String, Bundle> componentMetadata = new LinkedHashMap<>();
        final List<ManifestModel.Component> activities = new ArrayList<>();
        final List<ManifestModel.Component> services = new ArrayList<>();
        final List<ManifestModel.Component> receivers = new ArrayList<>();
        final List<ManifestModel.Component> providers = new ArrayList<>();
        final Set<String> permissions = new LinkedHashSet<>();
        final List<ManifestModel.PermissionDeclaration> permissionDeclarations = new ArrayList<>();
        final List<ManifestModel.PermissionGroupDeclaration> permissionGroups = new ArrayList<>();
        final Set<String> sharedLibraries = new LinkedHashSet<>();
        final List<ManifestModel.SharedLibraryDependency> sharedLibraryDependencies = new ArrayList<>();
        final Set<String> providedSharedLibraries = new LinkedHashSet<>();
        final List<ManifestModel.Instrumentation> instrumentations = new ArrayList<>();
        final Set<String> queryPackages = new LinkedHashSet<>();
        final Set<String> queryProviderAuthorities = new LinkedHashSet<>();
        final List<ManifestModel.QueryIntent> queryIntents = new ArrayList<>();
        List<ManifestModel.Component> allComponents() {
            List<ManifestModel.Component> values = new ArrayList<>();
            values.addAll(activities); values.addAll(services); values.addAll(receivers); values.addAll(providers);
            return values;
        }
    }

    private static ApplicationInfo applicationInfoTemplate(SandboxRecord record, ManifestSet set) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = record.packageName;
        info.name = set.applicationClass.isEmpty() ? null : set.applicationClass;
        info.processName = applicationProcessName(record.packageName, set.applicationProcessName);
        info.sourceDir = baseArtifactPath(record);
        info.publicSourceDir = info.sourceDir;
        List<String> splitNames = new ArrayList<>();
        List<String> splitPaths = new ArrayList<>();
        for (PackageArtifactRecord artifact : record.artifacts) {
            if (artifact.base()) continue;
            splitNames.add(artifact.splitName);
            splitPaths.add(artifact.path);
        }
        setOptionalApplicationField(info, "splitNames", splitNames.toArray(new String[0]));
        info.splitSourceDirs = splitPaths.isEmpty() ? null : splitPaths.toArray(new String[0]);
        info.splitPublicSourceDirs = info.splitSourceDirs == null
                ? null : info.splitSourceDirs.clone();
        info.nativeLibraryDir = nativeLibraryPath(record);
        setOptionalApplicationField(info, "primaryCpuAbi",
                record.nativeAbi.isEmpty() || "legacy-unknown".equals(record.nativeAbi)
                        ? null : record.nativeAbi);
        setOptionalApplicationField(info, "secondaryCpuAbi", null);
        // Shared-library paths are resolved by the Guest loader from the immutable package
        // universe. Never inherit a host parser's path-bearing array into the virtual PMS view.
        setOptionalApplicationField(info, "sharedLibraryFiles", null);
        info.minSdkVersion = set.minSdk;
        info.targetSdkVersion = set.targetSdk;
        info.flags = ApplicationInfo.FLAG_HAS_CODE;
        if (set.applicationDebuggable) info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        if (set.applicationLargeHeap) info.flags |= ApplicationInfo.FLAG_LARGE_HEAP;
        if (set.applicationHardwareAccelerated) info.flags |= ApplicationInfo.FLAG_HARDWARE_ACCELERATED;
        if (set.applicationExtractNativeLibs) info.flags |= ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS;
        if (set.applicationUsesCleartextTraffic) info.flags |= ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC;
        setOptionalApplicationField(info, "appComponentFactory", set.applicationComponentFactory);
        info.metaData = set.applicationMetadata == null ? null : new Bundle(set.applicationMetadata);
        setOptionalApplicationField(info, "directBootAware", set.applicationDirectBootAware);
        info.enabled = true;
        setOptionalApplicationField(info, "networkSecurityConfigRes",
                set.applicationNetworkSecurityConfigResId);
        return info;
    }

    private List<byte[]> signingCertificates(SandboxRecord record) {
        File base = new File(baseArtifactPath(record));
        try {
            int flags = PackageManager.GET_SIGNATURES;
            if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                    base.getAbsolutePath(), flags);
            List<byte[]> values = new ArrayList<>();
            Signature[] current = null;
            if (info != null && Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                current = info.signingInfo.getApkContentsSigners();
            }
            if ((current == null || current.length == 0) && info != null) current = info.signatures;
            if (current != null) {
                for (Signature signature : current) {
                    if (signature != null && signature.toByteArray().length > 0) {
                        values.add(signature.toByteArray());
                    }
                }
            }
            if (!values.isEmpty()) return values;
        } catch (RuntimeException ignored) {
            // The package parser is an optional projection. The immutable signer digest below
            // still gives the PMS a stable virtual identity when an old parser cannot expose
            // certificate bytes for an already trusted revision.
        }
        byte[] fallback = firstDigestBytes(record.signatureSha256);
        return fallback.length == 0 ? List.of() : List.of(fallback);
    }

    private static byte[] firstDigestBytes(String value) {
        String first = value == null ? "" : value.split(",", 2)[0].trim();
        if (!first.matches("[0-9a-fA-F]{64}")) return new byte[0];
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(first.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static String nativeLibraryPath(SandboxRecord record) {
        if (record.nativeLibraryDir == null || record.nativeLibraryDir.trim().isEmpty()) return "";
        File root = new File(record.nativeLibraryDir);
        if (!record.nativeAbi.isEmpty() && !"legacy-unknown".equals(record.nativeAbi)) {
            File abi = new File(root, record.nativeAbi);
            if (abi.isDirectory()) return abi.getAbsolutePath();
        }
        return root.getAbsolutePath();
    }

    private static String applicationProcessName(String packageName, String declared) {
        return normalizeProcessName(packageName, declared);
    }

    private static String normalizeProcessName(String packageName, String declared) {
        if (declared == null || declared.trim().isEmpty()) return packageName;
        String value = declared.trim();
        return value.startsWith(":") ? packageName + value : value;
    }

    private static void setOptionalApplicationField(ApplicationInfo info, String name, Object value) {
        try {
            java.lang.reflect.Field field = ApplicationInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(info, value);
        } catch (NoSuchFieldException ignored) {
            // API 32 compile stubs omit some API 33+ fields; the runtime parser projection
            // supplies them when the platform exposes them.
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional metadata must not make an otherwise valid virtual package unloadable.
        }
    }

    private static String baseArtifactPath(SandboxRecord record) {
        for (PackageArtifactRecord artifact : record.artifacts) {
            if (artifact.base()) return artifact.path;
        }
        return record.apkPath;
    }
}
