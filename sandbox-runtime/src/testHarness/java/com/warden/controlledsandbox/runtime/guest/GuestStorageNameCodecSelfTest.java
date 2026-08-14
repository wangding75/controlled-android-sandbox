package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Direct collision, migration, concurrency and restart regressions for Guest storage names. */
public final class GuestStorageNameCodecSelfTest {
    private GuestStorageNameCodecSelfTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "worker".equals(args[0])) {
            runRegistryWorker(new File(args[1]), args[2], args[3]);
            return;
        }
        testCollisionFreeContextSurfaces();
        testLegacyMigrationRequiresProvableOwnership();
        testLegacyEnumerationMigratesUniqueAndRejectsAmbiguous();
        testIndependentCodecInstancesMergeRegistryUpdates();
        testIndependentOsProcessesMergeRegistryUpdates();
        testShortNamesDoNotGrowRegistry();
        testLongNameRestartStabilityAndClaimCleanup();
        testLegacyV2DirectoryRace();
        System.out.println("PASS Guest storage name codec transactional mapping self-test");
    }

    private static void testCollisionFreeContextSurfaces() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-surfaces").toFile();
        try {
            GuestContext context = newContext(root);

            SharedPreferences firstPrefs = context.getSharedPreferences("prefs a", Context.MODE_PRIVATE);
            SharedPreferences secondPrefs = context.getSharedPreferences("prefs?a", Context.MODE_PRIVATE);
            require(firstPrefs != secondPrefs, "colliding preference names returned one object");
            require(firstPrefs.edit().putString("owner", "first").commit(), "first preferences write failed");
            require(secondPrefs.getString("owner", null) == null,
                    "second preferences read data from the first logical name");
            require(secondPrefs.edit().putString("owner", "second").commit(), "second preferences write failed");
            require("first".equals(firstPrefs.getString("owner", null)),
                    "second preferences write overwrote the first logical name");

            write(context.getFileStreamPath("file a"), "first-file");
            write(context.getFileStreamPath("file?a"), "second-file");
            require(!context.getFileStreamPath("file a").equals(context.getFileStreamPath("file?a")),
                    "colliding file names mapped to one physical path");
            require("first-file".equals(read(context.getFileStreamPath("file a"))),
                    "first file content was replaced by collision");
            require("second-file".equals(read(context.getFileStreamPath("file?a"))),
                    "second file content was replaced by collision");
            require(Arrays.asList(context.fileList()).containsAll(List.of("file a", "file?a")),
                    "fileList did not decode reversible logical names");

            File firstDatabase = context.getDatabasePath("db a");
            File secondDatabase = context.getDatabasePath("db?a");
            require(!firstDatabase.equals(secondDatabase),
                    "colliding database names mapped to one physical path");
            context.openOrCreateDatabase("db a", Context.MODE_PRIVATE, null).close();
            context.openOrCreateDatabase("db?a", Context.MODE_PRIVATE, null).close();
            require(Arrays.asList(context.databaseList()).containsAll(List.of("db a", "db?a")),
                    "databaseList did not decode reversible logical names");

            require(!context.getDir("dir a", Context.MODE_PRIVATE).equals(
                            context.getDir("dir?a", Context.MODE_PRIVATE)),
                    "colliding getDir names mapped to one directory");
            require(!context.getExternalFilesDir("Pictures A").equals(
                            context.getExternalFilesDir("Pictures?A")),
                    "colliding external types mapped to one directory");

            File dot = context.getFileStreamPath(".");
            File dotDot = context.getFileStreamPath("..");
            File unicode = context.getFileStreamPath("資料");
            File emoji = context.getFileStreamPath("pet-🐾");
            File slash = context.getFileStreamPath("a/b");
            require(!dot.equals(dotDot) && !unicode.equals(emoji) && !slash.equals(unicode),
                    "special logical names were not encoded uniquely");
            require(dot.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()),
                    "dot logical name escaped the Guest files directory");
            require(dotDot.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()),
                    "dot-dot logical name escaped the Guest files directory");
            require(!context.getExternalFilesDir(" ").equals(context.getExternalFilesDir(null)),
                    "space-only external type collapsed into the null category root");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void testLegacyMigrationRequiresProvableOwnership() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-legacy").toFile();
        try {
            GuestContext context = newContext(root);
            File data = context.getDataDir();

            File uniqueLegacy = new File(new File(data, "files"), "legacy-file");
            write(uniqueLegacy, "legacy-file-data");
            File migrated = context.getFileStreamPath("legacy-file");
            require(!uniqueLegacy.exists() && migrated.isFile(), "unique legacy file was not migrated");
            require("legacy-file-data".equals(read(migrated)), "unique migration changed content");

            File ambiguousLegacy = new File(new File(data, "files"), "file_a");
            write(ambiguousLegacy, "ambiguous-owner");
            requireLegacyCollision(() -> context.getFileStreamPath("file?a"));
            requireLegacyCollision(() -> context.getFileStreamPath("file a"));
            require(ambiguousLegacy.isFile(), "ambiguous legacy data was assigned to first accessor");

            File uniquePreferencesFile = new File(new File(data, "shared_prefs"), "prefs-a.cspf");
            SandboxSharedPreferences legacyPreferences = new SandboxSharedPreferences(uniquePreferencesFile);
            require(legacyPreferences.edit().putString("owner", "legacy-preferences").commit(),
                    "legacy preferences fixture write failed");
            SharedPreferences migratedPreferences = context.getSharedPreferences(
                    "prefs-a", Context.MODE_PRIVATE);
            require("legacy-preferences".equals(migratedPreferences.getString("owner", null)),
                    "unique legacy preferences were not migrated");

            File uniqueDatabase = new File(new File(data, "databases"), "db-a");
            write(uniqueDatabase, "legacy-db");
            File migratedDatabase = context.getDatabasePath("db-a");
            require(!uniqueDatabase.exists() && migratedDatabase.isFile(),
                    "unique legacy database was not migrated");

            File uniqueDir = new File(data, "app_dir-a");
            require(uniqueDir.mkdirs(), "legacy getDir fixture creation failed");
            write(new File(uniqueDir, "marker"), "legacy-dir");
            File migratedDir = context.getDir("dir-a", Context.MODE_PRIVATE);
            require(new File(migratedDir, "marker").isFile(), "unique legacy getDir was not migrated");

            File externalRoot = context.getExternalFilesDir(null);
            File uniqueExternal = new File(externalRoot, "Pictures-A");
            require(uniqueExternal.mkdirs(), "legacy external fixture creation failed");
            write(new File(uniqueExternal, "marker"), "legacy-external");
            File migratedExternal = context.getExternalFilesDir("Pictures-A");
            require(new File(migratedExternal, "marker").isFile(),
                    "unique legacy external directory was not migrated");

            GuestContext restarted = newContext(root);
            require("legacy-file-data".equals(read(restarted.getFileStreamPath("legacy-file"))),
                    "unique migration was not stable across restart");
            requireLegacyCollision(() -> restarted.getFileStreamPath("file?a"));
        } finally {
            deleteRecursively(root);
        }
    }

    private static void testLegacyEnumerationMigratesUniqueAndRejectsAmbiguous() throws Exception {
        File uniqueRoot = Files.createTempDirectory("guest-name-codec-list-unique").toFile();
        try {
            GuestContext context = newContext(uniqueRoot);
            File files = context.getFilesDir();
            File databases = new File(context.getDataDir(), "databases");
            write(new File(files, "discoverable-file"), "file");
            write(new File(databases, "discoverable-db"), "db");
            require(Arrays.asList(context.fileList()).contains("discoverable-file"),
                    "fileList omitted a uniquely decodable legacy file");
            require(Arrays.asList(context.databaseList()).contains("discoverable-db"),
                    "databaseList omitted a uniquely decodable legacy database");
            require(!new File(files, "discoverable-file").exists(),
                    "fileList did not migrate the unique legacy file");
        } finally {
            deleteRecursively(uniqueRoot);
        }

        File ambiguousRoot = Files.createTempDirectory("guest-name-codec-list-ambiguous").toFile();
        try {
            GuestContext context = newContext(ambiguousRoot);
            write(new File(context.getFilesDir(), "unknown_owner"), "ambiguous");
            requireFailure(GuestStorageNameCodec.LEGACY_INDEX_AMBIGUOUS, context::fileList);
        } finally {
            deleteRecursively(ambiguousRoot);
        }
    }

    private static void testIndependentCodecInstancesMergeRegistryUpdates() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-concurrent").toFile();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            GuestContext first = newContext(root);
            GuestContext second = newContext(root);
            String alpha = "甲".repeat(500) + "-alpha";
            String beta = "乙".repeat(500) + "-beta";
            CountDownLatch start = new CountDownLatch(1);
            Future<?> firstWrite = executor.submit(() -> {
                await(start);
                writeUnchecked(first.getFileStreamPath(alpha), "alpha");
            });
            Future<?> secondWrite = executor.submit(() -> {
                await(start);
                writeUnchecked(second.getFileStreamPath(beta), "beta");
            });
            start.countDown();
            firstWrite.get(5, TimeUnit.SECONDS);
            secondWrite.get(5, TimeUnit.SECONDS);

            GuestContext restarted = newContext(root);
            require(Arrays.asList(restarted.fileList()).containsAll(List.of(alpha, beta)),
                    "independent codec instances lost a registry update");
            require("alpha".equals(read(restarted.getFileStreamPath(alpha))),
                    "alpha hash mapping was corrupted");
            require("beta".equals(read(restarted.getFileStreamPath(beta))),
                    "beta hash mapping was corrupted");
        } finally {
            executor.shutdownNow();
            deleteRecursively(root);
        }
    }


    private static void testIndependentOsProcessesMergeRegistryUpdates() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-processes").toFile();
        try {
            String alpha = "process-alpha-".repeat(80);
            String beta = "process-beta-".repeat(80);
            String java = new File(new File(System.getProperty("java.home"), "bin"), "java")
                    .getAbsolutePath();
            String classPath = System.getProperty("java.class.path");
            Process first = new ProcessBuilder(java, "-cp", classPath,
                    GuestStorageNameCodecSelfTest.class.getName(),
                    "worker", root.getAbsolutePath(), alpha, "alpha-process")
                    .redirectErrorStream(true).start();
            Process second = new ProcessBuilder(java, "-cp", classPath,
                    GuestStorageNameCodecSelfTest.class.getName(),
                    "worker", root.getAbsolutePath(), beta, "beta-process")
                    .redirectErrorStream(true).start();
            require(first.waitFor(10, TimeUnit.SECONDS), "first registry worker timed out");
            require(second.waitFor(10, TimeUnit.SECONDS), "second registry worker timed out");
            String firstOutput = new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String secondOutput = new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(first.exitValue() == 0, "first registry worker failed: " + firstOutput);
            require(second.exitValue() == 0, "second registry worker failed: " + secondOutput);

            GuestContext restarted = newContext(root);
            String[] listed = restarted.fileList();
            require(Arrays.asList(listed).containsAll(List.of(alpha, beta)),
                    "independent OS processes lost a registry update: listed="
                            + Arrays.toString(listed)
                            + " alphaLength=" + alpha.length()
                            + " betaLength=" + beta.length());
            require("alpha-process".equals(read(restarted.getFileStreamPath(alpha))),
                    "first OS-process mapping was corrupted");
            require("beta-process".equals(read(restarted.getFileStreamPath(beta))),
                    "second OS-process mapping was corrupted");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void runRegistryWorker(File root, String logicalName, String value)
            throws Exception {
        File instance = new File(root, "instance");
        File files = new File(new File(instance, "data"), "files");
        if (!files.isDirectory() && !files.mkdirs() && !files.isDirectory()) {
            throw new IllegalStateException("cannot create worker files directory");
        }
        GuestStorageNameCodec codec = new GuestStorageNameCodec(instance);
        File target = codec.resolve(files, "file", logicalName, "", "");
        write(target, value);
    }

    private static void testShortNamesDoNotGrowRegistry() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-short").toFile();
        try {
            GuestContext context = newContext(root);
            for (int index = 0; index < 2_000; index++) {
                context.getFileStreamPath("short-" + index);
            }
            File registry = new File(new File(root, "instance"), ".guest-storage-name-registry");
            require(!registry.exists() || registry.length() < 128,
                    "reversible short names caused persistent full-registry growth");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void testLongNameRestartStabilityAndClaimCleanup() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-long").toFile();
        try {
            String firstName = "長".repeat(400) + "-A";
            String secondName = "長".repeat(400) + "-B";
            GuestContext firstContext = newContext(root);
            File firstPath = firstContext.getFileStreamPath(firstName);
            File secondPath = firstContext.getFileStreamPath(secondName);
            require(!firstPath.equals(secondPath), "long names mapped to one hash path");
            require(firstPath.getName().startsWith("v2h_") && firstPath.getName().length() < 100,
                    "long name did not use bounded hash form");
            write(firstPath, "long-name-data");
            write(secondPath, "delete-me");

            GuestContext restarted = newContext(root);
            require(firstPath.getCanonicalFile().equals(
                            restarted.getFileStreamPath(firstName).getCanonicalFile()),
                    "long name mapping changed after restart");
            require("long-name-data".equals(read(restarted.getFileStreamPath(firstName))),
                    "long-name data was not readable after restart");
            require(Arrays.asList(restarted.fileList()).contains(firstName),
                    "long logical name was not restored by fileList");
            require(restarted.deleteFile(secondName), "long-name file deletion failed");
            require(!Arrays.asList(restarted.fileList()).contains(secondName),
                    "deleted long-name claim was not reclaimed");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void testLegacyV2DirectoryRace() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-dir-race").toFile();
        try {
            File instance = new File(root, "instance");
            File parent = new File(instance, "data");
            require(parent.mkdirs(), "race fixture parent");
            String logical = "minidumps" + "x".repeat(200);
            String legacyName = "app_" + logical;
            GuestStorageNameCodec probe = new GuestStorageNameCodec(instance);
            File v2 = probe.resolve(parent, "dir", logical, "app_", "");
            require(v2.getName().startsWith("app_v2h_"), "long dir name must use hashed v2 form");
            if (v2.isDirectory()) require(v2.delete(), "clear probe v2 dir");

            File legacy = new File(parent, legacyName);
            require(legacy.mkdirs() && v2.mkdirs(), "empty+empty fixtures");
            File converged = new GuestStorageNameCodec(instance).resolve(parent, "dir", logical, "app_", "");
            require(converged.getCanonicalFile().equals(v2.getCanonicalFile()), "empty+empty keeps v2");
            require(!legacy.exists(), "empty legacy is removed");
            require(v2.isDirectory(), "empty v2 remains");

            require(legacy.mkdirs(), "empty-legacy fixture");
            write(new File(v2, "keep"), "v2-data");
            File keepV2 = new GuestStorageNameCodec(instance).resolve(parent, "dir", logical, "app_", "");
            require("v2-data".equals(read(new File(keepV2, "keep"))), "empty legacy does not overwrite v2");
            require(!legacy.exists(), "empty legacy deleted when v2 has data");

            require(new File(v2, "keep").delete() && v2.delete(), "reset v2");
            require(legacy.mkdirs(), "populated-legacy fixture");
            write(new File(legacy, "marker"), "legacy-data");
            require(v2.mkdirs(), "empty v2 opposite race");
            File migrated = new GuestStorageNameCodec(instance).resolve(parent, "dir", logical, "app_", "");
            require("legacy-data".equals(read(new File(migrated, "marker"))),
                    "populated legacy migrates onto empty v2");
            require(!legacy.exists(), "populated legacy is consumed");

            write(new File(migrated, "v2-extra"), "v2-populated");
            require(legacy.mkdirs(), "both-populated legacy");
            write(new File(legacy, "legacy-extra"), "legacy-populated");
            boolean bothPopulated = false;
            try {
                new GuestStorageNameCodec(instance).resolve(parent, "dir", logical, "app_", "");
            } catch (IllegalStateException expected) {
                bothPopulated = String.valueOf(expected.getMessage()).contains("LEGACY_AND_V2_BOTH_EXIST");
            }
            require(bothPopulated, "both populated directories fail closed");
        } finally {
            deleteRecursively(root);
        }
    }

    private static GuestContext newContext(File root) {
        Bundle request = new Bundle();
        request.putInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.SESSION_ID, "session-name-codec");
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
                InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED);
        request.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, "BEST_EFFORT_COMPATIBILITY");
        request.putString(RuntimeKeys.DATA_ROOT, new File(root, "instance").getAbsolutePath());
        request.putParcelable(RuntimeKeys.PACKAGE_STATE, new VirtualPackageStateSnapshot(
                "com.example.guest", 3, "Guest", "1", 1L, "b".repeat(64),
                "a".repeat(64), "com.example.guest.MainActivity", "", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        GuestPackageSpec spec = new GuestPackageSpec(request);
        Context host = new Context();
        Resources resources = new Resources(new AssetManager(),
                new android.util.DisplayMetrics(), new Configuration());
        return new GuestContext(host, spec, GuestStorageNameCodecSelfTest.class.getClassLoader(),
                resources, resources.getAssets());
    }

    private static void requireLegacyCollision(ThrowingRunnable action) throws Exception {
        requireFailure(GuestStorageNameCodec.LEGACY_COLLISION, action);
    }

    private static void requireFailure(String code, ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected " + code);
        } catch (IllegalStateException expected) {
            require(String.valueOf(expected.getMessage()).contains(code),
                    "unexpected failure: " + expected.getMessage());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void writeUnchecked(File file, String value) {
        try {
            write(file, value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new AssertionError("cannot create fixture directory " + parent);
        }
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
