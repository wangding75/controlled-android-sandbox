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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.util.concurrent.Executor;

/**
 * A finite, Host-free ContextWrapper base exposed only through GuestContext.getBaseContext().
 * It preserves the common Guest identity surface while deliberately ending wrapper traversal at
 * null. This prevents generic ContextWrapper walkers from re-entering GuestContext forever.
 */
final class GuestContextUnwrapBoundary extends GuestHostOperationDenyContext {
    private final GuestContext owner;

    GuestContextUnwrapBoundary(GuestContext owner) {
        super(null);
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    @Override public Context getBaseContext() { return null; }
    @Override public String getPackageName() { return owner.getPackageName(); }
    @Override public String getOpPackageName() { return owner.getOpPackageName(); }
    @Override public Context getApplicationContext() { return owner.getApplicationContext(); }
    @Override public ClassLoader getClassLoader() { return owner.getClassLoader(); }
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
    @Override public String[] databaseList() { return owner.databaseList(); }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return owner.getSharedPreferences(name, mode);
    }
    @Override public boolean deleteSharedPreferences(String name) {
        return owner.deleteSharedPreferences(name);
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
    public Context createCredentialProtectedStorageContext() {
        return owner.createCredentialProtectedStorageContext();
    }
    @Override public Context createDeviceProtectedStorageContext() {
        return owner.createDeviceProtectedStorageContext();
    }
    @Override public boolean isDeviceProtectedStorage() { return owner.isDeviceProtectedStorage(); }
}
