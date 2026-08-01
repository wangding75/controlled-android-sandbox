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

/** Direct collision, migration and restart regressions for Guest storage-name mapping. */
public final class GuestStorageNameCodecSelfTest {
    private GuestStorageNameCodecSelfTest() { }

    public static void main(String[] args) throws Exception {
        testCollisionFreeContextSurfaces();
        testLegacyMigrationAndAmbiguity();
        testLongNameRestartStability();
        System.out.println("PASS Guest storage name codec collision and migration self-test");
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
            require(Arrays.asList(context.fileList()).containsAll(
                    Arrays.asList("file a", "file?a")),
                    "fileList did not return logical names");

            File firstDatabase = context.getDatabasePath("db a");
            File secondDatabase = context.getDatabasePath("db?a");
            require(!firstDatabase.equals(secondDatabase),
                    "colliding database names mapped to one physical path");
            context.openOrCreateDatabase("db a", Context.MODE_PRIVATE, null).close();
            context.openOrCreateDatabase("db?a", Context.MODE_PRIVATE, null).close();
            require(Arrays.asList(context.databaseList()).containsAll(Arrays.asList("db a", "db?a")),
                    "databaseList did not return logical names");

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

    private static void testLegacyMigrationAndAmbiguity() throws Exception {
        File root = Files.createTempDirectory("guest-name-codec-legacy").toFile();
        try {
            GuestContext context = newContext(root);
            File data = context.getDataDir();

            File legacyFile = new File(new File(data, "files"), "file_a");
            write(legacyFile, "legacy-file");
            File migratedFile = context.getFileStreamPath("file?a");
            require(!legacyFile.exists() && migratedFile.isFile(), "legacy file was not migrated");
            require("legacy-file".equals(read(migratedFile)), "migrated file content changed");
            requireLegacyCollision(() -> context.getFileStreamPath("file a"));

            File legacyPreferencesFile = new File(new File(data, "shared_prefs"), "prefs_a.cspf");
            SandboxSharedPreferences legacyPreferences = new SandboxSharedPreferences(legacyPreferencesFile);
            require(legacyPreferences.edit().putString("owner", "legacy-preferences").commit(),
                    "legacy preferences fixture write failed");
            SharedPreferences migratedPreferences = context.getSharedPreferences(
                    "prefs?a", Context.MODE_PRIVATE);
            require("legacy-preferences".equals(migratedPreferences.getString("owner", null)),
                    "legacy preferences were not migrated");
            requireLegacyCollision(() -> context.getSharedPreferences("prefs a", Context.MODE_PRIVATE));

            File legacyDatabase = new File(new File(data, "databases"), "db_a");
            write(legacyDatabase, "legacy-db");
            File migratedDatabase = context.getDatabasePath("db?a");
            require(!legacyDatabase.exists() && migratedDatabase.isFile(),
                    "legacy database was not migrated");
            requireLegacyCollision(() -> context.getDatabasePath("db a"));

            File legacyDir = new File(data, "app_dir_a");
            require(legacyDir.mkdirs(), "legacy getDir fixture creation failed");
            write(new File(legacyDir, "marker"), "legacy-dir");
            File migratedDir = context.getDir("dir?a", Context.MODE_PRIVATE);
            require(new File(migratedDir, "marker").isFile(), "legacy getDir data was not migrated");
            requireLegacyCollision(() -> context.getDir("dir a", Context.MODE_PRIVATE));

            File externalRoot = context.getExternalFilesDir(null);
            File legacyExternal = new File(externalRoot, "Pictures_A");
            require(legacyExternal.mkdirs(), "legacy external fixture creation failed");
            write(new File(legacyExternal, "marker"), "legacy-external");
            File migratedExternal = context.getExternalFilesDir("Pictures?A");
            require(new File(migratedExternal, "marker").isFile(),
                    "legacy external directory was not migrated");
            requireLegacyCollision(() -> context.getExternalFilesDir("Pictures A"));

            GuestContext restarted = newContext(root);
            requireLegacyCollision(() -> restarted.getFileStreamPath("file a"));
            require("legacy-file".equals(read(restarted.getFileStreamPath("file?a"))),
                    "registry restart did not preserve the successful legacy claim");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void testLongNameRestartStability() throws Exception {
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

            GuestContext restarted = newContext(root);
            require(firstPath.getCanonicalFile().equals(
                            restarted.getFileStreamPath(firstName).getCanonicalFile()),
                    "long name mapping changed after restart");
            require("long-name-data".equals(read(restarted.getFileStreamPath(firstName))),
                    "long-name data was not readable after restart");
            require(Arrays.asList(restarted.fileList()).contains(firstName),
                    "long logical name was not restored by fileList");
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
        try {
            action.run();
            throw new AssertionError("expected " + GuestStorageNameCodec.LEGACY_COLLISION);
        } catch (IllegalStateException expected) {
            require(String.valueOf(expected.getMessage()).contains(
                            GuestStorageNameCodec.LEGACY_COLLISION),
                    "unexpected legacy collision failure: " + expected.getMessage());
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
