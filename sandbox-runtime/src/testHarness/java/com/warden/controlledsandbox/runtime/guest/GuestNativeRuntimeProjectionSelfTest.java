package com.warden.controlledsandbox.runtime.guest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Host-side contract test for the app-owned U4 native runtime projection. */
public final class GuestNativeRuntimeProjectionSelfTest {
    private GuestNativeRuntimeProjectionSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cas-native-runtime-projection-");
        try {
            Path data = Files.createDirectories(root.resolve("data"));
            Path packaged = Files.createDirectories(root.resolve("packaged-lib"));
            Path revision = Files.createDirectories(data.resolve("app_u4sdk/dlibs/_916307690"));
            Path abi = Files.createDirectories(revision.resolve("lib/arm64-v8a"));
            Files.write(revision.resolve("core.jar"), new byte[]{'d', 'e', 'x', '\n', 1, 2, 3, 4});
            Files.write(revision.resolve(".ver_info"), "7.1.8.1".getBytes("UTF-8"));
            writeArm64Elf(abi.resolve("libwebviewuc.so"));
            Files.write(abi.resolve("libjsi.so"), new byte[]{4});

            String packagedPath = packaged.toFile().getCanonicalPath();
            String projected = GuestNativeRuntimeProjection.select(data.toFile(), "arm64-v8a",
                    false, packagedPath);
            require(projected.equals(abi.toFile().getCanonicalPath()),
                    "complete dynamic core was not selected");
            String searchPath = GuestNativeRuntimeProjection.searchPath(data.toFile(),
                    "arm64-v8a", false, packagedPath);
            require(searchPath.startsWith(projected + File.pathSeparator),
                    "dynamic core is not first in native search path");
            require(searchPath.endsWith(File.pathSeparator + packagedPath),
                    "packaged native fallback was dropped");
            String baseDexPath = root.resolve("base.apk").toFile().getCanonicalPath();
            String dexPath = GuestNativeRuntimeProjection.prependCoreDexPath(data.toFile(),
                    "arm64-v8a", false, packagedPath, baseDexPath);
            require(dexPath.equals(baseDexPath), "raw core dex was incorrectly sent to ZIP loader");
            ByteBuffer rawDex = GuestNativeRuntimeProjection.mapCoreDex(data.toFile(),
                    "arm64-v8a", false, packagedPath);
            require(rawDex != null && rawDex.isDirect() && rawDex.remaining() > 0,
                    "raw core dex was not mapped as a direct buffer");
            String overlayPath = GuestNativeRuntimeProjection.materializeRawDexOverlay(data.toFile(),
                    "arm64-v8a", false, packagedPath);
            require(!overlayPath.isEmpty(), "raw core dex overlay was not materialized");
            require(overlayPath.contains(".cas-core-overlays"),
                    "core dex overlay escaped the persistent CAS overlay store");
            try (ZipFile overlay = new ZipFile(overlayPath)) {
                ZipEntry entry = overlay.getEntry("classes.dex");
                require(entry != null && entry.getMethod() == ZipEntry.STORED
                        && entry.getSize() == 8L, "core dex overlay entry is invalid");
            }
            Files.delete(revision.resolve("core.jar"));
            require(GuestNativeRuntimeProjection.select(data.toFile(), "arm64-v8a", false,
                    packagedPath).equals(abi.toFile().getCanonicalPath()),
                    "persisted overlay did not preserve the selected native revision");
            String recoveredDexPath = GuestNativeRuntimeProjection.prependCoreDexPath(
                    data.toFile(), "arm64-v8a", false, packagedPath, baseDexPath);
            require(recoveredDexPath.contains("guest-core-overlay.apk"),
                    "persisted overlay was not selected after source cleanup");

            require(GuestNativeRuntimeProjection.select(data.toFile(), "x86_64", false,
                    packagedPath).equals(packagedPath), "ABI mismatch did not fall back");
            require(GuestNativeRuntimeProjection.select(data.toFile(), "arm64-v8a", true,
                    packagedPath).equals(packagedPath), "isolated process escaped projection");
            Files.delete(abi.resolve("libwebviewuc.so"));
            require(GuestNativeRuntimeProjection.select(data.toFile(), "arm64-v8a", false,
                    packagedPath).equals(packagedPath), "incomplete deployment did not fall back");
            require(GuestNativeRuntimeProjection.prependCoreDexPath(data.toFile(), "arm64-v8a",
                    false, packagedPath, baseDexPath).equals(baseDexPath),
                    "incomplete deployment still projected core dex");

            System.out.println("PASS Guest native runtime projection transactional selection self-test");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void writeArm64Elf(Path path) throws IOException {
        byte[] header = new byte[4096];
        header[0] = 0x7f;
        header[1] = 'E';
        header[2] = 'L';
        header[3] = 'F';
        header[4] = 2;
        header[5] = 1;
        header[6] = 1;
        header[16] = 3;
        header[18] = (byte) 183;
        Files.write(path, header);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
