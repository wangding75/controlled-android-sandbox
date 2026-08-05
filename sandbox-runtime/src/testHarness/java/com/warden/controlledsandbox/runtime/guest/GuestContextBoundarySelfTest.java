package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.packagemanager.PackageManagerHook;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.Set;

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
            request.putString(RuntimeKeys.APK_SHA256, "a".repeat(64));
            request.putLong(RuntimeKeys.APK_VERSION_CODE, 1L);
            request.putString(RuntimeKeys.PACKAGE_REVISION, "v1:sha256:" + "a".repeat(64));
            request.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, new File(root, "lib").getAbsolutePath());
            request.putString(RuntimeKeys.NATIVE_ABI, "x86_64");
            request.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, true);
            request.putString(RuntimeKeys.NATIVE_GUEST_TRUST,
                    InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED);
            request.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, "BEST_EFFORT_COMPATIBILITY");
            request.putString(RuntimeKeys.DATA_ROOT, new File(root, "instance").getAbsolutePath());
            request.putParcelable(RuntimeKeys.PACKAGE_STATE, new VirtualPackageStateSnapshot(
                    "com.example.guest", 3, "Guest", "1", 1L, "b".repeat(64),
                    "a".repeat(64), "com.example.guest.MainActivity", "", true,
                    java.util.List.of(), java.util.List.of(), java.util.List.of()));
            boolean untrustedNativeDenied = false;
            try { new GuestPackageSpec(request); }
            catch (SecurityException expected) {
                untrustedNativeDenied = String.valueOf(expected.getMessage())
                        .contains("UNTRUSTED_NATIVE_GUEST_DENIED");
            }
            require(untrustedNativeDenied, "Guest spec rejects untrusted native payload");
            request.putString(RuntimeKeys.NATIVE_GUEST_TRUST,
                    InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED);
            GuestPackageSpec spec = new GuestPackageSpec(request);
            FakePackageManager processPackageManager = new FakePackageManager();
            Context host = new StablePackageContext(processPackageManager);
            Resources resources = new Resources(new AssetManager(),
                    new android.util.DisplayMetrics(), new Configuration());
            GuestContext context = new GuestContext(host, spec,
                    GuestContextBoundarySelfTest.class.getClassLoader(), resources,
                    resources.getAssets(), processPackageManager);

            require(context.getBaseContext() == context, "base Context must not expose host");
            require(context.getApplicationContext() == context, "application Context before bootstrap");
            expectVirtualRoutingFailure(() -> context.bindService(new android.content.Intent(),
                            new android.content.ServiceConnection() {
                                @Override public void onServiceConnected(
                                        android.content.ComponentName name, android.os.IBinder binder) { }
                                @Override public void onServiceDisconnected(
                                        android.content.ComponentName name) { }
                            }, Context.BIND_AUTO_CREATE), "NO_GUEST_SERVICE_MATCH", "bindService");
            expectVirtualRoutingFailure(() -> context.bindService(new android.content.Intent(),
                            Context.BIND_AUTO_CREATE, Runnable::run,
                            new android.content.ServiceConnection() {
                                @Override public void onServiceConnected(
                                        android.content.ComponentName name, android.os.IBinder binder) { }
                                @Override public void onServiceDisconnected(
                                        android.content.ComponentName name) { }
                            }), "NO_GUEST_SERVICE_MATCH", "bindService executor overload");
            require(context.getContentResolver() != null,
                    "ContentResolver is exposed through the framework interception boundary");
            boolean packageManagerNotReady = false;
            try { context.getPackageManager(); }
            catch (SecurityException expected) {
                packageManagerNotReady = String.valueOf(expected.getMessage()).contains(
                        "GUEST_FRAMEWORK_API_NOT_READY:getPackageManager");
            }
            require(packageManagerNotReady, "PackageManager unavailable before hook readiness");
            expectVirtualRoutingFailure(() -> context.startActivity(new android.content.Intent()),
                    "ActivityNotFoundException", "startActivity");
            GuestIdentity identity = new GuestIdentity(spec.packageName, spec.virtualUid,
                    context.getApplicationInfo(), Set.of(), host.getPackageName(), 1000);
            Object originalPackageTransport = processPackageManager.mPM;
            try (PackageManagerHook packageHook = PackageManagerHook.install(
                    processPackageManager, identity)) {
                require(Proxy.isProxyClass(processPackageManager.mPM.getClass()),
                        "PackageManager transport proxied before Guest exposure");
                context.sealSystemServices(java.util.Map.of(
                        "packageManager", true,
                        "clipboard", false));
                require(context.getPackageManager() == processPackageManager,
                        "Guest exposes the exact proxied process PackageManager");
            }
            require(processPackageManager.mPM == originalPackageTransport,
                    "PackageManager transport restored during rollback");
            boolean missingHookDenied = false;
            try { context.getSystemService("clipboard"); }
            catch (SecurityException expected) {
                missingHookDenied = String.valueOf(expected.getMessage()).contains(
                        "GUEST_SYSTEM_SERVICE_HOOK_UNAVAILABLE:clipboard:clipboard");
            }
            require(missingHookDenied, "missing system-service hook cannot fall back to Host manager");
            require(context.getSystemService("download") == null,
                    "unknown system service cannot fall back to Host manager");
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
            catch (SecurityException expected) {
                databaseMoveDenied = String.valueOf(expected.getMessage())
                        .contains(GuestStorageTransferCoordinator.IDENTITY_MISMATCH);
            }
            require(databaseMoveDenied, "host database move source fails closed");
            boolean preferencesMoveDenied = false;
            try { context.moveSharedPreferencesFrom(host, "guest"); }
            catch (SecurityException expected) {
                preferencesMoveDenied = String.valueOf(expected.getMessage())
                        .contains(GuestStorageTransferCoordinator.IDENTITY_MISMATCH);
            }
            require(preferencesMoveDenied, "host preferences move source fails closed");
            Context deviceContext = context.createDeviceProtectedStorageContext();
            require(deviceContext instanceof GuestContext
                            && deviceContext.isDeviceProtectedStorage(),
                    "device-protected Guest context is available");
            require(!deviceContext.getDataDir().getCanonicalFile().equals(
                            context.getDataDir().getCanonicalFile()),
                    "device-protected data root is distinct");

            ApplicationInfo first = context.getApplicationInfo();
            first.packageName = "mutated";
            require(spec.packageName.equals(context.getApplicationInfo().packageName),
                    "ApplicationInfo returned defensively");
            System.out.println("PASS Guest Context storage and unwrap boundary self-test");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void expectVirtualRoutingFailure(Runnable action, String marker, String operation) {
        try {
            action.run();
            throw new AssertionError(operation + " unexpectedly succeeded");
        } catch (RuntimeException expected) {
            String value = expected.getClass().getSimpleName() + ":" + expected.getMessage();
            require(value.contains(marker), operation + " remains confined to virtual resolution");
            require(!value.contains("GUEST_CONTEXT_HOST_OPERATION_DENIED"),
                    operation + " no longer uses the legacy blanket denial");
        }
    }

    private static void expectDenied(Runnable action, String operation) {
        try {
            action.run();
            throw new AssertionError(operation + " exposed Host Context");
        } catch (SecurityException expected) {
            require(String.valueOf(expected.getMessage()).contains(
                    "GUEST_CONTEXT_HOST_OPERATION_DENIED:" + operation),
                    operation + " denial code");
        }
    }

    public interface FakePackageApi {
        String marker();
    }

    private static final class FakePackageService implements FakePackageApi {
        @Override public String marker() { return "platform"; }
    }

    private static final class FakePackageManager extends PackageManager {
        Object mPM = new FakePackageService();
    }

    private static final class StablePackageContext extends Context {
        private final PackageManager packages;

        StablePackageContext(PackageManager packages) {
            this.packages = packages;
        }

        @Override public PackageManager getPackageManager() { return packages; }
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
