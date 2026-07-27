package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.io.File;
import java.nio.file.Files;

public final class GuestContextBoundarySelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("guest-context-boundary").toFile();
        try {
            Bundle request = new Bundle();
            request.putInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
            request.putString(RuntimeKeys.SESSION_ID, "session-context");
            request.putLong(RuntimeKeys.GENERATION, 1L);
            request.putString(RuntimeKeys.PACKAGE_NAME, "com.example.guest");
            request.putInt(RuntimeKeys.VIRTUAL_USER_ID, 3);
            request.putInt(RuntimeKeys.VIRTUAL_UID, 190003);
            request.putInt(RuntimeKeys.PROCESS_SLOT, 1);
            request.putString(RuntimeKeys.PROCESS_NAME, "com.example.guest");
            request.putString(RuntimeKeys.APK_PATH, new File(root, "base.apk").getAbsolutePath());
            request.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, new File(root, "lib").getAbsolutePath());
            request.putString(RuntimeKeys.DATA_ROOT, new File(root, "instance").getAbsolutePath());
            GuestPackageSpec spec = new GuestPackageSpec(request);
            Context host = new Context();
            Resources resources = new Resources(new AssetManager(),
                    new android.util.DisplayMetrics(), new Configuration());
            GuestContext context = new GuestContext(host, spec,
                    GuestContextBoundarySelfTest.class.getClassLoader(), resources, resources.getAssets());

            require(context.getBaseContext() == context, "base Context must not expose host");
            require(context.getApplicationContext() == context, "application Context before bootstrap");
            require(context.getDataDir().getCanonicalPath().startsWith(spec.dataRootFile().getCanonicalPath()),
                    "data directory inside instance root");
            require(context.getNoBackupFilesDir().getCanonicalPath().startsWith(
                    context.getDataDir().getCanonicalPath()), "no-backup directory isolated");
            require(context.getDatabasePath("guest.db").getCanonicalPath().startsWith(
                    context.getDataDir().getCanonicalPath()), "database path isolated");
            context.openOrCreateDatabase("guest.db", Context.MODE_PRIVATE, null).close();
            require(context.databaseList().length == 1, "database list remains Guest-scoped");
            require(context.deleteDatabase("guest.db"), "Guest database deletion succeeds");
            require(context.getExternalFilesDir("Pictures").getCanonicalPath().startsWith(
                    spec.dataRootFile().getCanonicalPath()), "external files directory isolated");
            require(context.getExternalCacheDir().getCanonicalPath().startsWith(
                    spec.dataRootFile().getCanonicalPath()), "external cache directory isolated");
            require(context.getObbDir().getCanonicalPath().startsWith(
                    spec.dataRootFile().getCanonicalPath()), "OBB directory isolated");
            require(context.createCredentialProtectedStorageContext() == context,
                    "credential-protected context remains Guest context");
            require(context.createConfigurationContext(new Configuration()) == context,
                    "configuration context remains Guest context");
            require(context.createPackageContext(spec.packageName, 0) == context,
                    "own package context remains Guest context");
            boolean denied = false;
            try { context.createPackageContext("com.warden.controlledsandbox", 0); }
            catch (android.content.pm.PackageManager.NameNotFoundException expected) { denied = true; }
            require(denied, "host package context denied");
            boolean databaseMoveDenied = false;
            try { context.moveDatabaseFrom(host, "guest.db"); }
            catch (UnsupportedOperationException expected) { databaseMoveDenied = true; }
            require(databaseMoveDenied, "cross-Context database move fails closed");
            boolean preferencesMoveDenied = false;
            try { context.moveSharedPreferencesFrom(host, "guest"); }
            catch (UnsupportedOperationException expected) { preferencesMoveDenied = true; }
            require(preferencesMoveDenied, "cross-Context preferences move fails closed");
            boolean deviceProtectedDenied = false;
            try { context.createDeviceProtectedStorageContext(); }
            catch (UnsupportedOperationException expected) { deviceProtectedDenied = true; }
            require(deviceProtectedDenied, "unsupported device-protected context fails closed");

            ApplicationInfo first = context.getApplicationInfo();
            first.packageName = "mutated";
            require(spec.packageName.equals(context.getApplicationInfo().packageName),
                    "ApplicationInfo returned defensively");
            System.out.println("PASS Guest Context storage and unwrap boundary self-test");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }
}
