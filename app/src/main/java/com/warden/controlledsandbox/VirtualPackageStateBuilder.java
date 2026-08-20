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

        SharedLibraryResolver resolver = new SharedLibraryResolver(availableLibraries(catalog));
        SharedLibraryResolver.Resolution libraryResolution = resolver.resolve(set.sharedLibraryDependencies);
        libraryResolution.requireSuccessful();
        List<VirtualSharedLibrarySnapshot> librarySnapshots = new ArrayList<>();
        for (ManifestModel.SharedLibraryDependency dependency : set.sharedLibraryDependencies) {
            SharedLibraryResolver.AvailableLibrary match = null;
            for (SharedLibraryResolver.AvailableLibrary candidate : libraryResolution.resolved()) {
                if (candidate.kind() == dependency.kind() && candidate.name().equals(dependency.name())) {
                    match = candidate;
                    break;
                }
            }
            librarySnapshots.add(new VirtualSharedLibrarySnapshot(dependency.kind().name(),
                    dependency.name(), dependency.required(), dependency.version(),
                    dependency.certificateDigest(), match != null,
                    match == null ? "" : match.providerPackage()));
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
            SandboxCatalogState catalog) throws Exception {
        List<SharedLibraryResolver.AvailableLibrary> available = baseAvailableLibraries();
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

    static void requireInstallableSharedLibraries(SandboxRecord candidate,
                                                   SandboxCatalogState current) throws Exception {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        ManifestSet candidateSet = parse(candidate);
        List<SharedLibraryResolver.AvailableLibrary> available = baseAvailableLibraries();
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
        info.appComponentFactory = set.applicationComponentFactory;
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
