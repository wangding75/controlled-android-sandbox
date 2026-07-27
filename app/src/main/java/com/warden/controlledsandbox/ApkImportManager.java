package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.domain.packageinfo.PackageUpgradePolicy;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ApkImportManager {
    private static final long MAX_APK_BYTES = 1536L * 1024 * 1024;
    private static final long MAX_NATIVE_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private final Context context;

    ApkImportManager(Context context) { this.context = context.getApplicationContext(); }

    SandboxRecord importApk(Uri uri) throws Exception {
        File temporary = createStagingFile();
        try {
            String sha = copyAndHash(uri, temporary);
            return finishImport(temporary, sha);
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }
    }

    SandboxRecord importApkFile(File source) throws Exception {
        if (source == null || !source.isFile()) throw new IllegalArgumentException("Source APK does not exist");
        File temporary = createStagingFile();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_APK_BYTES) throw new IllegalArgumentException("APK exceeds 1.5 GiB limit");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            return finishImport(temporary, toHex(digest.digest()));
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }
    }

    private File createStagingFile() throws Exception {
        File stagingRoot = new File(context.getFilesDir(), "staging");
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) throw new IllegalStateException("Cannot create staging directory");
        return File.createTempFile("import-", ".apk", stagingRoot);
    }

    private SandboxRecord finishImport(File temporary, String sha) throws Exception {
        File transactionDir = null;
        try {
            ManifestModel manifest;
            try (ZipFile zip = new ZipFile(temporary)) {
                ZipEntry entry = zip.getEntry("AndroidManifest.xml");
                if (entry == null) throw new IllegalArgumentException("APK does not contain AndroidManifest.xml");
                try (InputStream input = zip.getInputStream(entry)) { manifest = new BinaryXmlManifestParser().parse(input); }
            }
            int archiveFlags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_META_DATA | (Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(temporary.getAbsolutePath(), archiveFlags);
            if (info == null) throw new IllegalArgumentException("PackageManager rejected the APK archive");
            String packageName = manifest.packageName();
            if (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid package name");
            if (!packageName.equals(info.packageName)) throw new IllegalArgumentException("Manifest and PackageManager package names differ");
            long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            String signatureSha256 = signingDigest(info);
            SandboxRecord previousRecord = existingRecord(packageName);
            new PackageUpgradePolicy().validate(previousRecord == null ? 0 : previousRecord.versionCode,
                    previousRecord == null ? "" : previousRecord.signatureSha256, versionCode, signatureSha256);

            File packagesRoot = new File(context.getFilesDir(), "packages");
            if (!packagesRoot.isDirectory() && !packagesRoot.mkdirs() && !packagesRoot.isDirectory()) {
                throw new IllegalStateException("Cannot create package root");
            }
            transactionDir = new File(packagesRoot, ".install-" + System.nanoTime());
            if (!transactionDir.mkdirs()) throw new IllegalStateException("Cannot create install transaction");
            File stagedApk = new File(transactionDir, "base.apk");
            moveFile(temporary, stagedApk);
            File stagedNativeDir = new File(transactionDir, "lib");
            String selectedAbi = extractNativeLibraries(stagedApk, stagedNativeDir);

            ApplicationInfo applicationInfo = info.applicationInfo;
            applicationInfo.sourceDir = stagedApk.getAbsolutePath();
            applicationInfo.publicSourceDir = stagedApk.getAbsolutePath();
            CharSequence loadedLabel = context.getPackageManager().getApplicationLabel(applicationInfo);
            String label = loadedLabel == null || loadedLabel.toString().trim().isEmpty() ? packageName : loadedLabel.toString();
            String version = info.versionName == null ? "" : info.versionName;
            ManifestModel.Component activity = componentByClass(manifest.activities(), manifest.launcherActivity());
            ManifestModel.Component service = firstEnabled(manifest.services());
            ManifestModel.Component receiver = firstEnabled(manifest.receivers());
            ManifestModel.Component provider = firstEnabled(manifest.providers());

            File packageDir = new File(packagesRoot, safeSegment(packageName));
            verifyExistingPackageState(packageDir, previousRecord);
            File backupDir = new File(packagesRoot, ".backup-" + safeSegment(packageName) + "-" + System.nanoTime());
            boolean hadPrevious = packageDir.exists();
            if (hadPrevious && !packageDir.renameTo(backupDir)) throw new IllegalStateException("Cannot stage previous package version");
            boolean committed = transactionDir.renameTo(packageDir);
            if (!committed) {
                if (hadPrevious) backupDir.renameTo(packageDir);
                throw new IllegalStateException("Cannot commit package installation");
            }
            transactionDir = null;
            deleteTree(backupDir);

            File apk = new File(packageDir, "base.apk");
            File nativeDir = new File(packageDir, "lib");
            return new SandboxRecord(packageName, label, version, versionCode, signatureSha256, apk.getAbsolutePath(),
                    selectedAbi.isEmpty() ? "" : nativeDir.getAbsolutePath(), manifest.launcherActivity(),
                    processName(packageName, activity), manifest.applicationClass(),
                    className(service), processName(packageName, service),
                    className(receiver), processName(packageName, receiver), firstAction(receiver),
                    className(provider), processName(packageName, provider),
                    provider == null ? "" : provider.authorities(),
                    String.join(",", manifest.permissions()), sha,
                    System.currentTimeMillis(), "NOT_TESTED", 0);
        } catch (Exception error) {
            temporary.delete();
            deleteTree(transactionDir);
            throw error;
        }
    }

    private String copyAndHash(Uri uri, File destination) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalArgumentException("Cannot open selected document");
            try (BufferedInputStream input = new BufferedInputStream(raw); BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_APK_BYTES) throw new IllegalArgumentException("APK exceeds 1.5 GiB limit");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
            }
        }
        if (total < 4) throw new IllegalArgumentException("Selected file is empty");
        try (FileInputStream in = new FileInputStream(destination)) {
            if (in.read() != 'P' || in.read() != 'K') throw new IllegalArgumentException("Selected file is not a ZIP/APK");
        }
        return toHex(digest.digest());
    }

    private String extractNativeLibraries(File apk, File outputDir) throws Exception {
        Set<String> available = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            int entries = 0;
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++entries > MAX_ZIP_ENTRIES) throw new IllegalArgumentException("APK has too many ZIP entries");
                String name = entry.getName();
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length == 3) available.add(parts[1]);
                }
            }
        }
        String selected = "";
        for (String abi : Build.SUPPORTED_ABIS) if (available.contains(abi)) { selected = abi; break; }
        if (selected.isEmpty()) return "";
        if (!outputDir.mkdirs() && !outputDir.isDirectory()) throw new IllegalStateException("Cannot create native library directory");
        long total = 0;
        Set<String> extractedNames = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String prefix = "lib/" + selected + "/";
                if (entry.isDirectory() || !entry.getName().startsWith(prefix) || !entry.getName().endsWith(".so")) continue;
                String fileName = entry.getName().substring(prefix.length());
                if (fileName.contains("/") || fileName.contains("\\") || fileName.trim().isEmpty()) throw new IllegalArgumentException("Unsafe native library path");
                if (!extractedNames.add(fileName)) throw new IllegalArgumentException("Duplicate native library entry: " + fileName);
                File output = new File(outputDir, fileName);
                long remaining = MAX_NATIVE_BYTES - total;
                try (InputStream input = zip.getInputStream(entry); FileOutputStream file = new FileOutputStream(output);
                     BufferedOutputStream out = new BufferedOutputStream(file)) {
                    total += copyLimited(input, out, remaining);
                    out.flush();
                    file.getFD().sync();
                }
                output.setReadable(true, true); output.setWritable(false, false); output.setExecutable(false, false);
            }
        }
        return selected;
    }


    private void verifyExistingPackageState(File packageDir, SandboxRecord previousRecord) throws Exception {
        if (!packageDir.exists()) {
            if (previousRecord != null) {
                throw new SecurityException("TRUSTED_PACKAGE_DIRECTORY_MISSING");
            }
            return;
        }
        if (!packageDir.isDirectory()) throw new SecurityException("PACKAGE_PATH_NOT_DIRECTORY");
        if (previousRecord == null) throw new SecurityException("PACKAGE_METADATA_MISSING_FOR_EXISTING_INSTALL");
        File existingApk = new File(packageDir, "base.apk");
        if (!existingApk.isFile()) throw new SecurityException("EXISTING_BASE_APK_MISSING");
        if (!existingApk.getCanonicalFile().equals(new File(previousRecord.apkPath).getCanonicalFile())) {
            throw new SecurityException("PACKAGE_METADATA_PATH_MISMATCH");
        }
        int flags = PackageManager.GET_META_DATA | (Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        PackageInfo existingInfo = context.getPackageManager().getPackageArchiveInfo(existingApk.getAbsolutePath(), flags);
        if (existingInfo == null || !previousRecord.packageName.equals(existingInfo.packageName)) {
            throw new SecurityException("EXISTING_PACKAGE_IDENTITY_INVALID");
        }
        long existingVersion = Build.VERSION.SDK_INT >= 28
                ? existingInfo.getLongVersionCode() : existingInfo.versionCode;
        if (existingVersion != previousRecord.versionCode) {
            throw new SecurityException("PACKAGE_METADATA_VERSION_MISMATCH");
        }
        if (!signingDigest(existingInfo).equals(previousRecord.signatureSha256)) {
            throw new SecurityException("PACKAGE_METADATA_SIGNER_MISMATCH");
        }
        if (!previousRecord.sha256.trim().isEmpty()
                && !sha256(existingApk).equals(previousRecord.sha256)) {
            throw new SecurityException("PACKAGE_METADATA_DIGEST_MISMATCH");
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return toHex(digest.digest());
    }

    private SandboxRecord existingRecord(String packageName) {
        for (SandboxRecord record : new SandboxRepository(context).load()) {
            if (record.packageName.equals(packageName)) return record;
        }
        return null;
    }

    private static String signingDigest(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) throw new SecurityException("APK signing certificate is missing");
        List<String> digests = new ArrayList<>();
        for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digests.add(toHex(digest.digest(signature.toByteArray())));
        }
        Collections.sort(digests);
        return String.join(",", digests);
    }

    private static long copyLimited(InputStream input, java.io.OutputStream output, long limit) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new java.io.IOException("Native libraries exceed 1 GiB limit");
            output.write(buffer, 0, count);
        }
        return total;
    }


    private static ManifestModel.Component componentByClass(java.util.List<ManifestModel.Component> components,
                                                             String className) {
        if (className == null || className.trim().isEmpty()) return null;
        for (ManifestModel.Component component : components) {
            if (className.equals(component.className())) return component;
        }
        return null;
    }

    private static String processName(String packageName, ManifestModel.Component component) {
        if (component == null) return packageName;
        if (component.isolatedProcess()) {
            return packageName + ":isolated_" + component.className().replaceAll("[^A-Za-z0-9_]", "_");
        }
        String declared = component.processName();
        if (declared == null || declared.trim().isEmpty()) return packageName;
        return declared.startsWith(":") ? packageName + declared : declared;
    }
    private static ManifestModel.Component firstEnabled(java.util.List<ManifestModel.Component> components) {
        for (ManifestModel.Component component : components) if (component.enabled()) return component;
        return null;
    }
    private static String className(ManifestModel.Component component) { return component == null ? "" : component.className(); }
    private static String firstAction(ManifestModel.Component component) {
        return component == null || component.actions().isEmpty() ? "" : component.actions().get(0);
    }

    private static void copy(InputStream input, java.io.OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
    private static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF; output[i * 2] = alphabet[value >>> 4]; output[i * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(output);
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        file.delete();
    }
    private static String safeSegment(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static void moveFile(File source, File destination) throws Exception {
        if (source.renameTo(destination)) return;
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(destination)) { copy(in, out); }
        if (!source.delete()) source.deleteOnExit();
    }
}
