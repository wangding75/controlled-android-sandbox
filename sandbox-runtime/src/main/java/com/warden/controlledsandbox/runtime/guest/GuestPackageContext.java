package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;

/**
 * Resource/code view returned by GuestContext.createPackageContext().
 *
 * <p>The view carries the target package's APK resources and optional Java class loader, while
 * component, Binder, storage and system-service calls remain owned by the creating Guest
 * session. This is the important distinction from returning a Host Context: visibility grants
 * access to another virtual package's resources, not permission to impersonate its UID or open
 * its private data directory.</p>
 */
final class GuestPackageContext extends GuestHostOperationDenyContext {
    private final GuestContext owner;
    private final String targetPackage;
    private final VirtualPackageStateSnapshot targetState;
    private final ApplicationInfo applicationInfo;
    private final GuestResourceLoader.LoadedResources loadedResources;
    private final ClassLoader classLoader;
    private final Resources resources;
    private final AssetManager assets;
    private final Resources.Theme theme;

    GuestPackageContext(GuestContext owner, String targetPackage,
                        VirtualPackageStateSnapshot targetState,
                        ApplicationInfo applicationInfo,
                        GuestResourceLoader.LoadedResources loadedResources,
                        ClassLoader classLoader) {
        super(owner);
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
        this.targetPackage = requirePackage(targetPackage);
        this.targetState = java.util.Objects.requireNonNull(targetState, "targetState");
        this.applicationInfo = new ApplicationInfo(
                java.util.Objects.requireNonNull(applicationInfo, "applicationInfo"));
        this.loadedResources = java.util.Objects.requireNonNull(loadedResources, "loadedResources");
        this.resources = loadedResources.resources;
        this.assets = loadedResources.assets;
        this.classLoader = java.util.Objects.requireNonNull(classLoader, "classLoader");
        this.theme = resources.newTheme();
    }

    void closeResources() {
        loadedResources.close();
    }

    @Override public Context getBaseContext() { return null; }
    @Override public String getPackageName() { return targetPackage; }
    /** Keep operation identity owned by the calling Guest, never by a resource-only view. */
    @Override public String getOpPackageName() { return owner.getOpPackageName(); }
    /** Resource-only views must not reintroduce the Host attribution source. */
    // See GuestContext: the static API stubs do not declare the hidden API31+ method.
    public android.content.AttributionSource getAttributionSource() {
        return owner.getAttributionSource();
    }
    @Override public Context getApplicationContext() { return owner.getApplicationContext(); }
    @Override public ClassLoader getClassLoader() { return classLoader; }
    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return assets; }
    @Override public Resources.Theme getTheme() { return theme; }
    @Override public void setTheme(int resid) { theme.applyStyle(resid, true); }
    @Override public ApplicationInfo getApplicationInfo() {
        return new ApplicationInfo(applicationInfo);
    }
    @Override public PackageManager getPackageManager() { return owner.getPackageManager(); }
    @Override public String getSystemServiceName(Class<?> serviceClass) {
        return owner.getSystemServiceName(serviceClass);
    }
    @Override public Object getSystemService(String name) {
        if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
            return LayoutInflater.from(owner.hostServiceContext()).cloneInContext(this);
        }
        return owner.getSystemService(name);
    }
    @Override public android.content.ContentResolver getContentResolver() {
        return owner.getContentResolver();
    }
    @Override public android.os.Looper getMainLooper() { return owner.getMainLooper(); }
    @Override public java.util.concurrent.Executor getMainExecutor() {
        return owner.getMainExecutor();
    }
    @Override public String getPackageCodePath() {
        return applicationInfo.sourceDir == null ? virtualApkPath(targetPackage)
                : applicationInfo.sourceDir;
    }
    @Override public String getPackageResourcePath() {
        return applicationInfo.publicSourceDir == null ? virtualApkPath(targetPackage)
                : applicationInfo.publicSourceDir;
    }

    /** ContextWrapper delegates component and storage operations to owner via its safe base. */
    @Override public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        return owner.createPackageContext(packageName, flags);
    }

    @Override public Context createContextForSplit(String splitName)
            throws PackageManager.NameNotFoundException {
        if (splitName != null && targetState.splitNames().contains(splitName.trim())) return this;
        throw new PackageManager.NameNotFoundException("Guest split is not installed: " + splitName);
    }

    @Override public Context createConfigurationContext(Configuration overrideConfiguration) {
        if (overrideConfiguration == null) {
            throw new IllegalArgumentException("overrideConfiguration is required");
        }
        Resources configured = new Resources(assets, resources.getDisplayMetrics(),
                copyConfiguration(overrideConfiguration));
        GuestResourceLoader.LoadedResources view = new GuestResourceLoader.LoadedResources(
                assets, configured, loadedResources.manifestMetadata, loadedResources.manifestAssets,
                java.util.Collections.emptyList());
        return new GuestPackageContext(owner, targetPackage, targetState, applicationInfo,
                view, classLoader);
    }

    public Context createCredentialProtectedStorageContext() {
        return this;
    }

    @Override public Context createDeviceProtectedStorageContext() {
        return this;
    }

    @Override public boolean isDeviceProtectedStorage() {
        return owner.isDeviceProtectedStorage();
    }

    private static Configuration copyConfiguration(Configuration source) {
        try {
            java.lang.reflect.Constructor<Configuration> constructor =
                    Configuration.class.getDeclaredConstructor(Configuration.class);
            constructor.setAccessible(true);
            return constructor.newInstance(source);
        } catch (ReflectiveOperationException unavailable) {
            return source;
        }
    }

    private static String virtualApkPath(String packageName) {
        return "/data/app/" + packageName + "/base.apk";
    }

    private static String requirePackage(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("targetPackage is required");
        }
        return value.trim();
    }
}
