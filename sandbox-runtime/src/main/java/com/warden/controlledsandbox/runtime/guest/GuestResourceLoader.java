package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;
import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GuestResourceLoader {
    private GuestResourceLoader() { }

    /**
     * Reads only application-level manifest metadata for the Binder-owned package authority.
     * The same APK-backed AssetManager path used by LoadedApk bootstrap is used here so
     * PackageManager and bindApplication cannot disagree about resource-backed values.
     */
    public static android.os.Bundle readApplicationMetadata(Context host, String apkPath)
            throws Exception {
        LoadedResources loaded = load(host, apkPath, new String[0]);
        try {
            return loaded.manifestMetadata.application();
        } finally {
            loaded.close();
        }
    }

    /**
     * Reads component-level manifest metadata for the Binder-owned PackageManager projection.
     * The returned map is keyed by the fully qualified component class name and contains fresh
     * Bundle copies.  AssetManager remains the parser of record so resource-backed values follow
     * the same decoding rules as the Guest LoadedApk bootstrap path.
     */
    public static java.util.Map<String, android.os.Bundle> readComponentMetadata(
            Context host, String apkPath) throws Exception {
        LoadedResources loaded = load(host, apkPath, new String[0]);
        try {
            return loaded.manifestMetadata.componentMetadata();
        } finally {
            loaded.close();
        }
    }

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
        // API 36. That is usually a configuration split manifest and can omit base metadata.
        AssetManager baseManifestAssets = AssetManager.class.getDeclaredConstructor().newInstance();
        Method baseAddAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        baseAddAssetPath.setAccessible(true);
        addRequiredAssetPath(baseAddAssetPath, baseManifestAssets, apkPath, "base manifest");
        GuestManifestMetadata metadata = GuestManifestMetadata.read(baseManifestAssets, resources);
        android.os.Bundle application = metadata.application();
        android.util.Log.i("CS_GUEST_METADATA", "applicationMetaData="
                + (application == null ? 0 : application.size()));
        return new LoadedResources(assets, resources, metadata, baseManifestAssets,
                Collections.emptyList());
    }

    /**
     * Loads APK resources directly from Binder-transferred file capabilities. API 29+ keeps the
     * hidden ApkAssets FD constructor and AssetManager.setApkAssets path stable; using those
     * framework primitives preserves resource table/package-id semantics without asking an
     * isolated process to traverse the host's /data/app tree.
     */
    static LoadedResources load(Context host, ParcelFileDescriptor apk,
                                List<ParcelFileDescriptor> splitDescriptors) throws Exception {
        if (apk == null || apk.getFd() < 0) {
            throw new IllegalArgumentException("FD-backed base APK is required");
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            throw new IllegalStateException("ISOLATED_APK_RESOURCES_FD_UNSUPPORTED_API");
        }
        ArrayList<ParcelFileDescriptor> retained = new ArrayList<>();
        try {
            ArrayList<ParcelFileDescriptor> all = new ArrayList<>();
            all.add(apk);
            if (splitDescriptors != null) all.addAll(splitDescriptors);
            Object[] loaded = new Object[all.size()];
            Class<?> apkAssetsClass = Class.forName("android.content.res.ApkAssets");
            for (int index = 0; index < all.size(); index++) {
                ParcelFileDescriptor descriptor = all.get(index);
                if (descriptor == null || descriptor.getFd() < 0) {
                    throw new IllegalArgumentException("Invalid APK resource capability: " + index);
                }
                ParcelFileDescriptor duplicate = descriptor.dup();
                retained.add(duplicate);
                rewind(duplicate);
                loaded[index] = loadApkAssetsFromFd(apkAssetsClass,
                        duplicate.getFileDescriptor(), "guest-apk-" + index);
            }
            AssetManager assets = newAssetManager(apkAssetsClass, loaded);
            ParcelFileDescriptor baseManifestDescriptor = apk.dup();
            retained.add(baseManifestDescriptor);
            rewind(baseManifestDescriptor);
            AssetManager baseManifestAssets = newAssetManager(apkAssetsClass,
                    new Object[] {loadApkAssetsFromFd(apkAssetsClass,
                            baseManifestDescriptor.getFileDescriptor(), "guest-base-manifest")});
            Resources hostResources = host.getResources();
            Resources resources = new Resources(assets, hostResources.getDisplayMetrics(),
                    hostResources.getConfiguration());
            GuestManifestMetadata metadata = GuestManifestMetadata.read(baseManifestAssets, resources);
            android.util.Log.i("CS_GUEST_RESOURCES", "FD_BACKED_APK_ASSETS_READY count=" + loaded.length);
            return new LoadedResources(assets, resources, metadata, baseManifestAssets, retained);
        } catch (Throwable error) {
            for (ParcelFileDescriptor descriptor : retained) {
                try { descriptor.close(); } catch (Throwable ignored) { }
            }
            throw error;
        }
    }

    private static Object loadApkAssetsFromFd(Class<?> apkAssetsClass, FileDescriptor descriptor,
                                              String friendlyName) throws Exception {
        int format = 0;
        try {
            format = apkAssetsClass.getField("FORMAT_APK").getInt(null);
        } catch (ReflectiveOperationException ignored) { }
        Class<?> assetsProvider = Class.forName("android.content.res.loader.AssetsProvider");
        Method selected = null;
        for (Method method : apkAssetsClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"loadFromFd".equals(method.getName()) || parameters.length != 4
                    || parameters[0] != FileDescriptor.class
                    || parameters[1] != String.class || parameters[2] != int.class
                    || parameters[3] != assetsProvider) continue;
            selected = method;
            break;
        }
        if (selected == null) {
            throw new IllegalStateException("ISOLATED_APK_ASSETS_LOAD_FROM_FD_UNAVAILABLE");
        }
        selected.setAccessible(true);
        Object result = selected.invoke(null, descriptor, friendlyName, format, null);
        if (result == null) throw new IllegalStateException("ISOLATED_APK_ASSETS_LOAD_FROM_FD_FAILED");
        return result;
    }

    private static void rewind(ParcelFileDescriptor descriptor) throws Exception {
        ParcelFileDescriptor duplicate = descriptor.dup();
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(duplicate)) {
            input.getChannel().position(0L);
        }
    }

    private static AssetManager newAssetManager(Class<?> apkAssetsClass, Object[] apkAssets)
            throws Exception {
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        Object typedArray = java.lang.reflect.Array.newInstance(apkAssetsClass, apkAssets.length);
        for (int index = 0; index < apkAssets.length; index++) {
            java.lang.reflect.Array.set(typedArray, index, apkAssets[index]);
        }
        Method setApkAssets = AssetManager.class.getDeclaredMethod("setApkAssets",
                typedArray.getClass(), boolean.class);
        setApkAssets.setAccessible(true);
        setApkAssets.invoke(assets, typedArray, true);
        return assets;
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
        final AssetManager manifestAssets;
        private final List<ParcelFileDescriptor> retainedDescriptors;

        LoadedResources(AssetManager assets, Resources resources, GuestManifestMetadata manifestMetadata,
                        AssetManager manifestAssets, List<ParcelFileDescriptor> retainedDescriptors) {
            this.assets = assets;
            this.resources = resources;
            this.manifestMetadata = manifestMetadata;
            this.manifestAssets = manifestAssets;
            this.retainedDescriptors = List.copyOf(retainedDescriptors);
        }

        ParcelFileDescriptor baseDescriptor() {
            return retainedDescriptors.isEmpty() ? null : retainedDescriptors.get(0);
        }

        List<ParcelFileDescriptor> splitDescriptors() {
            if (retainedDescriptors.size() <= 1) return Collections.emptyList();
            return retainedDescriptors.subList(1, retainedDescriptors.size());
        }

        void close() {
            try { assets.close(); } catch (Throwable ignored) { }
            if (manifestAssets != assets) {
                try { manifestAssets.close(); } catch (Throwable ignored) { }
            }
            for (ParcelFileDescriptor descriptor : retainedDescriptors) {
                try { descriptor.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
