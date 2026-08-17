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
import java.util.Map;
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
        append(components, manifest.packageName(), manifest.applicationProcessName(),
                manifest.activities(), VirtualPackageMetadata.Type.ACTIVITY);
        append(components, manifest.packageName(), manifest.applicationProcessName(),
                manifest.services(), VirtualPackageMetadata.Type.SERVICE);
        append(components, manifest.packageName(), manifest.applicationProcessName(),
                manifest.receivers(), VirtualPackageMetadata.Type.RECEIVER);
        append(components, manifest.packageName(), manifest.applicationProcessName(),
                manifest.providers(), VirtualPackageMetadata.Type.PROVIDER);
        List<VirtualPackageMetadata.Filter> queryIntentFilters = new ArrayList<>();
        for (ManifestModel.QueryIntent query : manifest.queryIntents()) {
            List<VirtualPackageMetadata.DataRule> data = new ArrayList<>();
            for (ManifestModel.DataRule rule : query.dataRules()) {
                data.add(new VirtualPackageMetadata.DataRule(rule.scheme(), rule.host(), rule.port(), rule.path(),
                        rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
            }
            queryIntentFilters.add(new VirtualPackageMetadata.Filter(0,
                    new LinkedHashSet<>(query.actions()), new LinkedHashSet<>(query.categories()), data));
        }
        List<VirtualPackageMetadata.PermissionDeclaration> permissionDeclarations = new ArrayList<>();
        for (ManifestModel.PermissionDeclaration declaration : manifest.permissionDeclarations()) {
            permissionDeclarations.add(new VirtualPackageMetadata.PermissionDeclaration(
                    declaration.name(), declaration.group(), declaration.label(),
                    declaration.description(), declaration.labelRes(), declaration.descriptionRes(),
                    declaration.icon(), declaration.protectionLevel(), declaration.flags(),
                    declaration.tree()));
        }
        List<VirtualPackageMetadata.PermissionGroup> permissionGroups = new ArrayList<>();
        for (ManifestModel.PermissionGroupDeclaration group : manifest.permissionGroups()) {
            permissionGroups.add(new VirtualPackageMetadata.PermissionGroup(
                    group.name(), group.label(), group.description(), group.labelRes(),
                    group.descriptionRes(), group.icon(), group.requestRes(), group.priority(),
                    group.flags()));
        }
        return new VirtualPackageMetadata(manifest.packageName(), manifest.launcherActivity(),
                applicationInfo, components, "", 0L, "", 0L, 0L, "", List.of(), List.of(),
                List.of(), List.of(), true, manifest.queryPackages(),
                manifest.queryProviderAuthorities(), queryIntentFilters, Map.of(),
                permissionDeclarations, permissionGroups);
    }

    private static void append(List<VirtualPackageMetadata.Component> output, String packageName,
                               String applicationProcessName,
                               List<ManifestModel.Component> components,
                               VirtualPackageMetadata.Type type) {
        for (ManifestModel.Component component : components) {
            Set<String> actions = new LinkedHashSet<>(component.actions());
            List<VirtualPackageMetadata.Filter> filters = new ArrayList<>();
            for (ManifestModel.IntentFilter filter : component.intentFilters()) {
                List<VirtualPackageMetadata.DataRule> data = new ArrayList<>();
                for (ManifestModel.DataRule rule : filter.dataRules()) {
                    data.add(new VirtualPackageMetadata.DataRule(rule.scheme(), rule.host(), rule.port(), rule.path(),
                            rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
                }
                filters.add(new VirtualPackageMetadata.Filter(filter.priority(),
                        new LinkedHashSet<>(filter.actions()), new LinkedHashSet<>(filter.categories()), data));
            }
            List<VirtualPackageMetadata.ProviderPathRule> providerPathRules = new ArrayList<>();
            for (ManifestModel.ProviderPathRule rule : component.providerPathRules()) {
                providerPathRules.add(new VirtualPackageMetadata.ProviderPathRule(
                        rule.path(), rule.pathPrefix(), rule.pathPattern(),
                        rule.readPermission(), rule.writePermission(), rule.uriGrantRule()));
            }
            output.add(new VirtualPackageMetadata.Component(type, component.className(),
                    processName(packageName, applicationProcessName, component),
                    component.exported(), component.enabled(),
                    component.isolatedProcess(), actions, component.authorities(), component.permission(),
                    component.readPermission(), component.writePermission(), component.grantUriPermissions(),
                    "DEFAULT", filters, providerPathRules, null, component.launchMode(),
                    component.taskAffinity(), component.documentLaunchMode(), component.configChanges(),
                    component.screenOrientation(), component.windowSoftInputMode(), component.flags(),
                    component.excludeFromRecents(), component.noHistory(), component.finishOnTaskLaunch(),
                    component.clearTaskOnLaunch(), component.alwaysRetainTaskState(),
                    component.allowTaskReparenting(), component.resizeMode(), component.maxAspectRatio(),
                    component.minAspectRatio(), component.supportsPictureInPicture(), component.themeResId(),
                    component.foregroundServiceType(), component.stopWithTask(), component.directBootAware(),
                    component.multiprocess(), component.initOrder(), component.syncable(),
                    component.persistableMode(), component.targetActivity()));
        }
    }

    private static String processName(String packageName, String applicationProcessName,
                                      ManifestModel.Component component) {
        if (component.isolatedProcess()) {
            return packageName + ":isolated_" + component.className().replaceAll("[^A-Za-z0-9_]", "_");
        }
        String declared = component.processName() == null ? "" : component.processName().trim();
        if (declared.isEmpty()) declared = applicationProcessName == null
                ? "" : applicationProcessName.trim();
        if (declared.isEmpty()) return packageName;
        return declared.startsWith(":") ? packageName + declared : declared;
    }
}
