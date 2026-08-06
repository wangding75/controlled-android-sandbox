package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Instance-scoped Context view used by Guest Application and components.
 *
 * <p>The host Context remains the system-service transport underneath Android's ContextWrapper,
 * but Guest-visible identity and storage methods must terminate at this object. In particular,
 * callers never receive the host base Context through the normal Context API.</p>
 */
public final class GuestContext extends GuestHostOperationDenyContext {
    private static final Object PREFERENCE_LOCK_TIE = new Object();
    private final Context hostServiceContext;
    private final PackageManager packageManager;
    private final GuestPackageSpec spec;
    private final ClassLoader classLoader;
    private final Resources resources;
    private final AssetManager assets;
    private final File instanceRoot;
    private final File dataRoot;
    private final File externalRoot;
    private final ApplicationInfo applicationInfo;
    private final GuestContextComponentRouter componentRouter;
    private final GuestCapabilityGate capabilityGate;
    private final GuestStorageNameCodec storageNames;
    private final SharedState sharedState;
    final GuestDynamicReceiverRegistry dynamicReceivers;
    final GuestMainThreadDispatcher mainThread;
    private final boolean deviceProtected;
    private final Map<String, SharedPreferences> preferences = new HashMap<>();

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets) {
        this(host, spec, classLoader, resources, assets, host.getPackageManager(), false,
                new SharedState(new GuestCapabilityGate(spec.packageState.permissions()), classLoader));
    }

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets, PackageManager packageManager) {
        this(host, spec, classLoader, resources, assets, packageManager, false,
                new SharedState(new GuestCapabilityGate(spec.packageState.permissions()), classLoader));
    }

    private GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                         Resources resources, AssetManager assets, PackageManager packageManager,
                         boolean deviceProtected,
                         SharedState sharedState) {
        super();
        this.hostServiceContext = host.getApplicationContext();
        this.packageManager = java.util.Objects.requireNonNull(packageManager, "packageManager");
        this.spec = spec;
        this.classLoader = classLoader;
        this.resources = resources;
        this.assets = assets;
        this.deviceProtected = deviceProtected;
        this.sharedState = sharedState;
        this.instanceRoot = ensureDirectory(spec.dataRootFile());
        this.dataRoot = ensureDirectory(new File(instanceRoot,
                deviceProtected ? "device_protected" : "data"));
        this.externalRoot = ensureDirectory(new File(instanceRoot, "external"));
        this.storageNames = new GuestStorageNameCodec(instanceRoot);
        this.applicationInfo = GuestApplicationInfoFactory.create(spec, dataRoot.getAbsolutePath());
        this.dynamicReceivers = sharedState.dynamicReceivers;
        this.mainThread = sharedState.mainThread;
        this.capabilityGate = sharedState.capabilityGate;
        this.componentRouter = new GuestContextComponentRouter(
                this, spec, packageManager, sharedState.dynamicReceivers, sharedState.mainThread);
        ensureDirectory(new File(dataRoot, "files"));
        ensureDirectory(new File(dataRoot, "cache"));
        ensureDirectory(new File(dataRoot, "databases"));
        ensureDirectory(new File(dataRoot, "code_cache"));
        ensureDirectory(new File(dataRoot, "no_backup"));
        ensureDirectory(new File(dataRoot, "shared_prefs"));
        ensureDirectory(new File(dataRoot, "webview"));
        ensureDirectory(new File(externalRoot, "files"));
        ensureDirectory(new File(externalRoot, "cache"));
        ensureDirectory(new File(externalRoot, "obb"));
        ensureDirectory(new File(externalRoot, "media"));
    }

    void application(Application value) { sharedState.application = value; }
    void updatePermissionState(java.util.List<com.warden.controlledsandbox.contract.VirtualPermissionSnapshot> permissions) {
        capabilityGate.replace(permissions);
    }
    void sealSystemServices(java.util.Map<String, Boolean> installedHooks) {
        sharedState.systemServices.seal(installedHooks);
    }

    /** Prevents ordinary Guest code from unwrapping this Context into the host Context. */
    @Override public Context getBaseContext() { return this; }
    @Override public String getPackageName() { return spec.packageName; }
    @Override public Context getApplicationContext() {
        return sharedState.application == null ? this : sharedState.application;
    }
    @Override public ClassLoader getClassLoader() { return classLoader; }
    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return assets; }
    @Override public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(applicationInfo); }
    @Override public PackageManager getPackageManager() {
        sharedState.systemServices.requireHookAvailable("packageManager", "getPackageManager");
        return packageManager;
    }
    @Override public Object getSystemService(String name) {
        if (!sharedState.systemServices.isKnownService(name)) return null;
        capabilityGate.requireService(name);
        sharedState.systemServices.requireAvailable(name);
        return hostServiceContext.getSystemService(name);
    }
    @Override public ContentResolver getContentResolver() {
        return hostServiceContext.getContentResolver();
    }
    @Override public Executor getMainExecutor() { return hostServiceContext.getMainExecutor(); }

    @Override public void startActivity(Intent intent) { componentRouter.startActivity(intent, null); }
    @Override public void startActivity(Intent intent, Bundle options) {
        componentRouter.startActivity(intent, options);
    }
    @Override public void startActivities(Intent[] intents) { startActivities(intents, null); }
    @Override public void startActivities(Intent[] intents, Bundle options) {
        if (intents == null) throw new IllegalArgumentException("intents is required");
        for (Intent intent : intents) componentRouter.startActivity(intent, options);
    }
    @Override public ComponentName startService(Intent service) {
        return componentRouter.startService(service, false);
    }
    @Override public ComponentName startForegroundService(Intent service) {
        return componentRouter.startService(service, true);
    }
    @Override public boolean stopService(Intent service) { return componentRouter.stopService(service); }
    @Override public boolean bindService(Intent service, ServiceConnection connection, int flags) {
        return componentRouter.bindService(service, connection, flags, null);
    }
    @Override public boolean bindService(Intent service, int flags, Executor executor,
            ServiceConnection connection) {
        return componentRouter.bindService(service, connection, flags, executor);
    }
    @Override public void unbindService(ServiceConnection connection) {
        componentRouter.unbindService(connection);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return componentRouter.registerReceiver(receiver, filter, null, null, Context.RECEIVER_NOT_EXPORTED);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return componentRouter.registerReceiver(receiver, filter, null, null, flags);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return componentRouter.registerReceiver(receiver, filter, broadcastPermission, scheduler,
                Context.RECEIVER_NOT_EXPORTED);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler, int flags) {
        return componentRouter.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags);
    }
    @Override public void unregisterReceiver(BroadcastReceiver receiver) {
        componentRouter.unregisterReceiver(receiver);
    }
    @Override public void sendBroadcast(Intent intent) { componentRouter.sendBroadcast(intent, null); }
    @Override public void sendBroadcast(Intent intent, String receiverPermission) {
        componentRouter.sendBroadcast(intent, receiverPermission);
    }
    @Override public void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        componentRouter.sendBroadcast(intent, receiverPermission, options);
    }
    @Override public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        componentRouter.sendOrderedBroadcast(intent, receiverPermission, null, null, null,
                0, null, null);
    }
    @Override public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        componentRouter.sendOrderedBroadcast(intent, receiverPermission, null, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }
    @Override public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            Bundle options, BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        componentRouter.sendOrderedBroadcast(intent, receiverPermission, options, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    BroadcastReceiver dynamicReceiver(String receiverId) {
        return dynamicReceivers.require(receiverId);
    }
    void clearDynamicReceivers() { dynamicReceivers.clear(); }

    @Override public File getDataDir() { return dataRoot; }
    @Override public File getFilesDir() { return ensureDirectory(new File(dataRoot, "files")); }
    @Override public File getCacheDir() { return ensureDirectory(new File(dataRoot, "cache")); }
    @Override public File getCodeCacheDir() { return ensureDirectory(new File(dataRoot, "code_cache")); }
    @Override public File getNoBackupFilesDir() { return ensureDirectory(new File(dataRoot, "no_backup")); }
    @Override public File getDatabasePath(String name) {
        return storageNames.resolve(ensureDirectory(new File(dataRoot, "databases")),
                "database", name, "", "", "-journal", "-wal", "-shm");
    }
    @Override public SQLiteDatabase openOrCreateDatabase(
            String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(
            String name, int mode, SQLiteDatabase.CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory, errorHandler);
    }
    @Override public boolean moveDatabaseFrom(Context sourceContext, String name) {
        GuestContext source = compatibleStorageSource(sourceContext);
        File sourceParent = ensureDirectory(new File(source.dataRoot, "databases"));
        File targetParent = ensureDirectory(new File(dataRoot, "databases"));
        File sourceFile = source.storageNames.resolve(sourceParent,
                "database", name, "", "", "-journal", "-wal", "-shm");
        if (!sourceFile.isFile()) return false;
        File targetFile = storageNames.resolve(targetParent,
                "database", name, "", "", "-journal", "-wal", "-shm");
        boolean moved = GuestStorageTransferCoordinator.move(instanceRoot, sourceFile, targetFile,
                "-journal", "-wal", "-shm");
        if (moved) {
            source.storageNames.release(sourceParent, "database", name,
                    "", "", "-journal", "-wal", "-shm");
        }
        return moved;
    }
    @Override public boolean deleteDatabase(String name) {
        File parent = ensureDirectory(new File(dataRoot, "databases"));
        File database = getDatabasePath(name);
        boolean deleted = SQLiteDatabase.deleteDatabase(database);
        storageNames.release(parent, "database", name, "", "", "-journal", "-wal", "-shm");
        return deleted;
    }
    @Override public String[] databaseList() {
        return storageNames.listExisting(ensureDirectory(new File(dataRoot, "databases")),
                "database", "", "", "-journal", "-wal", "-shm");
    }
    @Override public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences existing = preferences.get(name);
        if (existing != null) return existing;
        File file = storageNames.resolve(ensureDirectory(new File(dataRoot, "shared_prefs")),
                "shared_preferences", name, "", ".cspf", ".tmp");
        SharedPreferences created = new SandboxSharedPreferences(file);
        preferences.put(name, created);
        return created;
    }
    @Override public synchronized boolean deleteSharedPreferences(String name) {
        preferences.remove(name);
        File file = storageNames.resolve(ensureDirectory(new File(dataRoot, "shared_prefs")),
                "shared_preferences", name, "", ".cspf", ".tmp");
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        boolean deleted = (!file.exists() || file.delete())
                && (!temporary.exists() || temporary.delete());
        storageNames.release(file.getParentFile(), "shared_preferences", name,
                "", ".cspf", ".tmp");
        return deleted;
    }
    @Override public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        GuestContext source = compatibleStorageSource(sourceContext);
        File sourceParent = ensureDirectory(new File(source.dataRoot, "shared_prefs"));
        File targetParent = ensureDirectory(new File(dataRoot, "shared_prefs"));
        File sourceFile = source.storageNames.resolve(sourceParent,
                "shared_preferences", name, "", ".cspf", ".tmp");
        if (!sourceFile.isFile()) return false;
        File targetFile = storageNames.resolve(targetParent,
                "shared_preferences", name, "", ".cspf", ".tmp");
        SandboxSharedPreferences sourceCached = source.cachedPreferences(name);
        SandboxSharedPreferences targetCached = cachedPreferences(name);
        boolean moved = withPreferenceLocks(sourceCached, targetCached, () -> {
            boolean result = GuestStorageTransferCoordinator.move(
                    instanceRoot, sourceFile, targetFile, ".tmp");
            if (result) {
                if (sourceCached != null) sourceCached.invalidateAfterMove();
                if (targetCached != null && targetCached != sourceCached) {
                    targetCached.invalidateAfterMove();
                }
            }
            return result;
        });
        if (moved) {
            synchronized (source) { source.preferences.remove(name); }
            synchronized (this) { preferences.remove(name); }
            source.storageNames.release(sourceParent, "shared_preferences", name,
                    "", ".cspf", ".tmp");
        }
        return moved;
    }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(getFileStreamPath(name));
    }
    @Override public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        return new FileOutputStream(getFileStreamPath(name), (mode & Context.MODE_APPEND) != 0);
    }
    @Override public File getFileStreamPath(String name) {
        return storageNames.resolve(getFilesDir(), "file", name, "", "");
    }
    @Override public boolean deleteFile(String name) {
        File file = getFileStreamPath(name);
        boolean deleted = !file.exists() || file.delete();
        storageNames.release(getFilesDir(), "file", name, "", "");
        return deleted;
    }
    @Override public String[] fileList() {
        return storageNames.listExisting(getFilesDir(), "file", "", "");
    }
    @Override public File getDir(String name, int mode) {
        return ensureDirectory(storageNames.resolve(dataRoot, "dir", name, "app_", ""));
    }
    @Override public File getExternalFilesDir(String type) {
        return scopedExternalDirectory("files", type);
    }
    @Override public File[] getExternalFilesDirs(String type) {
        return new File[]{getExternalFilesDir(type)};
    }
    @Override public File getExternalCacheDir() {
        return ensureDirectory(new File(externalRoot, "cache"));
    }
    @Override public File[] getExternalCacheDirs() {
        return new File[]{getExternalCacheDir()};
    }
    @Override public File getObbDir() { return ensureDirectory(new File(externalRoot, "obb")); }
    @Override public File[] getObbDirs() { return new File[]{getObbDir()}; }
    @Override public File[] getExternalMediaDirs() {
        return new File[]{ensureDirectory(new File(externalRoot, "media"))};
    }
    @Override public String getPackageCodePath() { return spec.apkPath; }
    @Override public String getPackageResourcePath() { return spec.apkPath; }

    @Override public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        if (spec.packageName.equals(packageName)) return this;
        throw new PackageManager.NameNotFoundException(
                "Guest package context is unavailable for " + packageName);
    }

    @Override public Context createContextForSplit(String splitName)
            throws PackageManager.NameNotFoundException {
        if (splitName != null && !splitName.trim().isEmpty() && spec.hasSplit(splitName)) return this;
        throw new PackageManager.NameNotFoundException("Guest split is not installed: " + splitName);
    }

    @Override public Context createConfigurationContext(Configuration overrideConfiguration) {
        if (overrideConfiguration == null) throw new IllegalArgumentException("overrideConfiguration is required");
        return this;
    }

    @Override public Context createCredentialProtectedStorageContext() {
        return deviceProtected ? storageContext(false) : this;
    }

    @Override public Context createDeviceProtectedStorageContext() {
        return deviceProtected ? this : storageContext(true);
    }

    @Override public boolean isDeviceProtectedStorage() { return deviceProtected; }

    private GuestContext storageContext(boolean targetDeviceProtected) {
        return new GuestContext(hostServiceContext, spec, classLoader, resources, assets,
                packageManager, targetDeviceProtected, sharedState);
    }

    private synchronized SandboxSharedPreferences cachedPreferences(String name) {
        SharedPreferences value = preferences.get(name);
        return value instanceof SandboxSharedPreferences ? (SandboxSharedPreferences) value : null;
    }


    private static boolean withPreferenceLocks(SandboxSharedPreferences first,
                                               SandboxSharedPreferences second,
                                               BooleanOperation operation) {
        if (first == null && second == null) return operation.run();
        if (first == null) synchronized (second) { return operation.run(); }
        if (second == null || first == second) synchronized (first) { return operation.run(); }
        int firstHash = System.identityHashCode(first);
        int secondHash = System.identityHashCode(second);
        if (firstHash == secondHash) {
            synchronized (PREFERENCE_LOCK_TIE) {
                synchronized (first) {
                    synchronized (second) { return operation.run(); }
                }
            }
        }
        SandboxSharedPreferences low = firstHash < secondHash ? first : second;
        SandboxSharedPreferences high = low == first ? second : first;
        synchronized (low) {
            synchronized (high) { return operation.run(); }
        }
    }

    private GuestContext compatibleStorageSource(Context sourceContext) {
        if (!(sourceContext instanceof GuestContext)) {
            throw new SecurityException(GuestStorageTransferCoordinator.IDENTITY_MISMATCH
                    + ":source is not a GuestContext");
        }
        GuestContext source = (GuestContext) sourceContext;
        try {
            if (!spec.packageName.equals(source.spec.packageName)
                    || spec.virtualUserId != source.spec.virtualUserId
                    || !instanceRoot.getCanonicalFile().equals(source.instanceRoot.getCanonicalFile())) {
                throw new SecurityException(GuestStorageTransferCoordinator.IDENTITY_MISMATCH);
            }
        } catch (java.io.IOException error) {
            throw new SecurityException(GuestStorageTransferCoordinator.IDENTITY_MISMATCH, error);
        }
        return source;
    }

    private File scopedExternalDirectory(String category, String type) {
        File categoryRoot = ensureDirectory(new File(externalRoot, category));
        return type == null || type.isEmpty()
                ? categoryRoot
                : ensureDirectory(storageNames.resolve(categoryRoot,
                        "external_" + category, type, "", ""));
    }

    private interface BooleanOperation { boolean run(); }

    private static final class SharedState {
        final GuestCapabilityGate capabilityGate;
        final GuestSystemServiceBoundary systemServices = new GuestSystemServiceBoundary();
        final GuestDynamicReceiverRegistry dynamicReceivers = new GuestDynamicReceiverRegistry();
        final GuestMainThreadDispatcher mainThread;
        volatile Application application;
        SharedState(GuestCapabilityGate capabilityGate, ClassLoader classLoader) {
            this.capabilityGate = capabilityGate;
            this.mainThread = new GuestMainThreadDispatcher(classLoader);
        }
    }

    private static File ensureDirectory(File file) {
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) {
            throw new IllegalStateException("Cannot create directory " + file);
        }
        return file;
    }

}
