package com.warden.controlledsandbox;

import android.content.Context;
import android.os.IBinder;
import java.io.File;
import java.nio.file.Files;

/** Direct executable ownership regression for management and runtime-permission sessions. */
public final class PackageSessionDirectOwnershipSelfTest {
    private PackageSessionDirectOwnershipSelfTest() { }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("package-session-direct-owner").toFile();
        PackageServiceDependencies dependencies = dependencies(new TestContext(root), root);
        try {
            LiveBinder managementToken = new LiveBinder();
            PackageManagementSession management = new PackageManagementSession(
                    dependencies, 0, 1, managementToken);
            management.binderDied();
            expectSecurity(management::loadCatalog,
                    "dead PackageManagementSession remained callable");
            require(managementToken.unlinkCount == 1,
                    "PackageManagementSession did not release its death registration");

            LiveBinder runtimeToken = new LiveBinder();
            PackageRuntimePermissionSession runtime = new PackageRuntimePermissionSession(
                    dependencies, 0, 1, runtimeToken);
            runtime.binderDied();
            expectSecurity(() -> runtime.requestRuntimePermission(
                            "guest.pkg", 0, "android.permission.CAMERA", 7,
                            "runtime-session", 1L),
                    "dead PackageRuntimePermissionSession remained callable");
            require(runtimeToken.unlinkCount == 1,
                    "PackageRuntimePermissionSession did not release its death registration");
        } finally {
            dependencies.close();
        }
        System.out.println("PASS Package session direct ownership self-test");
    }

    private static PackageServiceDependencies dependencies(Context context, File root) {
        return new PackageServiceDependencies(root,
                new SandboxPackageLifecycle(context),
                new PackageCallerVerifier(context),
                new VirtualPackageStateBuilder(context),
                new HostPermissionStateResolver(context),
                new VirtualSystemServiceStore(root),
                new VirtualDeviceServiceStore(root),
                new VirtualInteractionStore(root),
                new VirtualNetworkServiceStore(root),
                new ApplicationEnvironmentStore(root),
                new VirtualCompatibilityStore(root),
                new VirtualPolicyServicesStore(root),
                new VirtualMediaCommunicationStore(root),
                new VirtualPeripheralServicesStore(root),
                new VirtualPrivilegedServicesStore(root));
    }

    private static final class TestContext extends Context {
        private final File root;
        TestContext(File root) { this.root = root; }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return root; }
        @Override public File getDataDir() { return root; }
        @Override public File getCacheDir() { return new File(root, "cache"); }
        @Override public File getCodeCacheDir() { return new File(root, "code-cache"); }
        @Override public File getNoBackupFilesDir() { return new File(root, "no-backup"); }
    }

    private static final class LiveBinder implements IBinder {
        private DeathRecipient recipient;
        private int unlinkCount;
        @Override public boolean isBinderAlive() { return true; }
        @Override public void linkToDeath(DeathRecipient value, int flags) { recipient = value; }
        @Override public boolean unlinkToDeath(DeathRecipient value, int flags) {
            if (recipient != null && recipient != value) return false;
            recipient = null;
            unlinkCount++;
            return true;
        }
    }

    private static void expectSecurity(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (SecurityException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
