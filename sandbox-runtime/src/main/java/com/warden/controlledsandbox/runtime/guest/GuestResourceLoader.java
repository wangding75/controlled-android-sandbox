package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import java.lang.reflect.Method;

public final class GuestResourceLoader {
    private GuestResourceLoader() { }

    static LoadedResources load(Context host, String apkPath, String[] splitPaths) throws Exception {
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        addAssetPath.setAccessible(true);
        addRequiredAssetPath(addAssetPath, assets, apkPath, "base");
        if (splitPaths != null) {
            for (String splitPath : splitPaths) addRequiredAssetPath(addAssetPath, assets, splitPath, "split");
        }
        Resources hostResources = host.getResources();
        Resources resources = new Resources(assets, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
        // AssetManager resolves a colliding AndroidManifest.xml entry to the last added path on
        // API 36.  That is usually a configuration split manifest and can omit base application
        // metadata.  Keep the merged asset/resource view for app resources, but parse manifest
        // metadata from a base-only AssetManager.
        AssetManager baseManifestAssets = AssetManager.class.getDeclaredConstructor().newInstance();
        Method baseAddAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        baseAddAssetPath.setAccessible(true);
        addRequiredAssetPath(baseAddAssetPath, baseManifestAssets, apkPath, "base manifest");
        GuestManifestMetadata metadata = GuestManifestMetadata.read(baseManifestAssets, resources);
        android.os.Bundle application = metadata.application();
        android.util.Log.i("CS_GUEST_METADATA", "applicationMetaData="
                + (application == null ? 0 : application.size()));
        return new LoadedResources(assets, resources, metadata);
    }

    private static void addRequiredAssetPath(Method addAssetPath, AssetManager assets,
                                             String path, String kind) throws Exception {
        Object cookie = addAssetPath.invoke(assets, path);
        if (!(cookie instanceof Integer) || ((Integer) cookie) == 0) {
            throw new IllegalStateException("AssetManager rejected Guest " + kind + " APK: " + path);
        }
    }

    static final class LoadedResources {
        final AssetManager assets;
        final Resources resources;
        final GuestManifestMetadata manifestMetadata;
        LoadedResources(AssetManager assets, Resources resources, GuestManifestMetadata manifestMetadata) {
            this.assets = assets;
            this.resources = resources;
            this.manifestMetadata = manifestMetadata;
        }
    }
}
