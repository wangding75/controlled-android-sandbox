package com.warden.controlledsandbox.runtime.guest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.io.FileOutputStream;

/**
 * Selects an app-owned runtime native payload without widening the Guest native boundary.
 *
 * <p>Some applications deploy a newer WebView/U4 core below their private data directory and
 * expose that directory through {@code ApplicationInfo.nativeLibraryDir}.  The platform loader
 * and the application therefore agree on the same core only when this projection is made before
 * {@code LoadedApk} and {@code Application} bootstrap.  Native libraries are selected read-only;
 * when the matching core is a raw DEX file, CAS atomically creates a validated ZIP overlay in a
 * separate CAS-owned directory because U4 may retire the source after loading it.  Every selected
 * or generated path remains canonically contained by the current Guest data root.  Invalid or
 * incomplete deployments fall back to the verified APK native directory.</p>
 */
final class GuestNativeRuntimeProjection {
    private static final String U4_DIRECTORY = "app_u4sdk";
    private static final String U4_DLIBS_DIRECTORY = "dlibs";
    private static final String CAS_OVERLAY_DIRECTORY = ".cas-core-overlays";
    private static final String CORE_JAR = "core.jar";
    private static final String CORE_OVERLAY = "guest-core-overlay.apk";
    private static final String CORE_LIBRARY = "libwebviewuc.so";
    private static final String OPTIONAL_JSI_LIBRARY = "libjsi.so";
    private static final long MAX_PAYLOAD_BYTES = 512L * 1024L * 1024L;
    private static final long MIN_CORE_LIBRARY_BYTES = 4L * 1024L;

    private GuestNativeRuntimeProjection() { }

    static String select(GuestPackageSpec spec, File dataDir, String packagedNativeLibraryDir) {
        if (spec == null) return normalize(packagedNativeLibraryDir);
        return select(dataDir, spec.nativeAbi, spec.isolatedProcess, packagedNativeLibraryDir);
    }

    static String select(File dataDir, String nativeAbi, boolean isolatedProcess,
                         String packagedNativeLibraryDir) {
        String fallback = normalize(packagedNativeLibraryDir);
        if (isolatedProcess || dataDir == null || nativeAbi == null
                || nativeAbi.trim().isEmpty()) return fallback;
        try {
            File dataRoot = canonicalDirectory(dataDir);
            if (dataRoot == null) return fallback;
            Candidate selected = chooseCandidate(dataRoot, nativeAbi.trim());
            return selected == null ? fallback : selected.libraryDirectory;
        } catch (IOException | RuntimeException ignored) {
            // A partially written or concurrently retired deployment must never make process
            // bootstrap fail. The verified APK directory is the safe transaction fallback.
            return fallback;
        }
    }

    static String searchPath(GuestPackageSpec spec, File dataDir, String packagedNativeLibraryDir) {
        if (spec == null) return normalize(packagedNativeLibraryDir);
        return searchPath(dataDir, spec.nativeAbi, spec.isolatedProcess, packagedNativeLibraryDir);
    }

    static String searchPath(File dataDir, String nativeAbi, boolean isolatedProcess,
                             String packagedNativeLibraryDir) {
        String fallback = normalize(packagedNativeLibraryDir);
        String selected = select(dataDir, nativeAbi, isolatedProcess, fallback);
        if (selected.isEmpty()) return fallback;
        if (fallback.isEmpty() || samePath(selected, fallback)) return selected;
        return selected + File.pathSeparator + fallback;
    }

    static String prependCoreDexPath(File dataDir, String nativeAbi, boolean isolatedProcess,
                                     String packagedNativeLibraryDir, String baseDexPath) {
        String fallback = normalize(baseDexPath);
        String packagedNative = normalize(packagedNativeLibraryDir);
        try {
            Candidate candidate = selectedCandidate(dataDir, nativeAbi, isolatedProcess);
            if (candidate == null || samePath(candidate.libraryDirectory, packagedNative)
                    || candidate.rawDex) return fallback;
            return candidate.coreDex.getCanonicalPath()
                    + (fallback.isEmpty() ? "" : File.pathSeparator + fallback);
        } catch (IOException | RuntimeException ignored) {
            return fallback;
        }
    }

    static ByteBuffer mapCoreDex(File dataDir, String nativeAbi, boolean isolatedProcess,
                                 String packagedNativeLibraryDir) {
        String packagedNative = normalize(packagedNativeLibraryDir);
        try {
            Candidate candidate = selectedCandidate(dataDir, nativeAbi, isolatedProcess);
            if (candidate == null || samePath(candidate.libraryDirectory, packagedNative)
                    || !candidate.rawDex) return null;
            try (FileChannel channel = FileChannel.open(candidate.coreDex.toPath(), StandardOpenOption.READ)) {
                if (channel.size() <= 0L || channel.size() > Integer.MAX_VALUE) return null;
                return channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size());
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    static String materializeRawDexOverlay(File dataDir, String nativeAbi, boolean isolatedProcess,
                                           String packagedNativeLibraryDir) {
        String packagedNative = normalize(packagedNativeLibraryDir);
        Candidate candidate = selectedCandidate(dataDir, nativeAbi, isolatedProcess);
        if (candidate == null || samePath(candidate.libraryDirectory, packagedNative)) return "";
        if (!candidate.rawDex) {
            try { return candidate.coreDex.getCanonicalPath(); }
            catch (IOException | RuntimeException ignored) { return ""; }
        }
        ByteBuffer coreDex = mapCoreDex(dataDir, nativeAbi, isolatedProcess, packagedNative);
        if (coreDex == null) return "";
        File target = createOverlayTarget(dataDir, candidate.revision.getName());
        if (target == null) return "";
        File temporary = new File(target.getParentFile(), CORE_OVERLAY + ".tmp");
        long size = coreDex.remaining();
        if (size <= 0L || size > MAX_PAYLOAD_BYTES) return "";
        try {
            if (isValidDexZip(target)) return target.getCanonicalPath();
            if (!candidate.revision.isDirectory()) return "";
            CRC32 checksum = new CRC32();
            updateChecksum(coreDex.duplicate(), checksum);
            try (FileOutputStream output = new FileOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                ZipEntry entry = new ZipEntry("classes.dex");
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(size);
                entry.setCompressedSize(size);
                entry.setCrc(checksum.getValue());
                zip.putNextEntry(entry);
                writeBuffer(coreDex.duplicate(), zip);
                zip.closeEntry();
                zip.finish();
            }
            try (ZipFile verification = new ZipFile(temporary)) {
                ZipEntry entry = verification.getEntry("classes.dex");
                if (entry == null || entry.getMethod() != ZipEntry.STORED
                        || entry.getSize() != size) return "";
            }
            com.warden.controlledsandbox.domain.persistence.DurableAtomicFile.replacePrepared(
                    temporary.toPath(), target.toPath());
            return target.getCanonicalPath();
        } catch (IOException | RuntimeException ignored) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignoredDelete) { }
            return "";
        }
    }

    private static Candidate validateCandidate(File dataRoot, File revision, String nativeAbi)
            throws IOException {
        String name = revision.getName();
        if (!safeRevisionName(name)) return null;
        if (!Files.isDirectory(revision.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        File canonicalRevision = canonicalContained(dataRoot, revision);
        if (canonicalRevision == null) return null;
        File coreJar = regularFile(canonicalRevision, CORE_JAR, 1L, MAX_PAYLOAD_BYTES, dataRoot);
        File overlay = persistedOverlay(dataRoot, name);
        File coreDex = isValidDexSource(overlay) ? overlay
                : (isValidDexSource(coreJar) ? coreJar : null);
        File libDirectory = childDirectory(canonicalRevision, "lib", nativeAbi);
        if (coreDex == null || libDirectory == null) return null;
        File webView = regularFile(libDirectory, CORE_LIBRARY, MIN_CORE_LIBRARY_BYTES,
                MAX_PAYLOAD_BYTES, dataRoot);
        if (webView == null || !validElf(webView, nativeAbi)) return null;
        File jsi = new File(libDirectory, OPTIONAL_JSI_LIBRARY);
        if (Files.exists(jsi.toPath(), LinkOption.NOFOLLOW_LINKS)
                && regularFile(libDirectory, OPTIONAL_JSI_LIBRARY, 1L, MAX_PAYLOAD_BYTES, dataRoot) == null) {
            return null;
        }
        File versionMarker = new File(canonicalRevision, ".ver_info");
        long score = Math.max(Math.max(canonicalRevision.lastModified(), coreDex.lastModified()),
                Math.max(webView.lastModified(), versionMarker.lastModified()));
        return new Candidate(canonicalRevision, libDirectory.getCanonicalPath(), coreDex,
                isRawDex(coreDex), score);
    }

    /**
     * U4 owns and cleans the {@code dlibs/<revision>} payload directory after it has loaded the
     * native core.  Keep the CAS-generated ZIP outside that lifecycle while retaining it below
     * the same Guest data root.  This makes a raw-Dex deployment restartable after U4 removes
     * {@code core.jar}, without allowing the projection to read outside the instance boundary.
     */
    private static File persistedOverlay(File dataRoot, String revisionName) throws IOException {
        if (!safeRevisionName(revisionName)) return null;
        File u4Root = childDirectory(dataRoot, U4_DIRECTORY);
        if (u4Root == null) return null;
        File store = new File(u4Root, CAS_OVERLAY_DIRECTORY);
        if (Files.isSymbolicLink(store.toPath())
                || !Files.isDirectory(store.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        File revision = new File(store, revisionName);
        if (Files.isSymbolicLink(revision.toPath())
                || !Files.isDirectory(revision.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        File overlay = new File(revision, CORE_OVERLAY);
        if (Files.isSymbolicLink(overlay.toPath())) return null;
        return canonicalContained(dataRoot, overlay);
    }

    private static File createOverlayTarget(File dataRoot, String revisionName) {
        try {
            if (!safeRevisionName(revisionName)) return null;
            File u4Root = childDirectory(dataRoot, U4_DIRECTORY);
            if (u4Root == null) return null;
            File store = new File(u4Root, CAS_OVERLAY_DIRECTORY);
            if (Files.exists(store.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(store.toPath())
                    || !Files.isDirectory(store.toPath(), LinkOption.NOFOLLOW_LINKS))) return null;
            Files.createDirectories(store.toPath());
            File revision = new File(store, revisionName);
            if (Files.exists(revision.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(revision.toPath())
                    || !Files.isDirectory(revision.toPath(), LinkOption.NOFOLLOW_LINKS))) return null;
            Files.createDirectories(revision.toPath());
            File target = new File(revision, CORE_OVERLAY);
            if (Files.isSymbolicLink(target.toPath())) return null;
            return canonicalContained(dataRoot, target);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Candidate selectedCandidate(File dataDir, String nativeAbi,
                                               boolean isolatedProcess) {
        if (isolatedProcess || dataDir == null || nativeAbi == null || nativeAbi.trim().isEmpty()) {
            return null;
        }
        try {
            File dataRoot = canonicalDirectory(dataDir);
            return dataRoot == null ? null : chooseCandidate(dataRoot, nativeAbi.trim());
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Candidate chooseCandidate(File dataRoot, String nativeAbi) throws IOException {
        File dlibs = childDirectory(dataRoot, U4_DIRECTORY, U4_DLIBS_DIRECTORY);
        if (dlibs == null) return null;
        File[] revisions = dlibs.listFiles(File::isDirectory);
        if (revisions == null || revisions.length == 0) return null;
        Arrays.sort(revisions, (left, right) -> left.getName().compareTo(right.getName()));
        Candidate selected = null;
        for (File revision : revisions) {
            Candidate candidate = validateCandidate(dataRoot, revision, nativeAbi);
            if (candidate == null) continue;
            if (selected == null || candidate.score > selected.score
                    || (candidate.score == selected.score
                    && candidate.libraryDirectory.compareTo(selected.libraryDirectory) > 0)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static File childDirectory(File root, String... names) throws IOException {
        File current = root;
        for (String name : names) {
            current = new File(current, name);
            if (Files.isSymbolicLink(current.toPath())
                    || !Files.isDirectory(current.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
            current = canonicalContained(root, current);
            if (current == null) return null;
        }
        return current;
    }

    private static File regularFile(File root, String name, long minimum, long maximum, File dataRoot)
            throws IOException {
        File requested = new File(root, name);
        if (Files.isSymbolicLink(requested.toPath())) return null;
        File file = canonicalContained(dataRoot, requested);
        if (file == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        long length = file.length();
        return length >= minimum && length <= maximum ? file : null;
    }

    private static File canonicalDirectory(File value) throws IOException {
        if (Files.isSymbolicLink(value.toPath())
                || !Files.isDirectory(value.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        File canonical = value.getCanonicalFile();
        return Files.isDirectory(canonical.toPath(), LinkOption.NOFOLLOW_LINKS) ? canonical : null;
    }

    private static File canonicalContained(File root, File value) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File canonicalValue = value.getCanonicalFile();
        String rootPath = canonicalRoot.getPath();
        String valuePath = canonicalValue.getPath();
        if (!valuePath.equals(rootPath)
                && !valuePath.startsWith(rootPath + File.separator)) return null;
        return canonicalValue;
    }

    private static boolean validElf(File file, String nativeAbi) {
        int expectedClass;
        int expectedMachine;
        switch (nativeAbi) {
            case "arm64-v8a": expectedClass = 2; expectedMachine = 183; break;
            case "armeabi-v7a": expectedClass = 1; expectedMachine = 40; break;
            case "x86_64": expectedClass = 2; expectedMachine = 62; break;
            case "x86": expectedClass = 1; expectedMachine = 3; break;
            default: return false;
        }
        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) return false;
                offset += count;
            }
            if ((header[0] & 0xff) != 0x7f || header[1] != 'E'
                    || header[2] != 'L' || header[3] != 'F'
                    || (header[4] & 0xff) != expectedClass || (header[5] & 0xff) != 1
                    || (header[6] & 0xff) != 1) return false;
            int type = (header[16] & 0xff) | ((header[17] & 0xff) << 8);
            int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
            return type == 3 && machine == expectedMachine;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isRawDex(File file) {
        byte[] magic = new byte[4];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(magic) != magic.length) return false;
            return (magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x'
                    && magic[3] == '\n')
                    || (magic[0] == 'c' && magic[1] == 'd' && magic[2] == 'e'
                    && magic[3] == 'x');
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isValidDexSource(File file) {
        return file != null && (isRawDex(file) || isValidDexZip(file));
    }

    private static boolean isValidDexZip(File file) {
        if (file == null) return false;
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("classes.dex");
            if (entry == null || entry.isDirectory() || entry.getSize() <= 4L) return false;
            try (java.io.InputStream input = zip.getInputStream(entry)) {
                byte[] magic = new byte[4];
                if (input.read(magic) != magic.length) return false;
                return (magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x'
                        && magic[3] == '\n')
                        || (magic[0] == 'c' && magic[1] == 'd' && magic[2] == 'e'
                        && magic[3] == 'x');
            }
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void updateChecksum(ByteBuffer buffer, CRC32 checksum) {
        byte[] chunk = new byte[64 * 1024];
        while (buffer.hasRemaining()) {
            int length = Math.min(buffer.remaining(), chunk.length);
            buffer.get(chunk, 0, length);
            checksum.update(chunk, 0, length);
        }
    }

    private static void writeBuffer(ByteBuffer buffer, java.io.OutputStream output) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        while (buffer.hasRemaining()) {
            int length = Math.min(buffer.remaining(), chunk.length);
            buffer.get(chunk, 0, length);
            output.write(chunk, 0, length);
        }
    }

    private static boolean safeRevisionName(String value) {
        if (value == null || value.isEmpty() || value.length() > 128
                || value.equals(".") || value.equals("..")) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == '_' || c == '-' || c == '.'
                    || c >= '0' && c <= '9'
                    || c >= 'A' && c <= 'Z'
                    || c >= 'a' && c <= 'z')) return false;
        }
        return true;
    }

    private static boolean samePath(String left, String right) {
        try {
            return new File(left).getCanonicalFile().equals(new File(right).getCanonicalFile());
        } catch (IOException | RuntimeException ignored) {
            return left.equals(right);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Candidate {
        final File revision;
        final String libraryDirectory;
        final File coreDex;
        final boolean rawDex;
        final long score;

        Candidate(File revision, String libraryDirectory, File coreDex, boolean rawDex, long score) {
            this.revision = revision;
            this.libraryDirectory = libraryDirectory;
            this.coreDex = coreDex;
            this.rawDex = rawDex;
            this.score = score;
        }
    }
}
