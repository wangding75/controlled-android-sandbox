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
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
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
    private final ContentResolver contentResolver;
    private final GuestPackageSpec spec;
    private ClassLoader classLoader;
    private final Resources resources;
    private final AssetManager assets;
    private final File instanceRoot;
    private final File dataRoot;
    private final File externalRoot;
    private final boolean capabilityBackedStorage;
    private final ApplicationInfo applicationInfo;
    private final GuestContextComponentRouter componentRouter;
    private final GuestWebViewProviderServiceBridge webViewProviderServices;
    private final GuestCapabilityGate capabilityGate;
    private final GuestStorageNameCodec storageNames;
    private final SharedState sharedState;
    private final Context unwrapBoundary;
    private final Resources.Theme frameworkTheme;
    private final Map<String, GuestPackageContext> packageContexts = new HashMap<>();
    /** ContextImpl caches this context-bound service; cloning it for every lookup is observable. */
    private volatile LayoutInflater guestLayoutInflater;
    private final Object layoutInflaterLock = new Object();
    private volatile GuestActivityThreadServiceBridge serviceFrameworkBridge;
    final GuestDynamicReceiverRegistry dynamicReceivers;
    final GuestMainThreadDispatcher mainThread;
    private final boolean deviceProtected;
    private final Map<String, SharedPreferences> preferences = new HashMap<>();

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets) {
        this(host, spec, classLoader, resources, assets, host.getPackageManager(), false,
                new SharedState(new GuestCapabilityGate(spec.packageState.permissions()), classLoader),
                null, "", null);
    }

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets, PackageManager packageManager) {
        this(host, spec, classLoader, resources, assets, packageManager, false,
                new SharedState(new GuestCapabilityGate(spec.packageState.permissions()), classLoader),
                null, "", null);
    }

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets, PackageManager packageManager,
                 Bundle applicationMetadata) {
        this(host, spec, classLoader, resources, assets, packageManager, applicationMetadata, "");
    }

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets, PackageManager packageManager,
                 Bundle applicationMetadata, String appComponentFactory) {
        this(host, spec, classLoader, resources, assets, packageManager, false,
                new SharedState(new GuestCapabilityGate(spec.packageState.permissions()), classLoader),
                applicationMetadata, appComponentFactory, null);
    }

    GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                 Resources resources, AssetManager assets, PackageManager packageManager,
                 Bundle applicationMetadata, String appComponentFactory,
                 ApplicationInfo parsedApplicationInfo) {
        this(host, spec, classLoader, resources, assets, packageManager, false,
                new SharedState(new GuestCapabilityGate(spec.packageState.permissions()), classLoader),
                applicationMetadata, appComponentFactory, parsedApplicationInfo);
    }

    private GuestContext(Context host, GuestPackageSpec spec, ClassLoader classLoader,
                         Resources resources, AssetManager assets, PackageManager packageManager,
                         boolean deviceProtected,
                         SharedState sharedState, Bundle applicationMetadata,
                         String appComponentFactory,
                         ApplicationInfo parsedApplicationInfo) {
        // ContextWrapper has a small set of hidden framework helpers (for example
        // getDisplayNoVerify(), used while PhoneWindow tears down a DecorView) that call the
        // private base field directly. Keep that field on the host's application transport so
        // those helpers remain total; all public Guest identity/storage APIs below are projected
        // here and getBaseContext() still returns the finite Guest-only boundary.
        super(host.getApplicationContext());
        this.hostServiceContext = host.getApplicationContext();
        // The Guest APK has an independent Resources table, while AndroidX still requires a
        // non-null framework Theme during Activity.onCreate. Apply the virtual component's
        // manifest theme to a Theme created by Guest Resources; reusing the Host Theme would
        // make Host resource IDs (drawables, colors, styles) visible to Guest code.
        this.frameworkTheme = createFrameworkTheme(resources, spec);
        this.packageManager = java.util.Objects.requireNonNull(packageManager, "packageManager");
        this.spec = spec;
        this.classLoader = classLoader;
        this.resources = resources;
        this.assets = assets;
        this.deviceProtected = deviceProtected;
        this.sharedState = sharedState;
        this.unwrapBoundary = new GuestContextUnwrapBoundary(this);
        android.util.Log.i("CS_GUEST_STORAGE", "bootstrap isolated=" + spec.isolatedProcess
                + " dataFd=" + (spec.dataRootDescriptor == null ? -1 : spec.dataRootDescriptor.getFd())
                + " dataRoot=" + spec.dataRoot);
        this.capabilityBackedStorage = spec.isolatedProcess && spec.dataRootDescriptor != null;
        this.instanceRoot = capabilityBackedStorage
                ? spec.dataRootFile() : ensureDirectory(spec.dataRootFile());
        this.dataRoot = ensureDirectory(new File(instanceRoot,
                deviceProtected ? "device_protected" : "data"));
        this.externalRoot = ensureDirectory(new File(instanceRoot, "external"));
        this.storageNames = new GuestStorageNameCodec(instanceRoot, capabilityBackedStorage);
        this.applicationInfo = GuestApplicationInfoFactory.create(spec, dataRoot.getAbsolutePath(),
                applicationMetadata, appComponentFactory, parsedApplicationInfo);
        // ContentResolver captures its Context attribution source at construction time. Reuse
        // Android's concrete ApplicationContentResolver implementation with this Guest Context;
        // an anonymous ContentResolver cannot implement API32's hidden provider lifecycle hooks.
        this.contentResolver = createGuestContentResolver();
        this.dynamicReceivers = sharedState.dynamicReceivers;
        this.mainThread = sharedState.mainThread;
        this.capabilityGate = sharedState.capabilityGate;
        this.webViewProviderServices = new GuestWebViewProviderServiceBridge(hostServiceContext);
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
    void configureWebViewProvider(String providerPackage) {
        webViewProviderServices.configure(providerPackage);
    }
    void closeWebViewProviderServices() {
        webViewProviderServices.close();
    }
    void beginComponentTeardown() {
        componentRouter.beginTeardown();
    }
    synchronized void closeComponentServices() {
        componentRouter.close();
        for (GuestPackageContext packageContext : packageContexts.values()) {
            packageContext.closeResources();
        }
        packageContexts.clear();
    }

    void installServiceFrameworkBridge(GuestActivityThreadServiceBridge bridge) {
        serviceFrameworkBridge = bridge;
        // The Service transport belongs to the Guest process, while Android can derive multiple
        // Context views for one Activity/Application. Keep the process-owned bridge in shared
        // state so a derived Context cannot silently fall back to the manual Service map.
        sharedState.serviceFrameworkBridge = bridge;
    }

    GuestActivityThreadServiceBridge serviceFrameworkBridge() {
        GuestActivityThreadServiceBridge local = serviceFrameworkBridge;
        return local == null ? sharedState.serviceFrameworkBridge : local;
    }

    /** Prevents ordinary Guest code from unwrapping this Context into the host Context. */
    /**
     * Return a finite Guest-only unwrap boundary. Returning this object here used to satisfy
     * the no-Host-leak rule but violates the ContextWrapper contract for libraries that walk
     * wrappers (Qigsaw's split loader did exactly that and looped forever). The boundary is not
     * backed by the Host and terminates at null after one hop.
     */
    @Override public Context getBaseContext() { return unwrapBoundary; }

    /**
     * Returns the host-only context used by internal RuntimeBroker transport.
     *
     * <p>Guest application code still observes this object as its Context and cannot bind host
     * components through {@link #bindService(Intent, ServiceConnection, int)}. Runtime-owned
     * routing, window identity repair and PendingIntent delivery may read the host transport
     * context; guest identity remains in the request bundle.</p>
     */
    public Context hostServiceContext() { return hostServiceContext; }

    @Override public String getPackageName() { return spec.packageName; }
    @Override public String getOpPackageName() { return spec.packageName; }
    /**
     * Project the API 31+ framework attribution source into the active Guest identity. The
     * Android 12 device image exposes AttributionSource constructors as hidden APIs (and has no
     * public Builder), so construction stays reflective and is guarded by the platform version.
     * This source is also what AppOpsManager and ContentResolver use to create their outbound
     * attribution chains.
    */
    // The static compile stubs intentionally omit this API31+ hidden Context method. Keep the
    // source-compatible method without @Override so both the stub compiler and API32 runtime
    // build use the same Guest attribution projection.
    public android.content.AttributionSource getAttributionSource() {
        return GuestAttributionSourceBridge.create(spec.virtualUid, spec.packageName);
    }
    @Override public Context getApplicationContext() {
        return sharedState.application == null ? this : sharedState.application;
    }
    @Override public ClassLoader getClassLoader() { return classLoader; }

    /**
     * LoadedApk wraps the process ClassLoader after BoundApplication identity is published.
     * The base Guest loader stays as the factory's input; this publishes the factory result.
     */
    void installProcessClassLoader(ClassLoader processLoader) {
        if (processLoader == null) throw new IllegalArgumentException("process ClassLoader is required");
        this.classLoader = processLoader;
        sharedState.mainThread.installFrameworkClassLoader(processLoader);
        GuestNativeBindingDiagnostic.recordLoader("process.install", processLoader);
    }
    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return assets; }
    @Override public Resources.Theme getTheme() { return frameworkTheme; }
    @Override public void setTheme(int resid) { frameworkTheme.applyStyle(resid, true); }
    @Override public android.os.Looper getMainLooper() { return hostServiceContext.getMainLooper(); }
    @Override public String getSystemServiceName(Class<?> serviceClass) {
        String hostServiceName = hostServiceContext.getSystemServiceName(serviceClass);
        if (hostServiceName != null) return hostServiceName;
        if (serviceClass != null && "android.view.accessibility.CaptioningManager"
                .equals(serviceClass.getName())) return "captioning";
        if (serviceClass != null && "android.view.accessibility.AccessibilityManager"
                .equals(serviceClass.getName())) return "accessibility";
        if (serviceClass != null && "android.view.inputmethod.InputMethodManager"
                .equals(serviceClass.getName())) return "input_method";
        // Radio-less Host images may omit the TelephonyManager class-to-name registration even
        // though the Guest has an explicit virtual telephony profile and Binder boundary.
        if (serviceClass != null && "android.telephony.TelephonyManager"
                .equals(serviceClass.getName())) return Context.TELEPHONY_SERVICE;
        if (serviceClass != null && "android.telephony.SubscriptionManager"
                .equals(serviceClass.getName())) return Context.TELEPHONY_SUBSCRIPTION_SERVICE;
        if (serviceClass != null && "android.os.BatteryManager"
                .equals(serviceClass.getName())) return "batterymanager";
        if (serviceClass != null && "android.telecom.TelecomManager"
                .equals(serviceClass.getName())) return "telecom";
        return null;
    }
    @Override public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(applicationInfo); }
    @Override public PackageManager getPackageManager() {
        sharedState.systemServices.requireHookAvailable("packageManager", "getPackageManager");
        return packageManager;
    }
    @Override public int checkPermission(String permission, int pid, int uid) {
        return capabilityGate.checkPermission(permission);
    }
    @Override public int checkCallingPermission(String permission) {
        return capabilityGate.checkPermission(permission);
    }
    @Override public int checkCallingOrSelfPermission(String permission) {
        return capabilityGate.checkPermission(permission);
    }
    @Override public int checkSelfPermission(String permission) {
        return capabilityGate.checkPermission(permission);
    }
    @Override public void enforcePermission(String permission, int pid, int uid, String message) {
        if (checkPermission(permission, pid, uid) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message == null ? "Permission denied: " + permission : message);
        }
    }
    @Override public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
        componentRouter.grantUriPermission(toPackage, uri, modeFlags);
    }
    @Override public void revokeUriPermission(Uri uri, int modeFlags) {
        componentRouter.revokeUriPermission(uri, modeFlags);
    }
    @Override public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        return componentRouter.checkUriPermission(uri, pid, uid, modeFlags);
    }
    @Override public Object getSystemService(String name) {
        // LayoutInflater is framework-owned but context-bound. It must be cloned into the
        // Guest context so Activity/Fragment UI code does not receive null or a Host inflater.
        if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
            LayoutInflater cached = guestLayoutInflater;
            if (cached != null) return cached;
            synchronized (layoutInflaterLock) {
                cached = guestLayoutInflater;
                if (cached == null) {
                    cached = LayoutInflater.from(hostServiceContext).cloneInContext(this);
                    guestLayoutInflater = cached;
                }
                return cached;
            }
        }
        if (!sharedState.systemServices.isKnownService(name)) return null;
        // Android service lookup is discovery, not permission grant. Camera and
        // location managers must be obtainable before a runtime permission is
        // granted; the actual operation proxies enforce permission/AppOps when
        // the service is used. This matches Framework/VA/NBB semantics and
        // prevents Chromium's CameraAvailabilityObserver from crashing during
        // application startup.
        sharedState.systemServices.requireAvailable(name);
        Object override = com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry
                .get(this, name);
        if (override != null) return override;
        return hostServiceContext.getSystemService(name);
    }
    @Override public ContentResolver getContentResolver() {
        return contentResolver;
    }

    private ContentResolver createGuestContentResolver() {
        if (Build.VERSION.SDK_INT < 31) return hostServiceContext.getContentResolver();
        try {
            Object hostResolver = hostServiceContext.getContentResolver();
            Class<?> resolverType = Class.forName(
                    "android.app.ContextImpl$ApplicationContentResolver");
            Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Field mainThreadField = findField(resolverType, "mMainThread");
            mainThreadField.setAccessible(true);
            Object mainThread = mainThreadField.get(hostResolver);
            java.lang.reflect.Constructor<?> constructor = resolverType.getDeclaredConstructor(
                    Context.class, activityThreadType);
            constructor.setAccessible(true);
            return (ContentResolver) constructor.newInstance(this, mainThread);
        } catch (Throwable error) {
            if (error instanceof ThreadDeath) throw (ThreadDeath) error;
            // Host-side framework self-tests run without android.jar's concrete ContextImpl.
            // Keep those tests on the existing resolver; API31+ device images contain the
            // concrete class and therefore continue through the Guest-context constructor path.
            if (error instanceof ClassNotFoundException) {
                return hostServiceContext.getContentResolver();
            }
            throw new IllegalStateException("GUEST_CONTENT_RESOLVER_CONSTRUCTION_FAILED", error);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name)
            throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    @Override public Executor getMainExecutor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return hostServiceContext.getMainExecutor();
        }
        Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    @Override public void startActivity(Intent intent) { componentRouter.startActivity(intent, null); }
    @Override public void startActivity(Intent intent, Bundle options) {
        componentRouter.startActivity(intent, options);
    }
    void startActivityFromActivity(Intent intent, Bundle options, int callerTaskId) {
        componentRouter.startActivity(intent, options, callerTaskId);
    }
    Bundle startActivityFromFrameworkActivity(Intent intent, Bundle options, int callerTaskId) {
        return componentRouter.startActivityFromFrameworkActivity(intent, options, callerTaskId, -1);
    }
    Bundle startActivityFromFrameworkActivity(Intent intent, Bundle options, int callerTaskId,
                                              int requestCode) {
        return componentRouter.startActivityFromFrameworkActivity(
                intent, options, callerTaskId, requestCode);
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
        if (webViewProviderServices.bind(service, connection, flags, null)) return true;
        return componentRouter.bindService(service, connection, flags, null);
    }
    @Override public boolean bindService(Intent service, int flags, Executor executor,
            ServiceConnection connection) {
        if (webViewProviderServices.bind(service, connection, flags, executor)) return true;
        return componentRouter.bindService(service, connection, flags, executor);
    }
    @Override public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection connection) {
        if (webViewProviderServices.bindIsolated(service, flags, instanceName, executor, connection)) {
            return true;
        }
        return super.bindIsolatedService(service, flags, instanceName, executor, connection);
    }
    @Override public void unbindService(ServiceConnection connection) {
        if (webViewProviderServices.unbind(connection)) return;
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
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, errorHandler);
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
        SharedPreferences created = capabilityBackedStorage
                ? new SandboxSharedPreferences(GuestStorageBroker.preferences(spec, file.getName(),
                        deviceProtected))
                : new SandboxSharedPreferences(file);
        preferences.put(name, created);
        return created;
    }
    @Override public synchronized boolean deleteSharedPreferences(String name) {
        SharedPreferences cached = preferences.remove(name);
        File file = storageNames.resolve(ensureDirectory(new File(dataRoot, "shared_prefs")),
                "shared_preferences", name, "", ".cspf", ".tmp");
        if (capabilityBackedStorage) {
            return cached instanceof SandboxSharedPreferences
                    ? ((SandboxSharedPreferences) cached).deleteStoredFile()
                    : GuestStorageBroker.preferences(spec, file.getName(), deviceProtected).delete();
        }
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
        File targetFile = storageNames.resolve(targetParent,
                "shared_preferences", name, "", ".cspf", ".tmp");
        SandboxSharedPreferences sourceCached = source.cachedPreferences(name);
        SandboxSharedPreferences targetCached = cachedPreferences(name);
        if (capabilityBackedStorage) {
            boolean moved = withPreferenceLocks(sourceCached, targetCached, () ->
                    GuestStorageBroker.move(spec, sourceFile.getName(), source.deviceProtected,
                            targetFile.getName(), deviceProtected));
            if (moved) {
                if (sourceCached != null) sourceCached.invalidateAfterMove();
                if (targetCached != null && targetCached != sourceCached) {
                    targetCached.invalidateAfterMove();
                }
                synchronized (source) { source.preferences.remove(name); }
                synchronized (this) { preferences.remove(name); }
            }
            return moved;
        }
        if (!sourceFile.isFile()) return false;
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
        // Android's ContextImpl exposes valid app directories using the stable
        // "app_<name>" contract.  A number of production runtimes (notably UC/U4)
        // derive sibling paths from that value and will not find a codec-generated
        // name such as app_v2_dTRzZGs.  Keep the collision-safe codec for names that
        // cannot be represented as one Android directory component, but preserve the
        // framework-visible spelling for the normal component-name subset.
        if (isFrameworkDirectoryName(name)) {
            return ensureDirectory(new File(dataRoot, "app_" + name));
        }
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
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new PackageManager.NameNotFoundException("Guest package name is empty");
        }
        String targetPackage = packageName.trim();
        if (spec.packageName.equals(targetPackage)) return this;
        VirtualPackageProjectionSnapshot projection = null;
        for (VirtualPackageProjectionSnapshot candidate : spec.packageUniverse) {
            if (candidate != null && targetPackage.equals(candidate.packageState().packageName())) {
                projection = candidate;
                break;
            }
        }
        if (projection == null) {
            throw new PackageManager.NameNotFoundException(
                    "Guest package is not visible: " + targetPackage);
        }
        VirtualPackageStateSnapshot targetState = projection.packageState();
        if (!targetState.enabled()) {
            throw new PackageManager.NameNotFoundException(
                    "Guest package is disabled: " + targetPackage);
        }
        // ContextImpl distinguishes package code/resource views, and CAS additionally binds the
        // view to the immutable target revision. A package-name-only key would retain an old
        // peer ClassLoader after a split/base upgrade if the process survives long enough to
        // receive a new projection.
        String cacheKey = packageContextCacheKey(targetPackage, targetState.apkSha256(), flags);
        synchronized (this) {
            GuestPackageContext cached = packageContexts.get(cacheKey);
            if (cached != null) return cached;
        }
        try {
            GuestResourceLoader.LoadedResources loaded =
                    componentRouter.openPackageResources(targetPackage);
            ApplicationInfo targetInfo = packageContextApplicationInfo(projection);
            if (loaded.manifestMetadata != null) {
                Bundle metadata = loaded.manifestMetadata.application();
                if (metadata != null) targetInfo.metaData = metadata;
            }
            // Match ContextImpl's CONTEXT_INCLUDE_CODE contract without crossing the sandbox
            // boundary. The Broker has already checked visibility and supplied FD capabilities;
            // the target loader consumes DEX bytes from those FDs, never a host APK pathname.
            // It is a resource/code view, not a new UID/session: Binder, system-service and
            // storage calls remain owned by this Guest session.
            ClassLoader targetLoader = packageContextClassLoader(flags, targetPackage,
                    targetState, loaded);
            GuestPackageContext created = new GuestPackageContext(this, targetPackage,
                    targetState, targetInfo, loaded, targetLoader);
            synchronized (this) {
                GuestPackageContext existing = packageContexts.get(cacheKey);
                if (existing != null) {
                    created.closeResources();
                    return existing;
                }
                packageContexts.put(cacheKey, created);
            }
            return created;
        } catch (Exception error) {
            PackageManager.NameNotFoundException failure =
                    new PackageManager.NameNotFoundException(
                            "Guest package resources unavailable: " + targetPackage);
            failure.initCause(error);
            throw failure;
        }
    }

    static String packageContextCacheKey(String targetPackage, String packageRevision, int flags) {
        // ContextImpl keeps code-bearing and resources-only package contexts distinct. A caller
        // may legitimately request resources first and CONTEXT_INCLUDE_CODE later; collapsing
        // those views would permanently return the weaker ClassLoader contract.
        return targetPackage + "\nrevision=" + (packageRevision == null ? "" : packageRevision)
                + ((flags & 0x00000001) != 0 ? "\ninclude-code" : "\nresources-only");
    }

    private ClassLoader packageContextClassLoader(
            int flags, String targetPackage, VirtualPackageStateSnapshot targetState,
            GuestResourceLoader.LoadedResources loaded) throws Exception {
        if ((flags & 0x00000001) == 0) return classLoader;
        List<java.nio.ByteBuffer> buffers = GuestDexBufferLoader.load(
                loaded.baseDescriptor(), loaded.splitDescriptors());
        // No native search path is inherited from the owner. A target package's Java code can be
        // inspected/used through the FD-backed loader, while native loading remains explicit and
        // capability-gated instead of accidentally resolving the owner's .so directory.
        return new GuestClassLoader(buffers, "", classLoader, targetPackage,
                declaredGuestClasses(targetState));
    }

    private static List<String> declaredGuestClasses(VirtualPackageStateSnapshot state) {
        ArrayList<String> classes = new ArrayList<>();
        if (state.applicationClass() != null && !state.applicationClass().trim().isEmpty()) {
            classes.add(state.applicationClass().trim());
        }
        for (com.warden.controlledsandbox.contract.VirtualComponentSnapshot component
                : state.components()) {
            if (component != null && component.className() != null
                    && !component.className().trim().isEmpty()) {
                classes.add(component.className().trim());
            }
        }
        return List.copyOf(classes);
    }

    @Override public Context createContextForSplit(String splitName)
            throws PackageManager.NameNotFoundException {
        if (splitName != null && !splitName.trim().isEmpty() && spec.hasSplit(splitName)) return this;
        throw new PackageManager.NameNotFoundException("Guest split is not installed: " + splitName);
    }

    @Override public Context createConfigurationContext(Configuration overrideConfiguration) {
        if (overrideConfiguration == null) throw new IllegalArgumentException("overrideConfiguration is required");
        // ContextImpl creates a new Resources view for an override configuration. Returning the
        // current GuestContext here made locale/orientation/night-mode changes silently mutate
        // the process-wide resource view (and, in practice, left libraries such as AppCompat
        // observing the host configuration). Keep the Guest AssetManager and identity, but give
        // the derived context an independent Configuration object and Resources instance.
        Configuration configuration = copyConfiguration(overrideConfiguration);
        Resources configuredResources = new Resources(assets, resources.getDisplayMetrics(),
                configuration);
        return new GuestContext(hostServiceContext, spec, classLoader, configuredResources, assets,
                packageManager, deviceProtected, sharedState, applicationInfo.metaData,
                GuestApplicationInfoFactory.readComponentFactory(applicationInfo), applicationInfo);
    }

    @Override public Context createDisplayContext(android.view.Display display) {
        if (display == null) throw new IllegalArgumentException("display is required");
        // ContextWrapper's default implementation delegates to its host base context. That
        // would manufacture a host-owned display context and leak the host package identity
        // through a guest Activity. Keep the guest resources, package manager, and identity on
        // the derived context just as createConfigurationContext does.
        return new GuestContext(hostServiceContext, spec, classLoader, resources, assets,
                packageManager, deviceProtected, sharedState, applicationInfo.metaData,
                GuestApplicationInfoFactory.readComponentFactory(applicationInfo), applicationInfo);
    }

    public Context createCredentialProtectedStorageContext() {
        return deviceProtected ? storageContext(false) : this;
    }

    @Override public Context createDeviceProtectedStorageContext() {
        return deviceProtected ? this : storageContext(true);
    }

    @Override public boolean isDeviceProtectedStorage() { return deviceProtected; }

    private GuestContext storageContext(boolean targetDeviceProtected) {
        return new GuestContext(hostServiceContext, spec, classLoader, resources, assets,
                packageManager, targetDeviceProtected, sharedState, applicationInfo.metaData,
                GuestApplicationInfoFactory.readComponentFactory(applicationInfo), applicationInfo);
    }

    private ApplicationInfo packageContextApplicationInfo(
            VirtualPackageProjectionSnapshot projection) {
        VirtualPackageStateSnapshot state = projection.packageState();
        // Peer ApplicationInfo is built only from the virtual package authority. The optional
        // parsedApplicationInfo transport field can contain host parser defaults and is not a
        // source for identity, paths, flags, or ABI metadata.
        ApplicationInfo info = state.applicationInfo();
        if (info == null) info = new ApplicationInfo();
        info.packageName = state.packageName();
        info.uid = projection.virtualUid();
        info.processName = normalizePackageProcess(state.packageName(),
                info.processName);
        info.sourceDir = "/data/app/" + state.packageName() + "/base.apk";
        info.publicSourceDir = info.sourceDir;
        String[] splitNames = state.splitNames().toArray(new String[0]);
        setOptionalField(info, "splitNames", splitNames);
        String[] splitPaths = new String[splitNames.length];
        for (int index = 0; index < splitNames.length; index++) {
            splitPaths[index] = "/data/app/" + state.packageName() + "/split-"
                    + safeSyntheticName(splitNames[index]) + ".apk";
        }
        info.splitSourceDirs = splitPaths.length == 0 ? null : splitPaths;
        info.splitPublicSourceDirs = info.splitSourceDirs == null ? null : splitPaths.clone();
        info.dataDir = "/data/user/" + spec.virtualUserId + "/" + state.packageName();
        info.nativeLibraryDir = "";
        info.enabled = state.enabled();
        if (state.applicationClass() != null && !state.applicationClass().isEmpty()) {
            info.name = state.applicationClass();
            info.className = state.applicationClass();
        }
        return info;
    }

    private static void setOptionalField(ApplicationInfo info, String name, Object value) {
        try {
            java.lang.reflect.Field field = ApplicationInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(info, value);
        } catch (NoSuchFieldException ignored) {
            // API 32 compile stubs omit newer package projection fields.
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private static String safeSyntheticName(String value) {
        if (value == null || value.trim().isEmpty()) return "split";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String normalizePackageProcess(String packageName, String processName) {
        String value = processName == null ? "" : processName.trim();
        if (value.isEmpty()) return packageName;
        return value.startsWith(":") ? packageName + value : value;
    }

    private static Configuration copyConfiguration(Configuration source) {
        try {
            java.lang.reflect.Constructor<Configuration> constructor =
                    Configuration.class.getDeclaredConstructor(Configuration.class);
            constructor.setAccessible(true);
            return constructor.newInstance(source);
        } catch (ReflectiveOperationException unavailable) {
            // The host's API contract has always accepted a Configuration object. The reduced
            // static test stubs do not expose the copy constructor; production Android does. Do
            // not reject a valid configuration solely because the compile-time stub is small.
            return source;
        }
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
        volatile GuestActivityThreadServiceBridge serviceFrameworkBridge;
        SharedState(GuestCapabilityGate capabilityGate, ClassLoader classLoader) {
            this.capabilityGate = capabilityGate;
            this.mainThread = new GuestMainThreadDispatcher(classLoader);
        }
    }

    private File ensureDirectory(File file) {
        if (capabilityBackedStorage) return file;
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) {
            throw new IllegalStateException("Cannot create directory " + file);
        }
        return file;
    }

    /**
     * The exact Android getDir() projection is safe only for a single, ordinary
     * directory component.  Reject separators, dot components, control/space
     * characters and the names that can be confused with the codec namespace.
     */
    private static boolean isFrameworkDirectoryName(String value) {
        if (value == null || value.isEmpty() || value.equals(".") || value.equals("..")
                || value.length() > 240 || value.startsWith("v2_") || value.startsWith("v2h_")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '_' && character != '-' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static Resources.Theme createFrameworkTheme(Resources resources, GuestPackageSpec spec) {
        Resources.Theme theme = resources.newTheme();
        int themeResId = 0;
        for (com.warden.controlledsandbox.contract.VirtualComponentSnapshot component
                : spec.packageState.components()) {
            if ("ACTIVITY".equals(component.type())
                    && component.className().equals(spec.componentClass)) {
                themeResId = component.themeResId();
                break;
            }
        }
        if (themeResId != 0) theme.applyStyle(themeResId, true);
        else theme.applyStyle(android.R.style.Theme_DeviceDefault_Light_NoActionBar, true);
        return theme;
    }

}
