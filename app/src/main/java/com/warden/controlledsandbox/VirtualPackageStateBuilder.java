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

/** Builds immutable Binder package state from trusted catalog metadata and the matching APK revision. */
final class VirtualPackageStateBuilder {
    private final Map<String, ManifestModel> manifestsByRevision = new ConcurrentHashMap<>();

    VirtualPackageStateSnapshot build(SandboxRecord record, int virtualUserId,
                                      SandboxPolicyState policy) throws Exception {
        if (record == null) throw new IllegalArgumentException("Package is not installed");
        if (policy == null) throw new IllegalArgumentException("policy is required");
        ManifestModel manifest = manifestsByRevision.get(record.sha256);
        if (manifest == null) {
            manifest = parse(record);
            manifestsByRevision.put(record.sha256, manifest);
        }
        if (!record.packageName.equals(manifest.packageName())) {
            throw new SecurityException("CATALOG_MANIFEST_PACKAGE_MISMATCH");
        }
        List<VirtualComponentSnapshot> components = new ArrayList<>();
        append(components, record.packageName, manifest.activities(), "ACTIVITY");
        append(components, record.packageName, manifest.services(), "SERVICE");
        append(components, record.packageName, manifest.receivers(), "RECEIVER");
        append(components, record.packageName, manifest.providers(), "PROVIDER");

        Set<String> declared = new LinkedHashSet<>(manifest.permissions());
        List<VirtualPermissionSnapshot> permissions = new ArrayList<>();
        for (String permission : declared) {
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
                record.signatureSha256, record.sha256, manifest.launcherActivity(),
                manifest.applicationClass(), true, components, permissions, appOps);
    }

    boolean declaresPermission(SandboxRecord record, String permission) throws Exception {
        ManifestModel manifest = manifestsByRevision.get(record.sha256);
        if (manifest == null) {
            manifest = parse(record);
            manifestsByRevision.put(record.sha256, manifest);
        }
        return manifest.permissions().contains(permission);
    }

    void invalidate(String apkSha256) {
        if (apkSha256 != null) manifestsByRevision.remove(apkSha256);
    }

    private static ManifestModel parse(SandboxRecord record) throws Exception {
        File apk = new File(record.apkPath).getCanonicalFile();
        if (!apk.isFile()) throw new IllegalStateException("Trusted APK revision is missing: " + apk);
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry entry = archive.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IllegalArgumentException("APK manifest is missing");
            try (InputStream input = archive.getInputStream(entry)) {
                return new BinaryXmlManifestParser().parse(input);
            }
        }
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
}
