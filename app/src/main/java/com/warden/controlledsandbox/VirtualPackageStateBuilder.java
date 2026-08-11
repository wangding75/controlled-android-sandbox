package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.PackageAppOpSnapshot;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentDataSnapshot;
import com.warden.controlledsandbox.contract.VirtualIntentFilterSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualSharedLibrarySnapshot;
import com.warden.controlledsandbox.contract.VirtualInstrumentationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import com.warden.controlledsandbox.contract.VirtualProviderPathRuleSnapshot;
import android.content.Context;
import com.warden.controlledsandbox.domain.packageinfo.SharedLibraryResolver;
import com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
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

    VirtualPackageStateBuilder(Context context) {
        hostPermissions = new HostPermissionStateResolver(context);
    }

    VirtualPackageStateSnapshot build(SandboxRecord record, int virtualUserId,
                                      SandboxPolicyState policy,
                                      SandboxCatalogState catalog) throws Exception {
        if (record == null) throw new IllegalArgumentException("Package is not installed");
        if (policy == null) throw new IllegalArgumentException("policy is required");
        ManifestSet set = manifestsByRevision.get(record.sha256);
        if (set == null) {
            set = parse(record);
            manifestsByRevision.put(record.sha256, set);
        }
        if (!record.packageName.equals(set.packageName)) {
            throw new SecurityException("CATALOG_MANIFEST_PACKAGE_MISMATCH");
        }
        List<VirtualComponentSnapshot> components = new ArrayList<>();
        append(components, record.packageName, set.activities, "ACTIVITY", policy);
        append(components, record.packageName, set.services, "SERVICE", policy);
        append(components, record.packageName, set.receivers, "RECEIVER", policy);
        append(components, record.packageName, set.providers, "PROVIDER", policy);

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
        return new VirtualPackageStateSnapshot(record.packageName, virtualUserId,
                record.label, record.versionName, record.versionCode,
                record.signatureSha256, record.sha256, set.launcherActivity,
                set.applicationClass, effectivePackageEnabled(policy.packageState()),
                record.firstInstallAt, record.lastUpdateAt,
                "com.warden.virtualinstaller", record.splitNames(),
                new ArrayList<>(set.sharedLibraries), librarySnapshots, instrumentationSnapshots,
                components, permissions, appOps);
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
            set = parse(record);
            manifestsByRevision.put(record.sha256, set);
        }
        return set.permissions.contains(permission);
    }

    boolean declaresComponent(SandboxRecord record, String className) throws Exception {
        ManifestSet set = manifestsByRevision.get(record.sha256);
        if (set == null) {
            set = parse(record);
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

    private static ManifestSet parse(SandboxRecord record) throws Exception {
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
            } else if (set.launcherActivity.isEmpty() && !manifest.launcherActivity().isEmpty()) {
                set.launcherActivity = manifest.launcherActivity();
            }
            appendComponents(set.activities, manifest.activities());
            appendComponents(set.services, manifest.services());
            appendComponents(set.receivers, manifest.receivers());
            appendComponents(set.providers, manifest.providers());
            set.permissions.addAll(manifest.permissions()); set.sharedLibraries.addAll(manifest.sharedLibraries());
            set.sharedLibraryDependencies.addAll(manifest.sharedLibraryDependencies());
            set.providedSharedLibraries.addAll(manifest.providedSharedLibraries());
            set.instrumentations.addAll(manifest.instrumentations());
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
            if (existing == null) target.add(component);
            else existing.mergeFrom(component);
        }
    }

    private List<SharedLibraryResolver.AvailableLibrary> availableLibraries(
            SandboxCatalogState catalog) throws Exception {
        List<SharedLibraryResolver.AvailableLibrary> available = baseAvailableLibraries();
        if (catalog != null) {
            for (SandboxRecord installed : catalog.records()) {
                ManifestSet installedSet = manifestsByRevision.get(installed.sha256);
                if (installedSet == null) {
                    installedSet = parse(installed);
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
                               List<ManifestModel.Component> components, String type,
                               SandboxPolicyState policy) {
        for (ManifestModel.Component component : components) {
            String enabledSetting = policy.componentState(component.className());
            boolean enabled = effectiveComponentEnabled(component.enabled(), enabledSetting);
            List<VirtualIntentFilterSnapshot> filters = new ArrayList<>();
            for (ManifestModel.IntentFilter filter : component.intentFilters()) {
                List<VirtualIntentDataSnapshot> data = new ArrayList<>();
                for (ManifestModel.DataRule rule : filter.dataRules()) {
                    data.add(new VirtualIntentDataSnapshot(rule.scheme(), rule.host(), rule.path(),
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
                    processName(packageName, component), component.exported(), enabled,
                    component.isolatedProcess(), component.authorities(), component.permission(),
                    component.readPermission(), component.writePermission(), component.grantUriPermissions(),
                    enabledSetting, component.actions(), filters, providerPathRules));
        }
    }

    static boolean effectiveComponentEnabled(boolean manifestEnabled, String setting) {
        if (SandboxPolicyState.COMPONENT_ENABLED.equals(setting)) return true;
        if (SandboxPolicyState.COMPONENT_DISABLED.equals(setting)) return false;
        return manifestEnabled;
    }

    private static String processName(String packageName, ManifestModel.Component component) {
        if (component.isolatedProcess()) {
            return packageName + ":isolated_" + component.className().replaceAll("[^A-Za-z0-9_]", "_");
        }
        String declared = component.processName();
        if (declared == null || declared.trim().isEmpty()) return packageName;
        return declared.startsWith(":") ? packageName + declared : declared;
    }

    private static final class ManifestSet {
        String packageName = ""; String applicationClass = ""; String launcherActivity = "";
        final List<ManifestModel.Component> activities = new ArrayList<>();
        final List<ManifestModel.Component> services = new ArrayList<>();
        final List<ManifestModel.Component> receivers = new ArrayList<>();
        final List<ManifestModel.Component> providers = new ArrayList<>();
        final Set<String> permissions = new LinkedHashSet<>();
        final Set<String> sharedLibraries = new LinkedHashSet<>();
        final List<ManifestModel.SharedLibraryDependency> sharedLibraryDependencies = new ArrayList<>();
        final Set<String> providedSharedLibraries = new LinkedHashSet<>();
        final List<ManifestModel.Instrumentation> instrumentations = new ArrayList<>();
        List<ManifestModel.Component> allComponents() {
            List<ManifestModel.Component> values = new ArrayList<>();
            values.addAll(activities); values.addAll(services); values.addAll(receivers); values.addAll(providers);
            return values;
        }
    }
}
