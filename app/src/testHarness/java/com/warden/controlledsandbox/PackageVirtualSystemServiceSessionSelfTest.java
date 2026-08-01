package com.warden.controlledsandbox;

import android.content.Context;
import android.os.IBinder;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

/** Deterministic regression for client death delivered synchronously from linkToDeath. */
public final class PackageVirtualSystemServiceSessionSelfTest {
    private PackageVirtualSystemServiceSessionSelfTest() { }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("virtual-system-session-immediate-death").toFile();
        TestContext context = new TestContext(root);
        PackageServiceDependencies dependencies = dependencies(context, root);
        try {
            ImmediateDeathBinder clientToken = new ImmediateDeathBinder();
            PackageVirtualSystemServiceSession session = new PackageVirtualSystemServiceSession(
                    dependencies, 0, clientToken,
                    new VirtualSystemServiceStore.Scope("session.pkg", 2), 12002,
                    "session.pkg", 1L, "session-revision");
            dependencies.systemServices.reserveClientRegistration(session);
            require(!session.linkClientDeathAfterReservation(),
                    "session that died inside linkToDeath was published");
            require(!session.active(), "immediately dead session remained active");

            Field registryField = VirtualSystemServiceStore.class.getDeclaredField("clientRegistry");
            registryField.setAccessible(true);
            VirtualSystemServiceClientRegistry registry =
                    (VirtualSystemServiceClientRegistry) registryField.get(dependencies.systemServices);
            require(registry.snapshot().isEmpty(),
                    "immediately dead session leaked into virtual system-service registry");

            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope("replacement.pkg", 3);
            PackageVirtualSystemServiceSession existing = new PackageVirtualSystemServiceSession(
                    dependencies, 0, new LiveBinder(), scope, 12003,
                    "replacement.pkg", 2L, "replacement-revision");
            dependencies.systemServices.reserveClientRegistration(existing);
            require(existing.linkClientDeathAfterReservation(),
                    "live existing session failed death registration");
            dependencies.systemServices.commitClientRegistration(existing);

            PackageVirtualSystemServiceSession deadReplacement =
                    new PackageVirtualSystemServiceSession(dependencies, 0,
                            new ImmediateDeathBinder(), scope, 12003,
                            "replacement.pkg", 2L, "replacement-revision");
            dependencies.systemServices.reserveClientRegistration(deadReplacement);
            require(!deadReplacement.linkClientDeathAfterReservation(),
                    "dead replacement session was published");
            require(registry.snapshot().size() == 1
                            && registry.snapshot().contains(existing) && existing.active(),
                    "dead replacement removed the previous live session");
            existing.close();
        } finally {
            dependencies.close();
        }
        System.out.println("PASS Package virtual system-service session immediate-death self-test");
    }

    private static PackageServiceDependencies dependencies(Context context, File root) {
        return new PackageServiceDependencies(
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

        @Override public boolean isBinderAlive() { return true; }

        @Override public void linkToDeath(DeathRecipient value, int flags) {
            recipient = value;
        }

        @Override public boolean unlinkToDeath(DeathRecipient value, int flags) {
            if (recipient != value) return false;
            recipient = null;
            return true;
        }
    }

    private static final class ImmediateDeathBinder implements IBinder {
        private boolean alive = true;

        @Override public boolean isBinderAlive() { return alive; }

        @Override public void linkToDeath(DeathRecipient recipient, int flags) {
            alive = false;
            recipient.binderDied();
        }

        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
