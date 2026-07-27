package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.PackageAppOpSnapshot;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
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

    VirtualPackageStateSnapshot build(SandboxRecord record, int virtualUserId,
                                      SandboxPolicyState policy) throws Exception {
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
        append(components, record.packageName, set.activities, "ACTIVITY");
        append(components, record.packageName, set.services, "SERVICE");
        append(components, record.packageName, set.receivers, "RECEIVER");
        append(components, record.packageName, set.providers, "PROVIDER");

        List<VirtualPermissionSnapshot> permissions = new ArrayList<>();
        for (String permission : set.permissions) {
            String decision = policy.permissionDecision(permission);
            boolean granted = !SandboxPolicyState.PERMISSION_DENIED.equals(decision);
            permissions.add(new VirtualPermissionSnapshot(permission, decision, granted));
        }
        List<PackageAppOpSnapshot> appOps = new ArrayList<>();
        for (Map.Entry<String, String> item : policy.appOpModes().entrySet()) {
            appOps.add(new PackageAppOpSnapshot(item.getKey(), item.getValue()));
        }
        return new VirtualPackageStateSnapshot(record.packageName, virtualUserId,
                record.label, record.versionName, record.versionCode,
                record.signatureSha256, record.sha256, set.launcherActivity,
                set.applicationClass, true, record.splitNames(),
                new ArrayList<>(set.sharedLibraries), components, permissions, appOps);
    }

    boolean declaresPermission(SandboxRecord record, String permission) throws Exception {
        ManifestSet set = manifestsByRevision.get(record.sha256);
        if (set == null) {
            set = parse(record);
            manifestsByRevision.put(record.sha256, set);
        }
        return set.permissions.contains(permission);
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
            set.activities.addAll(manifest.activities()); set.services.addAll(manifest.services());
            set.receivers.addAll(manifest.receivers()); set.providers.addAll(manifest.providers());
            set.permissions.addAll(manifest.permissions()); set.sharedLibraries.addAll(manifest.sharedLibraries());
        }
        return set;
    }

    private static void append(List<VirtualComponentSnapshot> output, String packageName,
                               List<ManifestModel.Component> components, String type) {
        for (ManifestModel.Component component : components) {
            output.add(new VirtualComponentSnapshot(type, component.className(),
                    processName(packageName, component), component.exported(), component.enabled(),
                    component.isolatedProcess(), component.authorities(), component.permission(),
                    component.actions()));
        }
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
    }
}
