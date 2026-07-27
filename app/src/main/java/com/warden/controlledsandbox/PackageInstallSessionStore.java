package com.warden.controlledsandbox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-owned staged install sessions persisted under app-private storage. */
final class PackageInstallSessionStore {
    static final String STATE_OPEN = "OPEN";
    static final String STATE_SEALED = "SEALED";
    private static final long MAX_ARTIFACT_BYTES = 1536L * 1024 * 1024;
    private static final long MAX_INSTALL_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int MAX_ARTIFACTS = 256;
    private final File root;
    private final AtomicInteger nextId = new AtomicInteger((int) (System.currentTimeMillis() & 0x3fffffff));

    PackageInstallSessionStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        root = new File(filesDir, "install-sessions");
    }

    int create(String expectedPackageName) throws Exception {
        ensureRoot();
        String expected = validateExpectedPackage(value(expectedPackageName));
        for (int attempts = 0; attempts < 1000; attempts++) {
            int id = positive(nextId.incrementAndGet());
            File directory = directory(id);
            if (!directory.mkdir()) continue;
            File artifacts = new File(directory, "artifacts");
            if (!artifacts.mkdir()) {
                ApkImportManager.deleteTreeOrThrow(directory);
                throw new IllegalStateException("Cannot create install artifact directory");
            }
            writeState(new Session(id, expected, STATE_OPEN, 0));
            return id;
        }
        throw new IllegalStateException("Cannot allocate install session id");
    }

    String addArtifact(int sessionId, InputStream rawInput) throws Exception {
        if (rawInput == null) throw new IllegalArgumentException("APK artifact stream is unavailable");
        Session session = load(sessionId);
        requireState(session, STATE_OPEN);
        if (session.artifactCount >= MAX_ARTIFACTS) {
            throw new IllegalArgumentException("Install contains too many APK artifacts");
        }
        File artifacts = artifactsDirectory(sessionId);
        long existingBytes = totalArtifactBytes(artifacts);
        if (existingBytes >= MAX_INSTALL_BYTES) throw new IllegalArgumentException("Install set exceeds 3 GiB limit");
        int index = session.artifactCount;
        File target = new File(artifacts, artifactName(index));
        File incoming = new File(artifacts, artifactName(index) + ".incoming");
        try {
            copyLimited(rawInput, incoming, Math.min(MAX_ARTIFACT_BYTES, MAX_INSTALL_BYTES - existingBytes));
            requireZipMagic(incoming);
            moveFile(incoming, target);
            String digest = ApkImportManager.sha256(target);
            try {
                writeState(new Session(session.id, session.expectedPackageName, STATE_OPEN, index + 1));
            } catch (Exception stateFailure) {
                try { Files.deleteIfExists(target.toPath()); }
                catch (Exception cleanupFailure) { stateFailure.addSuppressed(cleanupFailure); }
                throw stateFailure;
            }
            return digest;
        } catch (Exception error) {
            try { Files.deleteIfExists(incoming.toPath()); }
            catch (Exception cleanupFailure) { error.addSuppressed(cleanupFailure); }
            throw error;
        }
    }

    PreparedSession seal(int sessionId) throws Exception {
        Session session = load(sessionId);
        requireState(session, STATE_OPEN);
        if (session.artifactCount < 1) throw new IllegalStateException("Install session contains no artifacts");
        List<File> artifacts = artifactFiles(session);
        Session sealed = new Session(session.id, session.expectedPackageName, STATE_SEALED,
                session.artifactCount);
        writeState(sealed);
        return new PreparedSession(sealed.id, sealed.expectedPackageName, artifacts);
    }

    void reopenAfterFailure(int sessionId) throws Exception {
        Session session = load(sessionId);
        if (STATE_SEALED.equals(session.state)) {
            writeState(new Session(session.id, session.expectedPackageName, STATE_OPEN,
                    session.artifactCount));
        }
    }

    void complete(int sessionId) throws Exception { deleteSession(sessionId); }
    void abandon(int sessionId) throws Exception { deleteSession(sessionId); }

    void sweepStale(long olderThanMillis) throws Exception {
        if (!root.exists()) return;
        ensureRoot();
        long cutoff = System.currentTimeMillis() - Math.max(0, olderThanMillis);
        File[] children = root.listFiles();
        if (children == null) throw new IllegalStateException("Cannot list install sessions");
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                Files.delete(child.toPath());
            } else if (child.isDirectory() && child.lastModified() < cutoff) {
                ApkImportManager.deleteTreeOrThrow(child);
            } else if (!child.isDirectory()) {
                throw new SecurityException("Unexpected install-session root entry: " + child.getName());
            }
        }
    }

    private Session load(int id) throws Exception {
        File directory = requireSessionDirectory(id);
        File state = new File(directory, "session.properties");
        requireRegularFile(state, "INSTALL_SESSION_STATE_INVALID");
        java.util.Properties values = new java.util.Properties();
        try (FileInputStream input = new FileInputStream(state)) { values.load(input); }
        int storedId = parseInt(values.getProperty("id"), "id");
        if (storedId != id) throw new SecurityException("INSTALL_SESSION_ID_MISMATCH");
        String expected = validateExpectedPackage(values.getProperty("expectedPackageName", ""));
        String sessionState = values.getProperty("state", "");
        if (!STATE_OPEN.equals(sessionState) && !STATE_SEALED.equals(sessionState)) {
            throw new SecurityException("INSTALL_SESSION_STATE_INVALID");
        }
        int count = parseInt(values.getProperty("artifactCount"), "artifactCount");
        if (count < 0 || count > MAX_ARTIFACTS) throw new SecurityException("INSTALL_SESSION_COUNT_INVALID");
        return new Session(id, expected, sessionState, count);
    }

    private List<File> artifactFiles(Session session) throws Exception {
        List<File> result = new ArrayList<>();
        File artifacts = artifactsDirectory(session.id);
        File[] entries = artifacts.listFiles();
        if (entries == null) throw new IllegalStateException("Cannot list install artifacts");
        java.util.Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            if (entry.getName().endsWith(".incoming")) {
                throw new SecurityException("INSTALL_SESSION_INCOMPLETE_ARTIFACT");
            }
            requireRegularFile(entry, "INSTALL_SESSION_ARTIFACT_INVALID");
            result.add(entry);
        }
        if (result.size() != session.artifactCount) {
            throw new SecurityException("INSTALL_SESSION_ARTIFACT_COUNT_MISMATCH");
        }
        for (int index = 0; index < result.size(); index++) {
            if (!artifactName(index).equals(result.get(index).getName())) {
                throw new SecurityException("INSTALL_SESSION_ARTIFACT_SEQUENCE_INVALID");
            }
            requireZipMagic(result.get(index));
        }
        if (totalArtifactBytes(artifacts) > MAX_INSTALL_BYTES) {
            throw new SecurityException("INSTALL_SESSION_SIZE_INVALID");
        }
        return result;
    }

    private void writeState(Session session) throws Exception {
        File directory = requireSessionDirectory(session.id);
        File state = new File(directory, "session.properties");
        File temporary = new File(directory, "session.properties.tmp");
        if (Files.isSymbolicLink(state.toPath()) || Files.isSymbolicLink(temporary.toPath())) {
            throw new SecurityException("INSTALL_SESSION_STATE_PATH_INVALID");
        }
        Files.deleteIfExists(temporary.toPath());
        String content = "id=" + session.id + "\n"
                + "expectedPackageName=" + session.expectedPackageName + "\n"
                + "state=" + session.state + "\n"
                + "artifactCount=" + session.artifactCount + "\n";
        try (FileOutputStream file = new FileOutputStream(temporary);
             BufferedOutputStream output = new BufferedOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush(); file.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), state.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), state.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        directory.setLastModified(System.currentTimeMillis());
    }

    private void ensureRoot() throws Exception {
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("Cannot create install session root");
        }
        if (Files.isSymbolicLink(root.toPath())) {
            throw new SecurityException("INSTALL_SESSION_ROOT_IS_SYMBOLIC_LINK");
        }
    }

    private File requireSessionDirectory(int id) throws Exception {
        ensureRoot();
        File directory = directory(id);
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory()) {
            throw new IllegalArgumentException("Install session does not exist: " + id);
        }
        return directory;
    }

    private File artifactsDirectory(int id) throws Exception {
        File artifacts = new File(requireSessionDirectory(id), "artifacts");
        if (Files.isSymbolicLink(artifacts.toPath()) || !artifacts.isDirectory()) {
            throw new SecurityException("INSTALL_SESSION_ARTIFACT_DIRECTORY_INVALID");
        }
        return artifacts;
    }

    private void deleteSession(int id) throws Exception {
        File directory = requireSessionDirectory(id);
        ApkImportManager.deleteTreeOrThrow(directory);
    }

    private File directory(int id) {
        if (id <= 0) throw new IllegalArgumentException("sessionId must be positive");
        return new File(root, Integer.toString(id));
    }

    private static void requireRegularFile(File file, String code) {
        if (Files.isSymbolicLink(file.toPath()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException(code + ": " + file.getName());
        }
    }

    private static long totalArtifactBytes(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".apk"));
        if (files == null) throw new IllegalStateException("Cannot list install artifacts");
        long total = 0;
        for (File file : files) {
            total = Math.addExact(total, file.length());
            if (total > MAX_INSTALL_BYTES) return total;
        }
        return total;
    }

    private static void copyLimited(InputStream rawInput, File destination, long limit) throws Exception {
        if (limit <= 0) throw new IllegalArgumentException("Install set exceeds 3 GiB limit");
        long total = 0;
        try (InputStream input = rawInput;
             BufferedInputStream buffered = new BufferedInputStream(input);
             FileOutputStream file = new FileOutputStream(destination);
             BufferedOutputStream output = new BufferedOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = buffered.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IllegalArgumentException("APK artifact exceeds install size limit");
                output.write(buffer, 0, count);
            }
            output.flush(); file.getFD().sync();
        }
        if (total < 4) throw new IllegalArgumentException("APK artifact is empty");
    }

    private static void requireZipMagic(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read() != 'P' || input.read() != 'K') {
                throw new IllegalArgumentException("Install artifact is not a ZIP/APK");
            }
        }
    }

    private static void moveFile(File source, File destination) throws Exception {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source.toPath(), destination.toPath());
        }
    }

    private static String artifactName(int index) {
        return String.format(java.util.Locale.ROOT, "artifact-%03d.apk", index);
    }

    private static int parseInt(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (Exception error) { throw new SecurityException("INSTALL_SESSION_" + name.toUpperCase() + "_INVALID", error); }
    }
    private static void requireState(Session session, String expected) {
        if (!expected.equals(session.state)) {
            throw new IllegalStateException("Install session is " + session.state + ", expected " + expected);
        }
    }
    private static int positive(int value) { return value <= 0 ? value & 0x7fffffff | 1 : value; }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static String validateExpectedPackage(String value) {
        String normalized = value(value);
        if (!normalized.isEmpty() && !normalized.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("expectedPackageName is invalid");
        }
        return normalized;
    }

    static final class PreparedSession {
        final int id; final String expectedPackageName; final List<File> artifacts;
        PreparedSession(int id, String expectedPackageName, List<File> artifacts) {
            this.id = id; this.expectedPackageName = expectedPackageName;
            this.artifacts = List.copyOf(artifacts);
        }
    }

    private static final class Session {
        final int id; final String expectedPackageName; final String state; final int artifactCount;
        Session(int id, String expectedPackageName, String state, int artifactCount) {
            this.id = id; this.expectedPackageName = expectedPackageName;
            this.state = state; this.artifactCount = artifactCount;
        }
    }
}
