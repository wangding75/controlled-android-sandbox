package com.warden.controlledsandbox;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import com.warden.controlledsandbox.domain.packageinfo.PackageUpgradePolicy;
import com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates and publishes one immutable single- or multi-APK revision. */
final class ApkImportManager {
    private static final long MAX_APK_BYTES = 1536L * 1024 * 1024;
    private static final long MAX_INSTALL_BYTES = 3L * 1024 * 1024 * 1024;
    private static final long MAX_NATIVE_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final int MAX_ARTIFACTS = 256;
    private final Context context;
    private final PackageStorageLayout storageLayout;

    ApkImportManager(Context context) {
        this.context = context.getApplicationContext();
        storageLayout = new PackageStorageLayout(this.context.getFilesDir());
    }

    SandboxRecord importApk(Uri uri, List<SandboxRecord> trustedRecords) throws Exception {
        return importApk(uri, trustedRecords,
                com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot
                        .NATIVE_GUEST_TRUST_UNTRUSTED);
    }

    SandboxRecord importApk(Uri uri, List<SandboxRecord> trustedRecords,
                            String nativeGuestTrust) throws Exception {
        File staging = createInputStagingFile();
        try {
            copyAndHash(uri, staging);
            return importApkFiles(List.of(staging), trustedRecords, nativeGuestTrust);
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    SandboxRecord importApkFile(File source, List<SandboxRecord> trustedRecords) throws Exception {
        return importApkFile(source, trustedRecords,
                com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot
                        .NATIVE_GUEST_TRUST_UNTRUSTED);
    }

    SandboxRecord importApkFile(File source, List<SandboxRecord> trustedRecords,
                                String nativeGuestTrust) throws Exception {
        return importApkFiles(List.of(source), trustedRecords, nativeGuestTrust);
    }

    SandboxRecord importApkFiles(List<File> sources, List<SandboxRecord> trustedRecords) throws Exception {
        return importApkFiles(sources, trustedRecords,
                com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot
                        .NATIVE_GUEST_TRUST_UNTRUSTED);
    }

    SandboxRecord importApkFiles(List<File> sources, List<SandboxRecord> trustedRecords,
                                 String nativeGuestTrust) throws Exception {
        if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("At least one APK artifact is required");
        if (sources.size() > MAX_ARTIFACTS) throw new IllegalArgumentException("Install contains too many APK artifacts");
        if (trustedRecords == null) throw new IllegalArgumentException("trustedRecords are required");
        File packagesRoot = storageLayout.packagesRoot();
        if (!packagesRoot.isDirectory() && !packagesRoot.mkdirs() && !packagesRoot.isDirectory()) {
            throw new IllegalStateException("Cannot create package root");
        }
        File transactionDir = new File(packagesRoot, ".install-" + System.nanoTime());
        if (!transactionDir.mkdirs()) throw new IllegalStateException("Cannot create install transaction");
        try {
            List<InspectedArtifact> artifacts = stageAndInspect(sources, transactionDir);
            return finishImport(transactionDir, artifacts, trustedRecords, nativeGuestTrust);
        } catch (Exception error) {
            try { deleteTreeOrThrow(transactionDir); }
            catch (Exception cleanupFailure) { error.addSuppressed(cleanupFailure); }
            throw error;
        }
    }

    private List<InspectedArtifact> stageAndInspect(List<File> sources, File transactionDir)
            throws Exception {
        List<InspectedArtifact> artifacts = new ArrayList<>();
        long totalBytes = 0;
        File incoming = new File(transactionDir, "incoming");
        if (!incoming.mkdirs()) throw new IllegalStateException("Cannot create incoming artifact directory");
        for (int index = 0; index < sources.size(); index++) {
            File source = sources.get(index);
            if (source == null || !source.isFile()) throw new IllegalArgumentException("Source APK does not exist");
            File staged = new File(incoming, String.format(Locale.ROOT, "%03d.apk", index));
            CopyResult copied = copyFileAndHash(source, staged, MAX_APK_BYTES);
            totalBytes += copied.bytes;
            if (totalBytes > MAX_INSTALL_BYTES) throw new IllegalArgumentException("Install set exceeds 3 GiB limit");
            artifacts.add(inspect(staged, copied.sha256));
        }
        validateArtifactSet(artifacts);
        Collections.sort(artifacts);
        return artifacts;
    }

    private SandboxRecord finishImport(File transactionDir, List<InspectedArtifact> artifacts,
                                       List<SandboxRecord> trustedRecords,
                                       String nativeGuestTrust) throws Exception {
        InspectedArtifact base = baseArtifact(artifacts);
        String packageName = base.manifest.packageName();
        SandboxRecord previousRecord = existingRecord(packageName, trustedRecords);
        new PackageUpgradePolicy().validate(previousRecord == null ? 0 : previousRecord.versionCode,
                previousRecord == null ? "" : previousRecord.signatureSha256,
                base.versionCode, base.signatureSha256);
        verifyExistingPackageState(previousRecord);

        String revisionSha256 = revisionDigest(artifacts);
        File incoming = new File(transactionDir, "incoming");
        File stagedBase = new File(transactionDir, "base.apk");
        moveFile(base.file, stagedBase);
        File stagedSplits = new File(transactionDir, "splits");
        List<PackageArtifactRecord> stagedRecords = new ArrayList<>();
        stagedRecords.add(new PackageArtifactRecord("", PackageArtifactRecord.TYPE_BASE, "", "",
                stagedBase.getAbsolutePath(), base.sha256));
        for (InspectedArtifact artifact : artifacts) {
            if (artifact.base()) continue;
            if (!stagedSplits.isDirectory() && !stagedSplits.mkdirs() && !stagedSplits.isDirectory()) {
                throw new IllegalStateException("Cannot create split staging directory");
            }
            File staged = new File(stagedSplits,
                    "split_" + PackageArtifactRecord.safe(artifact.splitName()) + ".apk");
            moveFile(artifact.file, staged);
            String configFor = artifact.manifest.configForSplit();
            if (PackageArtifactRecord.TYPE_CONFIG.equals(artifact.type()) && configFor.isEmpty()) configFor = "base";
            stagedRecords.add(new PackageArtifactRecord(artifact.splitName(), artifact.type(),
                    configFor, artifact.manifest.usesSplit(), staged.getAbsolutePath(), artifact.sha256));
        }
        deleteTreeOrThrow(incoming);

        boolean containsNativeCode = containsNativeCode(stagedRecords);
        NativeGuestExecutionPolicy.requireInstallAllowed(containsNativeCode, nativeGuestTrust);
        File stagedNativeDir = new File(transactionDir, "lib");
        String selectedAbi = extractNativeLibraries(stagedRecords, stagedNativeDir);
        File revisionsRoot = storageLayout.revisionsDirectory(packageName);
        if (!revisionsRoot.isDirectory() && !revisionsRoot.mkdirs() && !revisionsRoot.isDirectory()) {
            throw new IllegalStateException("Cannot create package revision root");
        }
        File revisionDir = storageLayout.revisionDirectory(packageName, revisionSha256);
        storageLayout.requireInsidePackagesRoot(revisionDir);
        storageLayout.requireNoManagedSymlinks(revisionDir);
        if (revisionDir.exists()) {
            requireMatchingPublishedRevision(transactionDir, revisionDir);
            deleteTreeOrThrow(transactionDir);
        } else {
            publishDirectory(transactionDir, revisionDir);
        }

        File apk = new File(revisionDir, "base.apk");
        File nativeDir = new File(revisionDir, "lib");
        List<PackageArtifactRecord> published = new ArrayList<>();
        for (PackageArtifactRecord artifact : stagedRecords) {
            File path = artifact.base() ? apk
                    : storageLayout.splitApkFile(packageName, revisionSha256, artifact.splitName);
            published.add(artifact.withPath(path.getAbsolutePath()));
            path.setReadable(true, true); path.setWritable(false, false); path.setExecutable(false, false);
        }

        ApplicationInfo applicationInfo = base.packageInfo.applicationInfo;
        applicationInfo.sourceDir = apk.getAbsolutePath();
        applicationInfo.publicSourceDir = apk.getAbsolutePath();
        applicationInfo.splitSourceDirs = splitPaths(published);
        applicationInfo.splitPublicSourceDirs = splitPaths(published);
        CharSequence loadedLabel = context.getPackageManager().getApplicationLabel(applicationInfo);
        String label = loadedLabel == null || loadedLabel.toString().trim().isEmpty()
                ? packageName : loadedLabel.toString();
        String version = base.packageInfo.versionName == null ? "" : base.packageInfo.versionName;
        ManifestSet merged = mergeManifests(artifacts);
        ManifestModel.Component activity = componentByClass(merged.activities, merged.launcherActivity);
        ManifestModel.Component service = firstEnabled(merged.services);
        ManifestModel.Component receiver = firstEnabled(merged.receivers);
        ManifestModel.Component provider = firstEnabled(merged.providers);

        long importedAt = System.currentTimeMillis();
        return new SandboxRecord(packageName, label, version, base.versionCode,
                base.signatureSha256, apk.getAbsolutePath(),
                selectedAbi.isEmpty() ? "" : nativeDir.getAbsolutePath(), selectedAbi,
                containsNativeCode, NativeGuestExecutionPolicy.normalizeTrust(nativeGuestTrust),
                merged.launcherActivity, processName(packageName, activity),
                base.manifest.applicationClass(), className(service), processName(packageName, service),
                className(receiver), processName(packageName, receiver), firstAction(receiver),
                className(provider), processName(packageName, provider),
                provider == null ? "" : provider.authorities(),
                String.join(",", merged.permissions), String.join(",", merged.sharedLibraries),
                revisionSha256, base.sha256, published, importedAt,
                importedAt, importedAt, "NOT_TESTED", 0);
    }

    private InspectedArtifact inspect(File file, String sha256) throws Exception {
        ManifestModel manifest;
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IllegalArgumentException("APK does not contain AndroidManifest.xml");
            try (InputStream input = zip.getInputStream(entry)) {
                manifest = new BinaryXmlManifestParser().parse(input);
            }
        }
        int archiveFlags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                | PackageManager.GET_META_DATA | (Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), archiveFlags);
        if (info == null) throw new IllegalArgumentException("PackageManager rejected an APK artifact");
        if (!manifest.packageName().matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("Invalid package name");
        }
        if (!manifest.packageName().equals(info.packageName)) {
            throw new IllegalArgumentException("Manifest and PackageManager package names differ");
        }
        long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        return new InspectedArtifact(file, sha256, manifest, info, versionCode, signingDigest(info));
    }

    private static void validateArtifactSet(List<InspectedArtifact> artifacts) {
        InspectedArtifact base = null;
        Set<String> splitNames = new HashSet<>();
        for (InspectedArtifact artifact : artifacts) {
            if (artifact.base()) {
                if (base != null) throw new IllegalArgumentException("Install set contains more than one base APK");
                base = artifact;
            } else if (!splitNames.add(artifact.splitName())) {
                throw new IllegalArgumentException("Duplicate split name: " + artifact.splitName());
            }
        }
        if (base == null) throw new IllegalArgumentException("Install set does not contain a base APK");
        for (InspectedArtifact artifact : artifacts) {
            if (!base.manifest.packageName().equals(artifact.manifest.packageName())) {
                throw new IllegalArgumentException("APK artifacts belong to different packages");
            }
            if (base.versionCode != artifact.versionCode) {
                throw new IllegalArgumentException("APK artifacts have different version codes");
            }
            if (!base.signatureSha256.equals(artifact.signatureSha256)) {
                throw new SecurityException("APK artifacts have different signing certificates");
            }
            if (!artifact.manifest.usesSplit().isEmpty()
                    && !splitNames.contains(artifact.manifest.usesSplit())) {
                throw new IllegalArgumentException("Missing required split: " + artifact.manifest.usesSplit());
            }
            if (!artifact.manifest.configForSplit().isEmpty()
                    && !"base".equals(artifact.manifest.configForSplit())
                    && !splitNames.contains(artifact.manifest.configForSplit())) {
                throw new IllegalArgumentException("Configuration split targets missing split: "
                        + artifact.manifest.configForSplit());
            }
        }
    }

    static boolean containsNativeCode(List<PackageArtifactRecord> artifacts) throws Exception {
        if (artifacts == null) throw new IllegalArgumentException("artifacts are required");
        for (PackageArtifactRecord artifact : artifacts) {
            try (ZipFile zip = new ZipFile(artifact.path)) {
                int entries = 0;
                var enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (++entries > MAX_ZIP_ENTRIES) {
                        throw new IllegalArgumentException("APK has too many ZIP entries");
                    }
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        String[] parts = name.split("/");
                        if (parts.length == 3 && !parts[1].isEmpty() && !parts[2].isEmpty()) return true;
                    }
                    try (InputStream input = zip.getInputStream(entry)) {
                        if (hasElfMagic(input)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasElfMagic(InputStream input) throws java.io.IOException {
        return input.read() == 0x7f && input.read() == 'E'
                && input.read() == 'L' && input.read() == 'F';
    }

    private String extractNativeLibraries(List<PackageArtifactRecord> artifacts, File outputDir)
            throws Exception {
        Set<String> available = new HashSet<>();
        for (PackageArtifactRecord artifact : artifacts) {
            try (ZipFile zip = new ZipFile(artifact.path)) {
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
        }
        String selected = "";
        for (String abi : Build.SUPPORTED_ABIS) if (available.contains(abi)) { selected = abi; break; }
        if (selected.isEmpty()) return "";
        if (!outputDir.mkdirs() && !outputDir.isDirectory()) throw new IllegalStateException("Cannot create native library directory");
        long total = 0;
        Map<String, String> extractedDigests = new HashMap<>();
        for (PackageArtifactRecord artifact : artifacts) {
            try (ZipFile zip = new ZipFile(artifact.path)) {
                var enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    String prefix = "lib/" + selected + "/";
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)
                            || !entry.getName().endsWith(".so")) continue;
                    String fileName = entry.getName().substring(prefix.length());
                    if (fileName.contains("/") || fileName.contains("\\") || fileName.trim().isEmpty()) {
                        throw new IllegalArgumentException("Unsafe native library path");
                    }
                    File temporary = new File(outputDir, fileName + ".incoming");
                    long remaining = MAX_NATIVE_BYTES - total;
                    try (InputStream input = zip.getInputStream(entry);
                         FileOutputStream file = new FileOutputStream(temporary);
                         BufferedOutputStream out = new BufferedOutputStream(file)) {
                        total += copyLimited(input, out, remaining);
                        out.flush(); file.getFD().sync();
                    }
                    String digest = sha256(temporary);
                    String existing = extractedDigests.get(fileName);
                    if (existing != null && !existing.equals(digest)) {
                        throw new SecurityException("Conflicting native library across splits: " + fileName);
                    }
                    File output = new File(outputDir, fileName);
                    if (existing == null) {
                        moveFile(temporary, output);
                        extractedDigests.put(fileName, digest);
                        output.setReadable(true, true); output.setWritable(false, false); output.setExecutable(false, false);
                    } else {
                        Files.deleteIfExists(temporary.toPath());
                    }
                }
            }
        }
        return selected;
    }

    private void verifyExistingPackageState(SandboxRecord previousRecord) throws Exception {
        if (previousRecord == null) return;
        storageLayout.requireRecordLayout(previousRecord);
        File existingApk = new File(previousRecord.apkPath).getCanonicalFile();
        int flags = PackageManager.GET_META_DATA | (Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        PackageInfo existingInfo = context.getPackageManager().getPackageArchiveInfo(existingApk.getAbsolutePath(), flags);
        if (existingInfo == null || !previousRecord.packageName.equals(existingInfo.packageName)) {
            throw new SecurityException("EXISTING_PACKAGE_IDENTITY_INVALID");
        }
        long existingVersion = Build.VERSION.SDK_INT >= 28
                ? existingInfo.getLongVersionCode() : existingInfo.versionCode;
        if (existingVersion != previousRecord.versionCode) throw new SecurityException("PACKAGE_METADATA_VERSION_MISMATCH");
        if (!signingDigest(existingInfo).equals(previousRecord.signatureSha256)) {
            throw new SecurityException("PACKAGE_METADATA_SIGNER_MISMATCH");
        }
        if (!previousRecord.baseApkSha256.equals(sha256(existingApk))) {
            throw new SecurityException("PACKAGE_METADATA_DIGEST_MISMATCH");
        }
    }

    private static void requireMatchingPublishedRevision(File stagedRevision,
                                                         File publishedRevision) throws Exception {
        if (Files.isSymbolicLink(publishedRevision.toPath()) || !publishedRevision.isDirectory()) {
            throw new SecurityException("IMMUTABLE_REVISION_DIRECTORY_MISMATCH");
        }
        if (!treeDigests(stagedRevision).equals(treeDigests(publishedRevision))) {
            throw new SecurityException("IMMUTABLE_REVISION_CONTENT_MISMATCH");
        }
    }

    private static Map<String, String> treeDigests(File root) throws Exception {
        Map<String, String> digests = new TreeMap<>();
        collectFileDigests(root, root, digests);
        return digests;
    }

    private static void collectFileDigests(File root, File current, Map<String, String> digests)
            throws Exception {
        if (Files.isSymbolicLink(current.toPath())) throw new SecurityException("REVISION_CONTAINS_SYMBOLIC_LINK");
        if (current.isDirectory()) {
            File[] children = current.listFiles();
            if (children == null) throw new IllegalStateException("Cannot list revision " + current);
            for (File child : children) collectFileDigests(root, child, digests);
        } else if (current.isFile()) {
            String relative = root.toPath().relativize(current.toPath()).toString().replace(File.separatorChar, '/');
            digests.put(relative, sha256(current));
        } else {
            throw new SecurityException("Unsupported revision entry: " + current);
        }
    }

    static String revisionDigest(List<InspectedArtifact> artifacts) throws Exception {
        if (artifacts.size() == 1 && artifacts.get(0).base()) return artifacts.get(0).sha256;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<InspectedArtifact> sorted = new ArrayList<>(artifacts);
        Collections.sort(sorted);
        for (InspectedArtifact artifact : sorted) {
            String configFor = artifact.manifest.configForSplit();
            if (PackageArtifactRecord.TYPE_CONFIG.equals(artifact.type()) && configFor.isEmpty()) {
                configFor = "base";
            }
            String line = artifact.type() + "|" + artifact.splitName() + "|"
                    + configFor + "|" + artifact.manifest.usesSplit()
                    + "|" + artifact.sha256 + "\n";
            digest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return toHex(digest.digest());
    }

    static String revisionDigestRecords(List<PackageArtifactRecord> artifacts) throws Exception {
        if (artifacts.size() == 1 && artifacts.get(0).base()) return artifacts.get(0).sha256;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<PackageArtifactRecord> sorted = new ArrayList<>(artifacts);
        Collections.sort(sorted);
        for (PackageArtifactRecord artifact : sorted) {
            String line = artifact.type + "|" + artifact.splitName + "|" + artifact.configForSplit
                    + "|" + artifact.usesSplit + "|" + artifact.sha256 + "\n";
            digest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return toHex(digest.digest());
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return toHex(digest.digest());
    }

    private File createInputStagingFile() throws Exception {
        File stagingRoot = new File(context.getFilesDir(), "staging");
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) throw new IllegalStateException("Cannot create staging directory");
        return File.createTempFile("import-", ".apk", stagingRoot);
    }

    private String copyAndHash(Uri uri, File destination) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Cannot open selected document");
            return copyStreamAndHash(input, destination, MAX_APK_BYTES).sha256;
        }
    }

    private static CopyResult copyFileAndHash(File source, File destination, long limit) throws Exception {
        try (InputStream input = new FileInputStream(source)) {
            return copyStreamAndHash(input, destination, limit);
        }
    }

    private static CopyResult copyStreamAndHash(InputStream raw, File destination, long limit)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (BufferedInputStream input = new BufferedInputStream(raw);
             FileOutputStream file = new FileOutputStream(destination);
             BufferedOutputStream output = new BufferedOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IllegalArgumentException("APK exceeds size limit");
                digest.update(buffer, 0, count); output.write(buffer, 0, count);
            }
            output.flush(); file.getFD().sync();
        }
        if (total < 4) throw new IllegalArgumentException("APK artifact is empty");
        try (FileInputStream input = new FileInputStream(destination)) {
            if (input.read() != 'P' || input.read() != 'K') throw new IllegalArgumentException("Artifact is not a ZIP/APK");
        }
        return new CopyResult(total, toHex(digest.digest()));
    }

    private static SandboxRecord existingRecord(String packageName, List<SandboxRecord> trustedRecords) {
        for (SandboxRecord record : trustedRecords) {
            if (record != null && record.packageName.equals(packageName)) return record;
        }
        return null;
    }

    private static String signingDigest(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) signatures = info.signingInfo.getApkContentsSigners();
        else signatures = info.signatures;
        if (signatures == null || signatures.length == 0) throw new SecurityException("APK signing certificate is missing");
        List<String> digests = new ArrayList<>();
        for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digests.add(toHex(digest.digest(signature.toByteArray())));
        }
        Collections.sort(digests);
        return String.join(",", digests);
    }

    private static ManifestSet mergeManifests(List<InspectedArtifact> artifacts) {
        ManifestSet out = new ManifestSet();
        for (InspectedArtifact artifact : artifacts) {
            ManifestModel manifest = artifact.manifest;
            out.activities.addAll(manifest.activities()); out.services.addAll(manifest.services());
            out.receivers.addAll(manifest.receivers()); out.providers.addAll(manifest.providers());
            out.permissions.addAll(manifest.permissions()); out.sharedLibraries.addAll(manifest.sharedLibraries());
            if (artifact.base()) out.launcherActivity = manifest.launcherActivity();
            else if (out.launcherActivity.isEmpty() && !manifest.launcherActivity().isEmpty()) {
                out.launcherActivity = manifest.launcherActivity();
            }
        }
        return out;
    }

    private static InspectedArtifact baseArtifact(List<InspectedArtifact> artifacts) {
        for (InspectedArtifact artifact : artifacts) if (artifact.base()) return artifact;
        throw new IllegalStateException("Base APK is unavailable");
    }

    private static String[] splitPaths(List<PackageArtifactRecord> artifacts) {
        List<String> paths = new ArrayList<>();
        for (PackageArtifactRecord artifact : artifacts) if (!artifact.base()) paths.add(artifact.path);
        return paths.isEmpty() ? null : paths.toArray(new String[0]);
    }

    private static ManifestModel.Component componentByClass(List<ManifestModel.Component> components,
                                                             String className) {
        if (className == null || className.trim().isEmpty()) return null;
        for (ManifestModel.Component component : components) if (className.equals(component.className())) return component;
        return null;
    }
    private static ManifestModel.Component firstEnabled(List<ManifestModel.Component> components) {
        for (ManifestModel.Component component : components) if (component.enabled()) return component;
        return null;
    }
    private static String processName(String packageName, ManifestModel.Component component) {
        if (component == null) return packageName;
        if (component.isolatedProcess()) return packageName + ":isolated_" + component.className().replaceAll("[^A-Za-z0-9_]", "_");
        String declared = component.processName();
        if (declared == null || declared.trim().isEmpty()) return packageName;
        return declared.startsWith(":") ? packageName + declared : declared;
    }
    private static String className(ManifestModel.Component component) { return component == null ? "" : component.className(); }
    private static String firstAction(ManifestModel.Component component) {
        return component == null || component.actions().isEmpty() ? "" : component.actions().get(0);
    }

    private static long copyLimited(InputStream input, java.io.OutputStream output, long limit)
            throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new java.io.IOException("Native libraries exceed 1 GiB limit");
            output.write(buffer, 0, count);
        }
        return total;
    }

    private static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray(); char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF; output[i * 2] = alphabet[value >>> 4]; output[i * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(output);
    }

    static void deleteTreeOrThrow(File file) throws Exception {
        if (file == null) return;
        java.nio.file.Path path = file.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(path)) { Files.delete(path); return; }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            File[] children = file.listFiles();
            if (children == null) throw new IllegalStateException("Cannot list directory " + file);
            for (File child : children) deleteTreeOrThrow(child);
        }
        Files.delete(path);
    }

    static void publishDirectory(File source, File destination) throws Exception {
        DurableAtomicFile.move(source.toPath(), destination.toPath());
    }

    private static void moveFile(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Cannot create destination directory");
        }
        Exception moveFailure;
        try {
            DurableAtomicFile.move(source.toPath(), destination.toPath());
            return;
        } catch (Exception error) {
            moveFailure = error;
        }

        File temporary = File.createTempFile(destination.getName() + ".copy-", ".tmp", parent);
        try {
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
                 FileOutputStream file = new FileOutputStream(temporary);
                 BufferedOutputStream output = new BufferedOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush(); file.getFD().sync();
            }
            DurableAtomicFile.replacePrepared(temporary.toPath(), destination.toPath());
            if (!source.delete() && source.exists()) {
                throw new IllegalStateException("Cannot remove copied staging file " + source);
            }
            File sourceParent = source.getParentFile();
            if (sourceParent != null) DurableAtomicFile.syncDirectory(sourceParent.toPath());
        } catch (Exception copyFailure) {
            copyFailure.addSuppressed(moveFailure);
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (Exception cleanupFailure) {
                copyFailure.addSuppressed(cleanupFailure);
            }
            throw copyFailure;
        }
    }

    static final class InspectedArtifact implements Comparable<InspectedArtifact> {
        final File file; final String sha256; final ManifestModel manifest; final PackageInfo packageInfo;
        final long versionCode; final String signatureSha256;
        InspectedArtifact(File file, String sha256, ManifestModel manifest, PackageInfo packageInfo,
                          long versionCode, String signatureSha256) {
            this.file = file; this.sha256 = sha256; this.manifest = manifest; this.packageInfo = packageInfo;
            this.versionCode = versionCode; this.signatureSha256 = signatureSha256;
        }
        boolean base() { return manifest.splitName().isEmpty(); }
        String splitName() { return manifest.splitName(); }
        String type() {
            if (base()) return PackageArtifactRecord.TYPE_BASE;
            if (!manifest.configForSplit().isEmpty() || splitName().startsWith("config.")) return PackageArtifactRecord.TYPE_CONFIG;
            return PackageArtifactRecord.TYPE_FEATURE;
        }
        @Override public int compareTo(InspectedArtifact other) {
            if (base() != other.base()) return base() ? -1 : 1;
            return splitName().compareTo(other.splitName());
        }
    }

    private static final class CopyResult {
        final long bytes; final String sha256;
        CopyResult(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }

    private static final class ManifestSet {
        String launcherActivity = "";
        final List<ManifestModel.Component> activities = new ArrayList<>();
        final List<ManifestModel.Component> services = new ArrayList<>();
        final List<ManifestModel.Component> receivers = new ArrayList<>();
        final List<ManifestModel.Component> providers = new ArrayList<>();
        final Set<String> permissions = new LinkedHashSet<>();
        final Set<String> sharedLibraries = new LinkedHashSet<>();
    }
}
