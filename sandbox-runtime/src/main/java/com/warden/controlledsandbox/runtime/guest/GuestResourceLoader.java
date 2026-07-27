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
        return new LoadedResources(assets, resources);
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
        LoadedResources(AssetManager assets, Resources resources) { this.assets = assets; this.resources = resources; }
    }
}
