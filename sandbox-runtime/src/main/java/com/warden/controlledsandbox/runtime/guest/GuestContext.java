package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Instance-scoped Context view used by Guest Application and components. */
public final class GuestContext extends ContextWrapper {
    private final GuestPackageSpec spec;
    private final ClassLoader classLoader;
    private final Resources resources;
    private final AssetManager assets;
    private final File dataRoot;
    private final ApplicationInfo applicationInfo;
    private volatile Application application;
    private final Map<String, SharedPreferences> preferences = new HashMap<>();

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets) {
        super(host);
        this.spec = spec;
        this.classLoader = classLoader;
        this.resources = resources;
        this.assets = assets;
        File instanceRoot = spec.dataRootFile();
        this.dataRoot = ensureDirectory(new File(instanceRoot, "data"));
        this.applicationInfo = new ApplicationInfo(host.getApplicationInfo());
        applicationInfo.packageName = spec.packageName;
        applicationInfo.sourceDir = spec.apkPath;
        applicationInfo.publicSourceDir = spec.apkPath;
        applicationInfo.nativeLibraryDir = spec.nativeLibraryDir;
        applicationInfo.dataDir = dataRoot.getAbsolutePath();
        applicationInfo.uid = spec.virtualUid;
        ensureDirectory(new File(dataRoot, "files"));
        ensureDirectory(new File(dataRoot, "cache"));
        ensureDirectory(new File(dataRoot, "databases"));
        ensureDirectory(new File(dataRoot, "code_cache"));
        ensureDirectory(new File(dataRoot, "webview"));
    }

    void application(Application value) { application = value; }

    @Override public String getPackageName() { return spec.packageName; }
    @Override public Context getApplicationContext() { return application == null ? this : application; }
    @Override public ClassLoader getClassLoader() { return classLoader; }
    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return assets; }
    @Override public ApplicationInfo getApplicationInfo() { return applicationInfo; }
    @Override public File getFilesDir() { return ensureDirectory(new File(dataRoot, "files")); }
    @Override public File getCacheDir() { return ensureDirectory(new File(dataRoot, "cache")); }
    @Override public File getCodeCacheDir() { return ensureDirectory(new File(dataRoot, "code_cache")); }
    @Override public File getDatabasePath(String name) { return new File(ensureDirectory(new File(dataRoot, "databases")), safeName(name)); }
    @Override public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        String safe = safeName(name);
        SharedPreferences existing = preferences.get(safe);
        if (existing != null) return existing;
        SharedPreferences created = new SandboxSharedPreferences(new File(ensureDirectory(new File(dataRoot, "shared_prefs")), safe + ".cspf"));
        preferences.put(safe, created);
        return created;
    }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException { return new FileInputStream(new File(getFilesDir(), safeName(name))); }
    @Override public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException { return new FileOutputStream(new File(getFilesDir(), safeName(name)), (mode & Context.MODE_APPEND) != 0); }
    @Override public boolean deleteFile(String name) { return new File(getFilesDir(), safeName(name)).delete(); }
    @Override public String[] fileList() { String[] files = getFilesDir().list(); return files == null ? new String[0] : files; }
    @Override public File getDir(String name, int mode) { return ensureDirectory(new File(dataRoot, "app_" + safeName(name))); }
    @Override public String getPackageCodePath() { return spec.apkPath; }
    @Override public String getPackageResourcePath() { return spec.apkPath; }

    private static File ensureDirectory(File file) {
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) {
            throw new IllegalStateException("Cannot create directory " + file);
        }
        return file;
    }

    private static String safeName(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("name is required");
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.equals(".") || safe.equals("..")) throw new IllegalArgumentException("unsafe name");
        return safe;
    }
}
