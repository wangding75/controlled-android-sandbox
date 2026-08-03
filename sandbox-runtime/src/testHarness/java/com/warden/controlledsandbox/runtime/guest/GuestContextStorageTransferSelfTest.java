package com.warden.controlledsandbox.runtime.guest;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/** Direct regression for credential/device storage contexts and cross-context moves. */
public final class GuestContextStorageTransferSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("guest-context-storage-transfer").toFile();
        try {
            GuestContext credential = newContext(root, "com.example.guest", 3);
            GuestContext device = (GuestContext) credential.createDeviceProtectedStorageContext();

            require(!credential.isDeviceProtectedStorage(), "credential context marked device protected");
            require(device.isDeviceProtectedStorage(), "device context not marked device protected");
            require(device.createDeviceProtectedStorageContext() == device,
                    "device context did not remain stable");
            Context credentialAgain = device.createCredentialProtectedStorageContext();
            require(!credentialAgain.isDeviceProtectedStorage(),
                    "credential context restored from device context has wrong domain");
            require(credential.getDataDir().getCanonicalPath().endsWith(File.separator + "data"),
                    "credential data directory is not instance/data");
            require(device.getDataDir().getCanonicalPath().endsWith(
                            File.separator + "device_protected"),
                    "device data directory is not instance/device_protected");
            require(!credential.getDataDir().getCanonicalFile().equals(
                            device.getDataDir().getCanonicalFile()),
                    "credential and device data directories overlap");
            require(device.getApplicationInfo().dataDir.equals(device.getDataDir().getAbsolutePath()),
                    "device ApplicationInfo dataDir is incorrect");
            require(credential.getExternalFilesDir("Pictures").getCanonicalFile().equals(
                            device.getExternalFilesDir("Pictures").getCanonicalFile()),
                    "external storage should not fork by protected-storage domain");
            Application application = new Application();
            credential.application(application);
            require(device.getApplicationContext() == application,
                    "storage contexts do not share Guest application identity");

            testSharedPreferencesMove(credential, device);
            testDatabaseMove(credential, device);
            testDestinationCollision(credential, device);
            testConcurrentMoveSingleWinner(credential);
            testPartialMoveRollback(root);
            testPostRenameUncertainMoveIsTracked(root);
            testPostRenameUncertainMainMoveReportsSuccess(root);
            testMissingSources(credential, device);
            testForeignContextDenied(root, credential, device);
            System.out.println("PASS Guest credential/device storage context and move self-test");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void testSharedPreferencesMove(GuestContext credential, GuestContext device) {
        SharedPreferences original = credential.getSharedPreferences("settings?user", Context.MODE_PRIVATE);
        SharedPreferences previousTarget = device.getSharedPreferences(
                "settings?user", Context.MODE_PRIVATE);
        require(original.edit().putString("owner", "credential").putInt("count", 7).commit(),
                "credential preferences write failed");
        require(device.moveSharedPreferencesFrom(credential, "settings?user"),
                "credential-to-device preferences move failed");
        requireMovedPreferences(original, "source preferences reference remained writable");
        requireMovedPreferences(previousTarget, "target preferences reference remained writable");
        SharedPreferences moved = device.getSharedPreferences("settings?user", Context.MODE_PRIVATE);
        require("credential".equals(moved.getString("owner", null)) && moved.getInt("count", 0) == 7,
                "device preferences did not preserve values");
        require(!credential.getSharedPreferences("settings?user", Context.MODE_PRIVATE)
                        .contains("owner"),
                "credential preferences still expose moved data");
        require(credential.moveSharedPreferencesFrom(device, "settings?user"),
                "device-to-credential preferences move failed");
        requireMovedPreferences(moved, "device preferences reference remained writable after reverse move");
        require("credential".equals(credential.getSharedPreferences(
                        "settings?user", Context.MODE_PRIVATE).getString("owner", null)),
                "preferences did not survive reverse move");
    }


    private static void requireMovedPreferences(SharedPreferences preferences, String message) {
        try {
            preferences.edit().putString("stale", "write").commit();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
            require(String.valueOf(expected.getMessage()).contains("SHARED_PREFERENCES_MOVED"),
                    "unexpected moved-preferences failure: " + expected.getMessage());
        }
    }

    private static void testDatabaseMove(GuestContext credential, GuestContext device)
            throws Exception {
        String name = "guest database?unicode-資料.db";
        File source = credential.getDatabasePath(name);
        write(source, "main");
        write(new File(source.getPath() + "-journal"), "journal");
        write(new File(source.getPath() + "-wal"), "wal");
        write(new File(source.getPath() + "-shm"), "shm");
        require(device.moveDatabaseFrom(credential, name),
                "credential-to-device database move failed");
        File target = device.getDatabasePath(name);
        require(!source.exists(), "database main file remains in credential storage");
        require("main".equals(read(target)), "database main file content changed");
        require("journal".equals(read(new File(target.getPath() + "-journal"))),
                "database journal was not moved");
        require("wal".equals(read(new File(target.getPath() + "-wal"))),
                "database WAL was not moved");
        require("shm".equals(read(new File(target.getPath() + "-shm"))),
                "database SHM was not moved");
        require(Arrays.asList(device.databaseList()).contains(name),
                "device databaseList omitted moved database");
        require(!Arrays.asList(credential.databaseList()).contains(name),
                "credential databaseList retained moved database");
        require(credential.moveDatabaseFrom(device, name),
                "device-to-credential database move failed");
        require("main".equals(read(credential.getDatabasePath(name))),
                "database did not survive reverse move");
    }

    private static void testDestinationCollision(GuestContext credential, GuestContext device)
            throws Exception {
        String name = "collision.db";
        File source = credential.getDatabasePath(name);
        File target = device.getDatabasePath(name);
        write(source, "source");
        write(target, "target");
        require(!device.moveDatabaseFrom(credential, name),
                "database move overwrote an existing destination");
        require("source".equals(read(source)), "source changed after rejected collision");
        require("target".equals(read(target)), "target changed after rejected collision");

        require(credential.getSharedPreferences("collision", Context.MODE_PRIVATE)
                        .edit().putString("value", "source").commit(),
                "source collision preferences write failed");
        require(device.getSharedPreferences("collision", Context.MODE_PRIVATE)
                        .edit().putString("value", "target").commit(),
                "target collision preferences write failed");
        require(!device.moveSharedPreferencesFrom(credential, "collision"),
                "preferences move overwrote an existing destination");
        require("source".equals(credential.getSharedPreferences(
                        "collision", Context.MODE_PRIVATE).getString("value", null)),
                "source preferences changed after rejected collision");
        require("target".equals(device.getSharedPreferences(
                        "collision", Context.MODE_PRIVATE).getString("value", null)),
                "target preferences changed after rejected collision");
    }



    private static void testConcurrentMoveSingleWinner(GuestContext credential) throws Exception {
        String name = "concurrent.db";
        write(credential.getDatabasePath(name), "single-winner");
        GuestContext firstTarget = (GuestContext) credential.createDeviceProtectedStorageContext();
        GuestContext secondTarget = (GuestContext) credential.createDeviceProtectedStorageContext();
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<Boolean> first = new FutureTask<>(() -> {
            start.await();
            return firstTarget.moveDatabaseFrom(credential, name);
        });
        FutureTask<Boolean> second = new FutureTask<>(() -> {
            start.await();
            return secondTarget.moveDatabaseFrom(credential, name);
        });
        Thread firstThread = new Thread(first, "guest-storage-move-first");
        Thread secondThread = new Thread(second, "guest-storage-move-second");
        firstThread.start();
        secondThread.start();
        start.countDown();
        boolean firstResult = first.get();
        boolean secondResult = second.get();
        require(firstResult != secondResult, "concurrent moves did not produce one winner");
        require("single-winner".equals(read(firstTarget.getDatabasePath(name))),
                "concurrent move lost database data");
    }

    private static void testPartialMoveRollback(File root) throws Exception {
        File instance = new File(root, "rollback-instance");
        File source = new File(instance, "data/databases/rollback.db");
        File target = new File(instance, "device_protected/databases/rollback.db");
        write(source, "main");
        write(new File(source.getPath() + "-wal"), "wal");
        AtomicInteger moves = new AtomicInteger();
        boolean failed = false;
        try {
            GuestStorageTransferCoordinator.moveForTest(instance, source, target, (from, to) -> {
                if (moves.incrementAndGet() == 2) {
                    throw new IllegalStateException(GuestStorageTransferCoordinator.MOVE_FAILED
                            + ":injected");
                }
                try {
                    File parent = to.getParentFile();
                    if (!parent.isDirectory()) parent.mkdirs();
                    Files.move(from.toPath(), to.toPath());
                    return DurableAtomicFile.CommitResult.confirmed();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }, "-wal");
        } catch (IllegalStateException expected) {
            failed = String.valueOf(expected.getMessage()).contains(
                    GuestStorageTransferCoordinator.MOVE_FAILED);
        }
        require(failed, "injected transfer failure was not reported");
        require("main".equals(read(source)), "main file changed during failed move");
        require("wal".equals(read(new File(source.getPath() + "-wal"))),
                "companion file was not rolled back");
        require(!target.exists() && !new File(target.getPath() + "-wal").exists(),
                "failed move left destination artifacts");
    }

    private static void testPostRenameUncertainMoveIsTracked(File root) throws Exception {
        File instance = new File(root, "post-rename-rollback-instance");
        File source = new File(instance, "data/databases/uncertain.db");
        File target = new File(instance, "device_protected/databases/uncertain.db");
        write(source, "main");
        write(new File(source.getPath() + "-wal"), "wal");
        AtomicInteger moves = new AtomicInteger();
        boolean failed = false;
        try {
            GuestStorageTransferCoordinator.moveForTest(instance, source, target, (from, to) -> {
                int move = moves.incrementAndGet();
                if (move == 2) {
                    throw new IllegalStateException(GuestStorageTransferCoordinator.MOVE_FAILED
                            + ":injected-after-uncertain-companion");
                }
                try {
                    File parent = to.getParentFile();
                    if (!parent.isDirectory()) parent.mkdirs();
                    Files.move(from.toPath(), to.toPath());
                    return move == 1
                            ? DurableAtomicFile.CommitResult.uncertain(
                                    new java.io.IOException(DurableAtomicFile.DIRECTORY_SYNC_FAILED))
                            : DurableAtomicFile.CommitResult.confirmed();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }, "-wal");
        } catch (IllegalStateException expected) {
            failed = String.valueOf(expected.getMessage()).contains(
                    GuestStorageTransferCoordinator.MOVE_FAILED);
        }
        require(failed, "failure after uncertain companion move was not reported");
        require("main".equals(read(source)), "main file changed after uncertain rollback");
        require("wal".equals(read(new File(source.getPath() + "-wal"))),
                "post-rename uncertain companion was omitted from rollback");
        require(!target.exists() && !new File(target.getPath() + "-wal").exists(),
                "rollback after uncertain move left destination artifacts");
    }

    private static void testPostRenameUncertainMainMoveReportsSuccess(File root) throws Exception {
        File instance = new File(root, "post-rename-success-instance");
        File source = new File(instance, "data/databases/uncertain-main.db");
        File target = new File(instance, "device_protected/databases/uncertain-main.db");
        write(source, "main");
        boolean moved = GuestStorageTransferCoordinator.moveForTest(
                instance, source, target, (from, to) -> {
                    try {
                        File parent = to.getParentFile();
                        if (!parent.isDirectory()) parent.mkdirs();
                        Files.move(from.toPath(), to.toPath());
                        return DurableAtomicFile.CommitResult.uncertain(
                                new java.io.IOException(DurableAtomicFile.DIRECTORY_SYNC_FAILED));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
        require(moved, "committed uncertain main move was reported as failure");
        require(!source.exists(), "source remained after committed uncertain main move");
        require("main".equals(read(target)),
                "target missing after committed uncertain main move");
    }

    private static void testMissingSources(GuestContext credential, GuestContext device) {
        require(!device.moveDatabaseFrom(credential, "missing.db"),
                "missing database move reported success");
        require(!device.moveSharedPreferencesFrom(credential, "missing-prefs"),
                "missing preferences move reported success");
    }

    private static void testForeignContextDenied(File root, GuestContext credential,
                                                  GuestContext device) {
        requireSecurity(() -> device.moveDatabaseFrom(new Context(), "guest.db"),
                "host database source was accepted");
        requireSecurity(() -> device.moveSharedPreferencesFrom(new Context(), "guest"),
                "host preferences source was accepted");
        GuestContext other = newContext(new File(root, "other"), "com.example.other", 4);
        requireSecurity(() -> device.moveDatabaseFrom(other, "guest.db"),
                "different Guest identity database source was accepted");
        requireSecurity(() -> credential.moveSharedPreferencesFrom(other, "guest"),
                "different Guest identity preferences source was accepted");
    }

    private static GuestContext newContext(File root, String packageName, int virtualUserId) {
        Bundle request = new Bundle();
        request.putInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.SESSION_ID, "session-storage-" + packageName);
        request.putLong(RuntimeKeys.GENERATION, 1L);
        request.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        request.putInt(RuntimeKeys.VIRTUAL_UID, 190000 + virtualUserId);
        request.putInt(RuntimeKeys.PROCESS_SLOT, 1);
        request.putString(RuntimeKeys.PROCESS_NAME, packageName);
        request.putString(RuntimeKeys.APK_PATH, new File(root, "base.apk").getAbsolutePath());
        request.putString(RuntimeKeys.APK_SHA256, "a".repeat(64));
        request.putLong(RuntimeKeys.APK_VERSION_CODE, 1L);
        request.putString(RuntimeKeys.PACKAGE_REVISION, "v1:sha256:" + "a".repeat(64));
        request.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, new File(root, "lib").getAbsolutePath());
        request.putString(RuntimeKeys.NATIVE_ABI, "x86_64");
        request.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, true);
        request.putString(RuntimeKeys.NATIVE_GUEST_TRUST,
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED);
        request.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, "BEST_EFFORT_COMPATIBILITY");
        request.putString(RuntimeKeys.DATA_ROOT, new File(root, "instance").getAbsolutePath());
        request.putParcelable(RuntimeKeys.PACKAGE_STATE, new VirtualPackageStateSnapshot(
                packageName, virtualUserId, "Guest", "1", 1L, "b".repeat(64),
                "a".repeat(64), packageName + ".MainActivity", "", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        GuestPackageSpec spec = new GuestPackageSpec(request);
        Context host = new Context();
        Resources resources = new Resources(new AssetManager(),
                new android.util.DisplayMetrics(), new Configuration());
        return new GuestContext(host, spec,
                GuestContextStorageTransferSelfTest.class.getClassLoader(),
                resources, resources.getAssets());
    }

    private static void requireSecurity(ThrowingRunnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (SecurityException expected) {
            require(String.valueOf(expected.getMessage()).contains(
                            GuestStorageTransferCoordinator.IDENTITY_MISMATCH),
                    "unexpected storage identity failure: " + expected.getMessage());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new AssertionError("cannot create " + parent);
        }
        Files.writeString(file.toPath(), value, StandardCharsets.UTF_8);
    }

    private static String read(File file) throws Exception {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
