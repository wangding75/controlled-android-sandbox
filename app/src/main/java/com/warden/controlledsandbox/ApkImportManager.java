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
    private static final int MAX_ZIP_ENTRIES = 200_000;
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
        List<File> stagedFiles = new ArrayList<>();
        List<String> stagedDigests = new ArrayList<>();
        long totalBytes = 0;
        File incoming = new File(transactionDir, "incoming");
        if (!incoming.mkdirs()) throw new IllegalStateException("Cannot create incoming artifact directory");
        File baseFile = null;
        for (int index = 0; index < sources.size(); index++) {
            File source = sources.get(index);
            if (source == null || !source.isFile()) throw new IllegalArgumentException("Source APK does not exist");
            File staged = new File(incoming, String.format(Locale.ROOT, "%03d.apk", index));
            CopyResult copied = copyFileAndHash(source, staged, MAX_APK_BYTES);
            totalBytes += copied.bytes;
            if (totalBytes > MAX_INSTALL_BYTES) throw new IllegalArgumentException("Install set exceeds 3 GiB limit");
            ManifestModel manifest = traced(PackageMutationTrace.PARSE,
                    () -> parseManifest(staged));
            if (manifest.splitName().isEmpty()) {
                if (baseFile != null) throw new IllegalArgumentException("Install set contains more than one base APK");
                baseFile = staged;
            }
            stagedFiles.add(staged);
            stagedDigests.add(copied.sha256);
        }
        if (baseFile == null) throw new IllegalArgumentException("Install set does not contain a base APK");
        File resolvedBaseFile = baseFile;
        PackageInfo baseInfo = traced(PackageMutationTrace.PARSE,
                () -> packageInfoForArchive(resolvedBaseFile));
        if (baseInfo == null) throw new IllegalArgumentException("PackageManager rejected the base APK artifact");
        for (int index = 0; index < stagedFiles.size(); index++) {
            int artifactIndex = index;
            artifacts.add(traced(PackageMutationTrace.PARSE,
                    () -> inspect(stagedFiles.get(artifactIndex),
                            stagedDigests.get(artifactIndex), baseInfo,
                            sources.get(artifactIndex))));
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
        String selectedAbi = traced(PackageMutationTrace.NATIVE_EXTRACT,
                () -> extractNativeLibraries(stagedRecords, stagedNativeDir));
        if (containsNativeCode && selectedAbi.isEmpty()) {
            throw new SecurityException("NATIVE_ABI_UNSUPPORTED");
        }
        File revisionsRoot = storageLayout.revisionsDirectory(packageName);
        if (!revisionsRoot.isDirectory() && !revisionsRoot.mkdirs() && !revisionsRoot.isDirectory()) {
            throw new IllegalStateException("Cannot create package revision root");
        }
        File revisionDir = storageLayout.revisionDirectory(packageName, revisionSha256);
        storageLayout.requireInsidePackagesRoot(revisionDir);
        storageLayout.requireNoManagedSymlinks(revisionDir);
        traced(PackageMutationTrace.PUBLISH, () -> {
            if (revisionDir.exists()) {
                removeKnownRuntimeProfileSidecars(revisionDir);
                requireMatchingPublishedRevision(transactionDir, revisionDir, selectedAbi);
                sealPublishedRevision(revisionDir);
                deleteTreeOrThrow(transactionDir);
            } else {
                publishDirectory(transactionDir, revisionDir);
                sealPublishedRevision(revisionDir);
            }
            return null;
        });

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
                merged.launcherActivity,
                processName(packageName, merged.applicationProcessName, activity),
                base.manifest.applicationClass(), className(service),
                processName(packageName, merged.applicationProcessName, service),
                className(receiver), processName(packageName, merged.applicationProcessName, receiver),
                firstAction(receiver), className(provider),
                processName(packageName, merged.applicationProcessName, provider),
                provider == null ? "" : provider.authorities(),
                String.join(",", merged.permissions), String.join(",", merged.sharedLibraries),
                revisionSha256, base.sha256, published, importedAt,
                importedAt, importedAt, "NOT_TESTED", 0);
    }

    private InspectedArtifact inspect(File file, String sha256, PackageInfo baseInfo,
                                      File originalSource) throws Exception {
        ManifestModel manifest = parseManifest(file);
        PackageInfo info = packageInfoForArchive(file);
        if (!manifest.packageName().matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("Invalid package name");
        }
        if (info == null) {
            if (manifest.splitName().isEmpty()) {
                throw new IllegalArgumentException("PackageManager rejected an APK artifact: " + file.getName());
            }
            if (manifest.versionCode() <= 0) {
                throw new IllegalArgumentException("Split APK revision is missing: " + file.getName());
            }
            String signer = splitSignerDigest(file, originalSource, baseInfo);
            return new InspectedArtifact(file, sha256, manifest, splitIdentity(manifest),
                    manifest.versionCode(), signer);
        }
        if (!manifest.packageName().equals(info.packageName)) {
            throw new IllegalArgumentException("Manifest and PackageManager package names differ");
        }
        long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        return new InspectedArtifact(file, sha256, manifest, info, versionCode, signingDigest(info));
    }

    private static PackageInfo splitIdentity(ManifestModel manifest) {
        PackageInfo info = new PackageInfo();
        info.packageName = manifest.packageName();
        info.versionCode = manifest.versionCode() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) manifest.versionCode();
        return info;
    }

    private ManifestModel parseManifest(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IllegalArgumentException("APK does not contain AndroidManifest.xml");
            try (InputStream input = zip.getInputStream(entry)) {
                return new BinaryXmlManifestParser().parse(input);
            }
        }
    }

    private PackageInfo packageInfoForArchive(File file) {
        int archiveFlags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                | PackageManager.GET_META_DATA | (Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        return context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), archiveFlags);
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

    private static void requireCompatibleElf(File file, String abi) throws Exception {
        int expectedClass;
        int expectedMachine;
        switch (abi) {
            case "arm64-v8a": expectedClass = 2; expectedMachine = 183; break;
            case "armeabi-v7a": expectedClass = 1; expectedMachine = 40; break;
            case "x86_64": expectedClass = 2; expectedMachine = 62; break;
            case "x86": expectedClass = 1; expectedMachine = 3; break;
            default: throw new SecurityException("NATIVE_ABI_UNSUPPORTED:" + abi);
        }
        byte[] header = new byte[20];
        int offset = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        boolean elf = offset >= 4
                && (header[0] & 0xff) == 0x7f && header[1] == 'E' && header[2] == 'L'
                && header[3] == 'F';
        if (!elf) {
            // Android PackageManager extracts lib/<abi>/*.so even when the payload is a
            // packed/encrypted blob (zip, 7z, version stamps) or shorter than an ELF header.
            return;
        }
        if (offset < header.length) throw new SecurityException("NATIVE_ELF_HEADER_SHORT");
        int type = (header[16] & 0xff) | ((header[17] & 0xff) << 8);
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        int elfClass = header[4] & 0xff;
        if ((header[5] & 0xff) != 1 || (header[6] & 0xff) != 1 || type != 3) {
            throw new SecurityException("NATIVE_ELF_FORMAT_UNSUPPORTED:" + abi);
        }
        boolean knownTarget = (elfClass == 2 && machine == 183)
                || (elfClass == 1 && machine == 40)
                || (elfClass == 2 && machine == 62)
                || (elfClass == 1 && machine == 3);
        if (!knownTarget) {
            throw new SecurityException("NATIVE_ELF_TARGET_UNSUPPORTED:" + abi);
        }
        if (elfClass != expectedClass || machine != expectedMachine) {
            PackageMutationTrace trace = PackageMutationTrace.current();
            if (trace != null) {
                trace.anomaly("MIXED_ELF_MACHINE", file.getName() + ":directoryAbi=" + abi
                        + ":class=" + elfClass + ":machine=" + machine);
            }
        }
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
        // Preserve Android's installed layout: ApplicationInfo.nativeLibraryDir points at the
        // selected ABI directory (for example .../lib/arm64-v8a), not at a flattened bag of
        // libraries. Some WebView/Chromium loaders derive sibling paths from this directory.
        File abiOutputDir = new File(outputDir, selected);
        if (!abiOutputDir.mkdirs() && !abiOutputDir.isDirectory()) {
            throw new IllegalStateException("Cannot create ABI native library directory");
        }
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
                    File temporary = new File(abiOutputDir, fileName + ".incoming");
                    long remaining = MAX_NATIVE_BYTES - total;
                    try (InputStream input = zip.getInputStream(entry);
                         FileOutputStream file = new FileOutputStream(temporary);
                         BufferedOutputStream out = new BufferedOutputStream(file)) {
                        total += copyLimited(input, out, remaining);
                        out.flush(); file.getFD().sync();
                    }
                    requireCompatibleElf(temporary, selected);
                    String digest = sha256(temporary);
                    String existing = extractedDigests.get(fileName);
                    if (existing != null && !existing.equals(digest)) {
                        throw new SecurityException("Conflicting native library across splits: " + fileName);
                    }
                    File output = new File(abiOutputDir, fileName);
                    if (existing == null) {
                        moveFile(temporary, output);
                        extractedDigests.put(fileName, digest);
                        // NativeLoader maps Guest ELF files with executable pages. Keep the
                        // published file owner-only but executable, matching PackageManager's
                        // extracted native-library contract without making the revision writable.
                        output.setReadable(true, true); output.setWritable(false, false); output.setExecutable(true, true);
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
                                                         File publishedRevision,
                                                         String selectedAbi) throws Exception {
        if (Files.isSymbolicLink(publishedRevision.toPath()) || !publishedRevision.isDirectory()) {
            throw new SecurityException("IMMUTABLE_REVISION_DIRECTORY_MISMATCH");
        }
        if (treeDigests(stagedRevision).equals(treeDigests(publishedRevision))) return;
        if (!selectedAbi.trim().isEmpty()
                && migrateLegacyFlatNativeLayout(stagedRevision, publishedRevision, selectedAbi)
                && treeDigests(stagedRevision).equals(treeDigests(publishedRevision))) return;
        throw new SecurityException("IMMUTABLE_REVISION_CONTENT_MISMATCH");
    }

    /**
     * Rehomes revisions produced before the ABI directory was preserved. The content-addressed
     * bytes are compared before any move; only a flat lib/*.so tree whose digests exactly match
     * the staged lib/<abi> tree is eligible. This keeps reinstall idempotent without accepting a
     * changed or injected native payload.
     */
    private static boolean migrateLegacyFlatNativeLayout(File stagedRevision,
                                                          File publishedRevision,
                                                          String selectedAbi) throws Exception {
        File stagedNative = new File(new File(stagedRevision, "lib"), selectedAbi);
        File publishedNative = new File(publishedRevision, "lib");
        if (!stagedNative.isDirectory() || !publishedNative.isDirectory()) return false;
        if (!flatNativeDigests(stagedNative).equals(flatNativeDigests(publishedNative))) return false;
        File[] children = publishedNative.listFiles();
        if (children == null) throw new IllegalStateException("Cannot list legacy native directory");
        File abiDirectory = new File(publishedNative, selectedAbi);
        publishedNative.setWritable(true, false);
        if (!abiDirectory.exists() && !abiDirectory.mkdirs() && !abiDirectory.isDirectory()) {
            throw new IllegalStateException("Cannot create migrated ABI native directory");
        }
        for (File child : children) {
            if (child.equals(abiDirectory)) continue;
            if (Files.isSymbolicLink(child.toPath()) || !child.isFile()
                    || !child.getName().endsWith(".so")) {
                throw new SecurityException("LEGACY_NATIVE_LAYOUT_INVALID");
            }
            moveFile(child, new File(abiDirectory, child.getName()));
        }
        return true;
    }

    private static Map<String, String> flatNativeDigests(File root) throws Exception {
        Map<String, String> digests = new TreeMap<>();
        File[] children = root.listFiles();
        if (children == null) throw new IllegalStateException("Cannot list native directory " + root);
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath()) || !child.isFile()
                    || !child.getName().endsWith(".so")) return Map.of();
            digests.put(child.getName(), sha256(child));
        }
        return digests;
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
        return traced(PackageMutationTrace.COPY, () -> {
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
                    long hashStarted = System.nanoTime();
                    digest.update(buffer, 0, count);
                    PackageMutationTrace trace = PackageMutationTrace.current();
                    if (trace != null) {
                        trace.addMeasuredNanos(PackageMutationTrace.HASH,
                                System.nanoTime() - hashStarted);
                        trace.checkpoint();
                    }
                    output.write(buffer, 0, count);
                }
                output.flush(); file.getFD().sync();
            }
            if (total < 4) throw new IllegalArgumentException("APK artifact is empty");
            try (FileInputStream input = new FileInputStream(destination)) {
                if (input.read() != 'P' || input.read() != 'K') {
                    throw new IllegalArgumentException("Artifact is not a ZIP/APK");
                }
            }
            return new CopyResult(total, toHex(digest.digest()));
        });
    }

    private static <T> T traced(String stage, CheckedSupplier<T> action) throws Exception {
        PackageMutationTrace trace = PackageMutationTrace.current();
        if (trace == null) return action.get();
        try (PackageMutationTrace.StageScope ignored = trace.stage(stage)) {
            return action.get();
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }

    private static SandboxRecord existingRecord(String packageName, List<SandboxRecord> trustedRecords) {
        for (SandboxRecord record : trustedRecords) {
            if (record != null && record.packageName.equals(packageName)) return record;
        }
        return null;
    }

    private static String signingDigestFromApk(File file) throws Exception {
        java.security.cert.Certificate[] certificates = null;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file, true)) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                try (InputStream input = jar.getInputStream(entry)) {
                    while (input.read(buffer) != -1) { }
                }
                java.security.cert.Certificate[] found = entry.getCertificates();
                if (found != null && found.length > 0) {
                    certificates = found;
                    break;
                }
            }
        }
        if (certificates == null || certificates.length == 0) {
            throw new SecurityException("Split APK signing certificate is missing: " + file.getName());
        }
        List<String> digests = new ArrayList<>();
        for (java.security.cert.Certificate certificate : certificates) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digests.add(toHex(digest.digest(certificate.getEncoded())));
        }
        Collections.sort(digests);
        return String.join(",", digests);
    }

    /**
     * Split APKs delivered through a modern package session can be v2/v3 signed without a
     * JAR certificate that JarFile exposes.  The platform has already verified such a split
     * when it is present in the installed package; only that exact installed source path may
     * inherit the independently parsed base signer.  Arbitrary external split files remain
     * fail-closed when their certificate cannot be inspected.
     */
    private String splitSignerDigest(File stagedFile, File originalSource, PackageInfo baseInfo)
            throws Exception {
        try {
            return signingDigestFromApk(stagedFile);
        } catch (SecurityException missingCertificate) {
            if (!isCurrentInstalledArtifact(originalSource, baseInfo.packageName)) {
                throw missingCertificate;
            }
            return signingDigest(baseInfo);
        }
    }

    private boolean isCurrentInstalledArtifact(File source, String packageName) {
        if (source == null || packageName == null || packageName.trim().isEmpty()) return false;
        try {
            ApplicationInfo installed = context.getPackageManager().getApplicationInfo(
                    packageName, 0);
            String expected = source.getCanonicalPath();
            if (sameCanonicalPath(expected, installed.sourceDir)) return true;
            if (installed.splitSourceDirs != null) {
                for (String split : installed.splitSourceDirs) {
                    if (sameCanonicalPath(expected, split)) return true;
                }
            }
        } catch (Exception ignored) {
            // A path that cannot be tied to the live PMS package is not eligible for signer
            // inheritance and will be rejected by the caller.
        }
        return false;
    }

    private static boolean sameCanonicalPath(String expected, String candidate) throws Exception {
        return candidate != null && !candidate.trim().isEmpty()
                && expected.equals(new File(candidate).getCanonicalPath());
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
            appendComponents(out.activities, manifest.activities());
            appendComponents(out.services, manifest.services());
            appendComponents(out.receivers, manifest.receivers());
            appendComponents(out.providers, manifest.providers());
            out.permissions.addAll(manifest.permissions()); out.sharedLibraries.addAll(manifest.sharedLibraries());
            if (artifact.base()) {
                out.launcherActivity = manifest.launcherActivity();
                out.applicationProcessName = manifest.applicationProcessName();
            }
            else if (out.launcherActivity.isEmpty() && !manifest.launcherActivity().isEmpty()) {
                out.launcherActivity = manifest.launcherActivity();
            }
        }
        return out;
    }

    private static void appendComponents(List<ManifestModel.Component> target,
                                         List<ManifestModel.Component> incoming) {
        for (ManifestModel.Component component : incoming) {
            ManifestModel.Component existing = componentByClass(target, component.className());
            if (existing == null) target.add(component);
            else existing.mergeFrom(component);
        }
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
    private static String processName(String packageName, String applicationProcessName,
                                      ManifestModel.Component component) {
        if (component == null) return packageName;
        if (component.isolatedProcess()) return packageName + ":isolated_" + component.className().replaceAll("[^A-Za-z0-9_]", "_");
        String declared = component.processName();
        if (declared == null || declared.trim().isEmpty()) declared = applicationProcessName;
        if (declared == null || declared.trim().isEmpty()) return packageName;
        declared = declared.trim();
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
        if (Files.isSymbolicLink(path)) {
            file.setWritable(true);
            Files.delete(path);
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            // Published revisions are sealed recursively, so a Guest-generated directory
            // such as lib-compressed may be read-only even though the caller owns the tree.
            // Deleting a child requires write permission on its parent, not only on the child.
            // Re-enable the directory before walking it; otherwise Guest Clear fails on dso_lock.
            file.setWritable(true);
            File[] children = file.listFiles();
            if (children == null) throw new IllegalStateException("Cannot list directory " + file);
            for (File child : children) deleteTreeOrThrow(child);
        }
        file.setWritable(true);
        try {
            Files.delete(path);
        } catch (java.nio.file.AccessDeniedException accessDenied) {
            System.gc();
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            file.setWritable(true);
            if (!file.delete() && Files.exists(path)) {
                throw accessDenied;
            }
        }
    }

    static void publishDirectory(File source, File destination) throws Exception {
        DurableAtomicFile.moveAcknowledged(source.toPath(), destination.toPath());
    }

    /**
     * Published APK/native revisions are content-addressed and must not become a writable
     * staging area for ART profiles or application-generated files.  The same app UID is used
     * by Guest processes, so sealing the directory is required in addition to sealing files.
     * deleteTreeOrThrow() explicitly re-enables write access while reclaiming an unreferenced
     * revision.
     */
    private static void sealPublishedRevision(File revision) throws Exception {
        if (revision == null || !revision.isDirectory()) {
            throw new IllegalStateException("Published revision directory is missing");
        }
        File[] children = revision.listFiles();
        if (children == null) throw new IllegalStateException("Cannot list published revision " + revision);
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                throw new SecurityException("PUBLISHED_REVISION_SYMLINK");
            }
            if (child.isDirectory()) sealPublishedRevision(child);
            child.setReadable(true, true);
            child.setWritable(false, false);
            if (child.isDirectory()) {
                child.setExecutable(true, true);
            } else if ((revision.getName().equals("lib")
                    || (revision.getParentFile() != null
                    && revision.getParentFile().getName().equals("lib")))
                    && child.getName().endsWith(".so")) {
                // A sealed revision is immutable, not non-executable. Removing the execute
                // bit here makes translated ARM guests run a read-only mapping and can crash
                // inside the guest ELF after System.load succeeds.
                child.setExecutable(true, true);
            }
        }
        revision.setReadable(true, true);
        revision.setWritable(false, false);
        revision.setExecutable(true, true);
    }

    /**
     * Older revisions may already contain ART's generated current-profile sidecars.  They are
     * not APK/native content and are the only runtime files accepted during migration; any
     * other unexpected entry still fails the immutable tree comparison below.
     */
    private static void removeKnownRuntimeProfileSidecars(File revision) throws Exception {
        File profileDirectory = new File(new File(revision, "lib"), "oat");
        if (!profileDirectory.exists()) return;
        if (java.nio.file.Files.isSymbolicLink(profileDirectory.toPath())
                || !profileDirectory.isDirectory()) {
            throw new SecurityException("PUBLISHED_PROFILE_DIRECTORY_INVALID");
        }
        File[] children = profileDirectory.listFiles();
        if (children == null) throw new IllegalStateException(
                "Cannot list published profile directory " + profileDirectory);
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())
                    || !child.isFile() || !child.getName().endsWith(".prof")) {
                continue;
            }
            child.setWritable(true, false);
            if (!child.delete() && child.exists()) {
                throw new IllegalStateException("Cannot remove runtime profile sidecar " + child);
            }
        }
        File[] remaining = profileDirectory.listFiles();
        if (remaining != null && remaining.length == 0) {
            profileDirectory.setWritable(true, false);
            if (!profileDirectory.delete() && profileDirectory.exists()) {
                throw new IllegalStateException("Cannot remove empty runtime profile directory");
            }
        }
    }

    private static void moveFile(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Cannot create destination directory");
        }
        Exception moveFailure;
        try {
            DurableAtomicFile.moveAcknowledged(source.toPath(), destination.toPath());
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
            DurableAtomicFile.replacePreparedAcknowledged(temporary.toPath(), destination.toPath());
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
        String applicationProcessName = "";
        final List<ManifestModel.Component> activities = new ArrayList<>();
        final List<ManifestModel.Component> services = new ArrayList<>();
        final List<ManifestModel.Component> receivers = new ArrayList<>();
        final List<ManifestModel.Component> providers = new ArrayList<>();
        final Set<String> permissions = new LinkedHashSet<>();
        final Set<String> sharedLibraries = new LinkedHashSet<>();
    }
}
