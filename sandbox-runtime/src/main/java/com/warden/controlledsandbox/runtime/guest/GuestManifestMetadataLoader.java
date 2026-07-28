package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Converts the installed APK manifest into the framework module's immutable virtual query model. */
public final class GuestManifestMetadataLoader {
    private GuestManifestMetadataLoader() { }

    static VirtualPackageMetadata load(File apk, ApplicationInfo applicationInfo) throws Exception {
        ManifestModel manifest;
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry entry = archive.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IllegalArgumentException("APK manifest is missing");
            try (InputStream input = archive.getInputStream(entry)) {
                manifest = new BinaryXmlManifestParser().parse(input);
            }
        }
        List<VirtualPackageMetadata.Component> components = new ArrayList<>();
        append(components, manifest.packageName(), manifest.activities(), VirtualPackageMetadata.Type.ACTIVITY);
        append(components, manifest.packageName(), manifest.services(), VirtualPackageMetadata.Type.SERVICE);
        append(components, manifest.packageName(), manifest.receivers(), VirtualPackageMetadata.Type.RECEIVER);
        append(components, manifest.packageName(), manifest.providers(), VirtualPackageMetadata.Type.PROVIDER);
        return new VirtualPackageMetadata(manifest.packageName(), manifest.launcherActivity(),
                applicationInfo, components);
    }

    private static void append(List<VirtualPackageMetadata.Component> output, String packageName,
                               List<ManifestModel.Component> components,
                               VirtualPackageMetadata.Type type) {
        for (ManifestModel.Component component : components) {
            Set<String> actions = new LinkedHashSet<>(component.actions());
            List<VirtualPackageMetadata.Filter> filters = new ArrayList<>();
            for (ManifestModel.IntentFilter filter : component.intentFilters()) {
                List<VirtualPackageMetadata.DataRule> data = new ArrayList<>();
                for (ManifestModel.DataRule rule : filter.dataRules()) {
                    data.add(new VirtualPackageMetadata.DataRule(rule.scheme(), rule.host(), rule.path(),
                            rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
                }
                filters.add(new VirtualPackageMetadata.Filter(filter.priority(),
                        new LinkedHashSet<>(filter.actions()), new LinkedHashSet<>(filter.categories()), data));
            }
            output.add(new VirtualPackageMetadata.Component(type, component.className(),
                    processName(packageName, component), component.exported(), component.enabled(),
                    component.isolatedProcess(), actions, component.authorities(), component.permission(),
                    "DEFAULT", filters));
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
