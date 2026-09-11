package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * A finite, Host-free terminal Context exposed only through GuestContext.getBaseContext().
 * It is intentionally not a ContextWrapper: Chromium's split compatibility path walks every
 * ContextWrapper and expects the terminal to carry a mutable mClassLoader field. Keeping that
 * field Guest-local lets the platform-compatible repair finish without exposing a Host Context.
 */
final class GuestContextUnwrapBoundary extends Context {
    private final GuestContext owner;
    @SuppressWarnings("unused") // Chromium locates this exact terminal field reflectively.
    private ClassLoader mClassLoader;

    GuestContextUnwrapBoundary(GuestContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
        this.mClassLoader = owner.getClassLoader();
    }

    @Override public String getPackageName() { return owner.getPackageName(); }
    @Override public String getOpPackageName() { return owner.getOpPackageName(); }
    @Override public Context getApplicationContext() { return owner.getApplicationContext(); }
    @Override public ClassLoader getClassLoader() { return mClassLoader; }
    @Override public Resources getResources() { return owner.getResources(); }
    @Override public AssetManager getAssets() { return owner.getAssets(); }
    @Override public Resources.Theme getTheme() { return owner.getTheme(); }
    @Override public void setTheme(int resid) { owner.setTheme(resid); }
    @Override public Looper getMainLooper() { return owner.getMainLooper(); }
    @Override public String getSystemServiceName(Class<?> serviceClass) {
        return owner.getSystemServiceName(serviceClass);
    }
    @Override public ApplicationInfo getApplicationInfo() { return owner.getApplicationInfo(); }
    @Override public PackageManager getPackageManager() { return owner.getPackageManager(); }
    @Override public int checkPermission(String permission, int pid, int uid) {
        return owner.checkPermission(permission, pid, uid);
    }
    @Override public int checkCallingPermission(String permission) {
        return owner.checkCallingPermission(permission);
    }
    @Override public int checkCallingOrSelfPermission(String permission) {
        return owner.checkCallingOrSelfPermission(permission);
    }
    @Override public int checkSelfPermission(String permission) {
        return owner.checkSelfPermission(permission);
    }
    @Override public void enforcePermission(String permission, int pid, int uid, String message) {
        owner.enforcePermission(permission, pid, uid, message);
    }
    @Override public void enforceCallingPermission(String permission, String message) {
        throw new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:enforceCallingPermission");
    }
    @Override public void enforceCallingOrSelfPermission(String permission, String message) {
        throw new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:enforceCallingOrSelfPermission");
    }
    @Override public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {
        owner.grantUriPermission(toPackage, uri, modeFlags);
    }
    @Override public void revokeUriPermission(Uri uri, int modeFlags) {
        owner.revokeUriPermission(uri, modeFlags);
    }
    @Override public void revokeUriPermission(String targetPackage, Uri uri, int modeFlags) {
        owner.revokeUriPermission(targetPackage, uri, modeFlags);
    }
    @Override public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        return owner.checkUriPermission(uri, pid, uid, modeFlags);
    }
    @Override public int checkCallingUriPermission(Uri uri, int modeFlags) {
        throw deniedUriPermission();
    }
    @Override public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        throw deniedUriPermission();
    }
    @Override public int checkUriPermission(Uri uri, String readPermission,
            String writePermission, int pid, int uid, int modeFlags) { throw deniedUriPermission(); }
    @Override public void enforceUriPermission(Uri uri, int pid, int uid,
            int modeFlags, String message) { throw deniedUriPermission(); }
    @Override public void enforceCallingUriPermission(Uri uri, int modeFlags,
            String message) { throw deniedUriPermission(); }
    @Override public void enforceCallingOrSelfUriPermission(Uri uri, int modeFlags,
            String message) { throw deniedUriPermission(); }
    @Override public void enforceUriPermission(Uri uri, String readPermission,
            String writePermission, int pid, int uid, int modeFlags, String message) {
        throw deniedUriPermission();
    }
    @Override public Object getSystemService(String name) { return owner.getSystemService(name); }
    @Override public ContentResolver getContentResolver() { return owner.getContentResolver(); }
    @Override public Executor getMainExecutor() { return owner.getMainExecutor(); }
    @Override public void startActivity(Intent intent) { owner.startActivity(intent); }
    @Override public void startActivity(Intent intent, Bundle options) {
        owner.startActivity(intent, options);
    }
    @Override public void startActivities(Intent[] intents) { owner.startActivities(intents); }
    @Override public void startActivities(Intent[] intents, Bundle options) {
        owner.startActivities(intents, options);
    }
    @Override public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        owner.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags);
    }
    @Override public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags, Bundle options)
            throws IntentSender.SendIntentException {
        owner.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options);
    }
    public boolean startInstrumentation(ComponentName className,
            String profileFile, Bundle arguments) {
        throw new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:startInstrumentation");
    }
    public void setWallpaper(Bitmap bitmap) throws IOException { denyWallpaper(); }
    public void setWallpaper(java.io.InputStream data) throws IOException { denyWallpaper(); }
    public void clearWallpaper() throws IOException { denyWallpaper(); }
    public Drawable getWallpaper() { throw deniedWallpaper(); }
    public Drawable peekWallpaper() { throw deniedWallpaper(); }
    public int getWallpaperDesiredMinimumWidth() { throw deniedWallpaper(); }
    public int getWallpaperDesiredMinimumHeight() { throw deniedWallpaper(); }
    @Override public ComponentName startService(Intent service) { return owner.startService(service); }
    @Override public ComponentName startForegroundService(Intent service) {
        return owner.startForegroundService(service);
    }
    @Override public boolean stopService(Intent service) { return owner.stopService(service); }
    @Override public boolean bindService(Intent service, ServiceConnection connection, int flags) {
        return owner.bindService(service, connection, flags);
    }
    @Override public boolean bindService(Intent service, int flags, Executor executor,
            ServiceConnection connection) {
        return owner.bindService(service, flags, executor, connection);
    }
    @Override public boolean bindIsolatedService(Intent service, int flags, String instanceName,
            Executor executor, ServiceConnection connection) {
        return owner.bindIsolatedService(service, flags, instanceName, executor, connection);
    }
    @Override public boolean bindServiceAsUser(Intent service, ServiceConnection connection,
            int flags, UserHandle user) {
        return owner.bindServiceAsUser(service, connection, flags, user);
    }
    @Override public void unbindService(ServiceConnection connection) {
        owner.unbindService(connection);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return owner.registerReceiver(receiver, filter);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            int flags) {
        return owner.registerReceiver(receiver, filter, flags);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String permission, Handler scheduler) {
        return owner.registerReceiver(receiver, filter, permission, scheduler);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String permission, Handler scheduler, int flags) {
        return owner.registerReceiver(receiver, filter, permission, scheduler, flags);
    }
    @Override public void unregisterReceiver(BroadcastReceiver receiver) {
        owner.unregisterReceiver(receiver);
    }
    @Override public void sendBroadcast(Intent intent) { owner.sendBroadcast(intent); }
    @Override public void sendBroadcast(Intent intent, String permission) {
        owner.sendBroadcast(intent, permission);
    }
    @Override public void sendBroadcast(Intent intent, String permission, Bundle options) {
        owner.sendBroadcast(intent, permission, options);
    }
    @Override public void sendOrderedBroadcast(Intent intent, String permission) {
        owner.sendOrderedBroadcast(intent, permission);
    }
    @Override public void sendOrderedBroadcast(Intent intent, String permission,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        owner.sendOrderedBroadcast(intent, permission, resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }
    @Override public void sendOrderedBroadcast(Intent intent, String permission, Bundle options,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        owner.sendOrderedBroadcast(intent, permission, options, resultReceiver, scheduler,
                initialCode, initialData, initialExtras);
    }
    @Override public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        denyUserBroadcast();
    }
    public void sendBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission) { denyUserBroadcast(); }
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, Bundle initialExtras) { denyUserBroadcast(); }
    @Override public void sendStickyBroadcast(Intent intent) { denyStickyBroadcast(); }
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        denyStickyBroadcast();
    }
    @Override public void sendStickyOrderedBroadcast(Intent intent,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) { denyStickyBroadcast(); }
    public void sendStickyOrderedBroadcastAsUser(Intent intent, UserHandle user,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) { denyStickyBroadcast(); }
    @Override public void removeStickyBroadcast(Intent intent) { denyStickyBroadcast(); }
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        denyStickyBroadcast();
    }
    @Override public File getDataDir() { return owner.getDataDir(); }
    @Override public File getFilesDir() { return owner.getFilesDir(); }
    @Override public File getCacheDir() { return owner.getCacheDir(); }
    @Override public File getCodeCacheDir() { return owner.getCodeCacheDir(); }
    @Override public File getNoBackupFilesDir() { return owner.getNoBackupFilesDir(); }
    @Override public File getDatabasePath(String name) { return owner.getDatabasePath(name); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
            SQLiteDatabase.CursorFactory factory) {
        return owner.openOrCreateDatabase(name, mode, factory);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
            SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
        return owner.openOrCreateDatabase(name, mode, factory, errorHandler);
    }
    @Override public boolean deleteDatabase(String name) { return owner.deleteDatabase(name); }
    @Override public boolean moveDatabaseFrom(Context sourceContext, String name) {
        return owner.moveDatabaseFrom(sourceContext, name);
    }
    @Override public String[] databaseList() { return owner.databaseList(); }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return owner.getSharedPreferences(name, mode);
    }
    @Override public boolean deleteSharedPreferences(String name) {
        return owner.deleteSharedPreferences(name);
    }
    @Override public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return owner.moveSharedPreferencesFrom(sourceContext, name);
    }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return owner.openFileInput(name);
    }
    @Override public FileOutputStream openFileOutput(String name, int mode)
            throws FileNotFoundException {
        return owner.openFileOutput(name, mode);
    }
    @Override public File getFileStreamPath(String name) { return owner.getFileStreamPath(name); }
    @Override public boolean deleteFile(String name) { return owner.deleteFile(name); }
    @Override public String[] fileList() { return owner.fileList(); }
    @Override public File getDir(String name, int mode) { return owner.getDir(name, mode); }
    @Override public File getExternalFilesDir(String type) { return owner.getExternalFilesDir(type); }
    @Override public File[] getExternalFilesDirs(String type) { return owner.getExternalFilesDirs(type); }
    @Override public File getExternalCacheDir() { return owner.getExternalCacheDir(); }
    @Override public File[] getExternalCacheDirs() { return owner.getExternalCacheDirs(); }
    @Override public File getObbDir() { return owner.getObbDir(); }
    @Override public File[] getObbDirs() { return owner.getObbDirs(); }
    @Override public File[] getExternalMediaDirs() { return owner.getExternalMediaDirs(); }
    @Override public String getPackageCodePath() { return owner.getPackageCodePath(); }
    @Override public String getPackageResourcePath() { return owner.getPackageResourcePath(); }
    @Override public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        return owner.createPackageContext(packageName, flags);
    }
    @Override public Context createContextForSplit(String splitName)
            throws PackageManager.NameNotFoundException {
        return owner.createContextForSplit(splitName);
    }
    @Override public Context createConfigurationContext(Configuration overrideConfiguration) {
        return owner.createConfigurationContext(overrideConfiguration);
    }
    @Override public Context createDisplayContext(android.view.Display display) {
        return owner.createDisplayContext(display);
    }
    public Context createCredentialProtectedStorageContext() {
        return owner.createCredentialProtectedStorageContext();
    }
    @Override public Context createDeviceProtectedStorageContext() {
        return owner.createDeviceProtectedStorageContext();
    }
    @Override public boolean isDeviceProtectedStorage() { return owner.isDeviceProtectedStorage(); }

    private static SecurityException deniedWallpaper() {
        return new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:wallpaper");
    }
    private static void denyWallpaper() { throw deniedWallpaper(); }
    private static void denyStickyBroadcast() {
        throw new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:stickyBroadcast");
    }
    private static void denyUserBroadcast() {
        throw new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:userBroadcast");
    }
    private static SecurityException deniedUriPermission() {
        return new SecurityException("GUEST_CONTEXT_HOST_OPERATION_DENIED:uriPermission");
    }
}
