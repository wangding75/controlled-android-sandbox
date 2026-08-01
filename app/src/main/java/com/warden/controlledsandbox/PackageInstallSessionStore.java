package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-owned, persisted PackageInstaller-style sessions under app-private storage. */
final class PackageInstallSessionStore {
    static final String STATE_OPEN = InstallSessionInfoSnapshot.STATE_OPEN;
    static final String STATE_SEALED = InstallSessionInfoSnapshot.STATE_SEALED;
    static final String STATE_COMMITTING = InstallSessionInfoSnapshot.STATE_COMMITTING;
    static final String STATE_FAILED = InstallSessionInfoSnapshot.STATE_FAILED;
    private static final int STATE_SCHEMA = 3;
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
        return create(InstallSessionParamsSnapshot.fullInstall(expectedPackageName));
    }

    int create(InstallSessionParamsSnapshot rawParams) throws Exception {
        ensureRoot();
        InstallSessionParamsSnapshot params = normalizeParams(rawParams);
        for (int attempts = 0; attempts < 1000; attempts++) {
            int id = positive(nextId.incrementAndGet());
            File directory = directory(id);
            if (!directory.mkdir()) continue;
            File artifacts = new File(directory, "artifacts");
            if (!artifacts.mkdir()) {
                ApkImportManager.deleteTreeOrThrow(directory);
                throw new IllegalStateException("Cannot create install artifact directory");
            }
            long now = System.currentTimeMillis();
            writeState(new Session(id, STATE_OPEN, params, 0, 0L, 0F,
                    now, now, 0, "", ""));
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
        if (existingBytes != session.bytesStaged) {
            throw new SecurityException("INSTALL_SESSION_BYTE_COUNT_MISMATCH");
        }
        if (existingBytes >= MAX_INSTALL_BYTES) throw new IllegalArgumentException("Install set exceeds 3 GiB limit");
        int index = session.artifactCount;
        File target = new File(artifacts, artifactName(index));
        File incoming = new File(artifacts, artifactName(index) + ".incoming");
        try {
            copyLimited(rawInput, incoming, Math.min(MAX_ARTIFACT_BYTES, MAX_INSTALL_BYTES - existingBytes));
            requireZipMagic(incoming);
            moveFile(incoming, target);
            String digest = ApkImportManager.sha256(target);
            long bytes = Math.addExact(existingBytes, target.length());
            float progress = inferredProgress(session.params, bytes, session.progress);
            try {
                writeState(session.withState(STATE_OPEN, index + 1, bytes, progress,
                        session.attemptCount, "", ""));
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
        Session sealed = session.withState(STATE_SEALED, session.artifactCount,
                session.bytesStaged, Math.max(session.progress, 0.90F),
                session.attemptCount + 1, "", "");
        writeState(sealed);
        return new PreparedSession(sealed.id, sealed.params, artifacts);
    }

    void markCommitting(int sessionId) throws Exception {
        Session session = load(sessionId);
        requireState(session, STATE_SEALED);
        writeState(session.withState(STATE_COMMITTING, session.artifactCount,
                session.bytesStaged, Math.max(session.progress, 0.95F),
                session.attemptCount, "", ""));
    }

    void markFailed(int sessionId, String code, String message) throws Exception {
        Session session = load(sessionId);
        if (!STATE_SEALED.equals(session.state) && !STATE_COMMITTING.equals(session.state)) {
            throw new IllegalStateException("Install session is " + session.state
                    + ", expected SEALED or COMMITTING");
        }
        String normalizedCode = bounded(code, "failureCode", 128);
        String normalizedMessage = bounded(message, "failureMessage", 2048);
        if (normalizedCode.isEmpty()) normalizedCode = "INSTALL_SESSION_COMMIT_FAILED";
        writeState(session.withState(STATE_FAILED, session.artifactCount,
                session.bytesStaged, session.progress, session.attemptCount,
                normalizedCode, normalizedMessage));
    }

    InstallSessionInfoSnapshot retry(int sessionId) throws Exception {
        Session session = load(sessionId);
        requireState(session, STATE_FAILED);
        Session open = session.withState(STATE_OPEN, session.artifactCount,
                session.bytesStaged, Math.min(session.progress, 0.89F),
                session.attemptCount, "", "");
        writeState(open);
        return open.info();
    }

    /** Legacy compatibility for pre-M5-T7 callers; new code should retain FAILED evidence and call retry(). */
    void reopenAfterFailure(int sessionId) throws Exception {
        Session session = load(sessionId);
        if (STATE_FAILED.equals(session.state)) {
            retry(sessionId);
        } else if (STATE_SEALED.equals(session.state) || STATE_COMMITTING.equals(session.state)) {
            Session open = session.withState(STATE_OPEN, session.artifactCount,
                    session.bytesStaged, Math.min(session.progress, 0.89F),
                    session.attemptCount, "", "");
            writeState(open);
        }
    }

    InstallSessionInfoSnapshot setProgress(int sessionId, float progress) throws Exception {
        if (Float.isNaN(progress) || progress < 0F || progress > 1F) {
            throw new IllegalArgumentException("progress is invalid");
        }
        Session session = load(sessionId);
        if (!STATE_OPEN.equals(session.state) && !STATE_SEALED.equals(session.state)) {
            throw new IllegalStateException("Install session progress cannot change in " + session.state);
        }
        Session next = session.withState(session.state, session.artifactCount,
                session.bytesStaged, progress, session.attemptCount, "", "");
        writeState(next);
        return next.info();
    }

    InstallSessionInfoSnapshot info(int sessionId) throws Exception { return load(sessionId).info(); }

    List<InstallSessionInfoSnapshot> list() throws Exception {
        if (!root.exists()) return List.of();
        ensureRoot();
        File[] children = root.listFiles();
        if (children == null) throw new IllegalStateException("Cannot list install sessions");
        List<InstallSessionInfoSnapshot> result = new ArrayList<>();
        java.util.Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath()) || !child.isDirectory()) {
                throw new SecurityException("Unexpected install-session root entry: " + child.getName());
            }
            int id = parseInt(child.getName(), "directoryId");
            result.add(load(id).info());
        }
        result.sort(Comparator.comparingInt(InstallSessionInfoSnapshot::sessionId));
        return List.copyOf(result);
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
        Properties values = new Properties();
        try (FileInputStream input = new FileInputStream(state)) { values.load(input); }
        int storedId = parseInt(values.getProperty("id"), "id");
        if (storedId != id) throw new SecurityException("INSTALL_SESSION_ID_MISMATCH");
        int schema = parseIntDefault(values.getProperty("schema"), 1, "schema");
        if (schema < 1 || schema > STATE_SCHEMA) {
            throw new SecurityException("INSTALL_SESSION_SCHEMA_UNSUPPORTED");
        }
        String sessionState = values.getProperty("state", "");
        requireKnownState(sessionState);
        int count = parseInt(values.getProperty("artifactCount"), "artifactCount");
        if (count < 0 || count > MAX_ARTIFACTS) throw new SecurityException("INSTALL_SESSION_COUNT_INVALID");

        InstallSessionParamsSnapshot params;
        long bytes;
        float progress;
        long createdAt;
        long updatedAt;
        int attempts;
        String failureCode;
        String failureMessage;
        if (schema == 1) {
            params = InstallSessionParamsSnapshot.fullInstall(validateExpectedPackage(
                    values.getProperty("expectedPackageName", "")));
            bytes = totalArtifactBytes(artifactsDirectory(id));
            progress = inferredProgress(params, bytes, 0F);
            createdAt = Math.max(1L, directory.lastModified());
            updatedAt = createdAt;
            attempts = STATE_OPEN.equals(sessionState) ? 0 : 1;
            failureCode = "";
            failureMessage = "";
        } else {
            params = new InstallSessionParamsSnapshot(
                    values.getProperty("mode", InstallSessionParamsSnapshot.MODE_FULL),
                    validateExpectedPackage(decode(values.getProperty("expectedPackageNameB64", ""))),
                    decode(values.getProperty("installerPackageNameB64", "")),
                    decode(values.getProperty("appLabelB64", "")),
                    parseLong(values.getProperty("sizeBytes"), "sizeBytes"),
                    parseInt(values.getProperty("installFlags"), "installFlags"),
                    parseBoolean(values.getProperty("rollbackEnabled"), "rollbackEnabled"),
                    values.getProperty("requireUserAction",
                            InstallSessionParamsSnapshot.USER_ACTION_UNSPECIFIED),
                    schema >= 3 ? values.getProperty("nativeGuestTrust",
                            InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED)
                            : InstallSessionParamsSnapshot.NATIVE_GUEST_TRUST_UNTRUSTED);
            bytes = parseLong(values.getProperty("bytesStaged"), "bytesStaged");
            progress = Float.intBitsToFloat(parseInt(values.getProperty("progressBits"), "progressBits"));
            createdAt = parseLong(values.getProperty("createdAt"), "createdAt");
            updatedAt = parseLong(values.getProperty("updatedAt"), "updatedAt");
            attempts = parseInt(values.getProperty("attemptCount"), "attemptCount");
            failureCode = decode(values.getProperty("failureCodeB64", ""));
            failureMessage = decode(values.getProperty("failureMessageB64", ""));
        }
        Session session = new Session(id, sessionState, params, count, bytes, progress,
                createdAt, updatedAt, attempts, failureCode, failureMessage);
        session.info();
        if (totalArtifactBytes(artifactsDirectory(id)) != bytes) {
            throw new SecurityException("INSTALL_SESSION_BYTE_COUNT_MISMATCH");
        }
        return session;
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
        long bytes = totalArtifactBytes(artifacts);
        if (bytes > MAX_INSTALL_BYTES) throw new SecurityException("INSTALL_SESSION_SIZE_INVALID");
        if (bytes != session.bytesStaged) throw new SecurityException("INSTALL_SESSION_BYTE_COUNT_MISMATCH");
        return result;
    }

    private void writeState(Session session) throws Exception {
        session.info();
        File directory = requireSessionDirectory(session.id);
        File state = new File(directory, "session.properties");
        File temporary = new File(directory, "session.properties.tmp");
        if (Files.isSymbolicLink(state.toPath()) || Files.isSymbolicLink(temporary.toPath())) {
            throw new SecurityException("INSTALL_SESSION_STATE_PATH_INVALID");
        }
        Files.deleteIfExists(temporary.toPath());
        String content = "schema=" + STATE_SCHEMA + "\n"
                + "id=" + session.id + "\n"
                + "state=" + session.state + "\n"
                + "mode=" + session.params.mode() + "\n"
                + "expectedPackageNameB64=" + encode(session.params.expectedPackageName()) + "\n"
                + "installerPackageNameB64=" + encode(session.params.installerPackageName()) + "\n"
                + "appLabelB64=" + encode(session.params.appLabel()) + "\n"
                + "sizeBytes=" + session.params.sizeBytes() + "\n"
                + "installFlags=" + session.params.installFlags() + "\n"
                + "rollbackEnabled=" + session.params.rollbackEnabled() + "\n"
                + "requireUserAction=" + session.params.requireUserAction() + "\n"
                + "nativeGuestTrust=" + session.params.nativeGuestTrust() + "\n"
                + "artifactCount=" + session.artifactCount + "\n"
                + "bytesStaged=" + session.bytesStaged + "\n"
                + "progressBits=" + Float.floatToIntBits(session.progress) + "\n"
                + "createdAt=" + session.createdAt + "\n"
                + "updatedAt=" + session.updatedAt + "\n"
                + "attemptCount=" + session.attemptCount + "\n"
                + "failureCodeB64=" + encode(session.failureCode) + "\n"
                + "failureMessageB64=" + encode(session.failureMessage) + "\n";
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
        directory.setLastModified(session.updatedAt);
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
        ApkImportManager.deleteTreeOrThrow(requireSessionDirectory(id));
    }

    private File directory(int id) {
        if (id <= 0) throw new IllegalArgumentException("sessionId must be positive");
        return new File(root, Integer.toString(id));
    }

    private static void requireRegularFile(File file, String code) {
        if (Files.isSymbolicLink(file.toPath())
                || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
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
        return String.format(Locale.ROOT, "artifact-%03d.apk", index);
    }

    private static InstallSessionParamsSnapshot normalizeParams(InstallSessionParamsSnapshot params) {
        if (params == null) throw new IllegalArgumentException("install session params are required");
        return new InstallSessionParamsSnapshot(params.mode(),
                validateExpectedPackage(params.expectedPackageName()),
                params.installerPackageName(), params.appLabel(), params.sizeBytes(),
                params.installFlags(), params.rollbackEnabled(), params.requireUserAction(),
                params.nativeGuestTrust());
    }

    private static float inferredProgress(InstallSessionParamsSnapshot params, long bytes, float current) {
        if (params.sizeBytes() <= 0L) return current;
        float staged = Math.min(0.89F, (float) bytes / (float) params.sizeBytes());
        return Math.max(current, staged);
    }

    private static void requireKnownState(String state) {
        if (!STATE_OPEN.equals(state) && !STATE_SEALED.equals(state)
                && !STATE_COMMITTING.equals(state) && !STATE_FAILED.equals(state)) {
            throw new SecurityException("INSTALL_SESSION_STATE_INVALID");
        }
    }

    private static int parseInt(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (Exception error) {
            throw new SecurityException("INSTALL_SESSION_" + name.toUpperCase(Locale.ROOT)
                    + "_INVALID", error);
        }
    }

    private static int parseIntDefault(String value, int fallback, String name) {
        return value == null || value.isEmpty() ? fallback : parseInt(value, name);
    }

    private static long parseLong(String value, String name) {
        try { return Long.parseLong(value); }
        catch (Exception error) {
            throw new SecurityException("INSTALL_SESSION_" + name.toUpperCase(Locale.ROOT)
                    + "_INVALID", error);
        }
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new SecurityException("INSTALL_SESSION_" + name.toUpperCase(Locale.ROOT) + "_INVALID");
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

    private static String bounded(String value, String name, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            if (value == null || value.isEmpty()) return "";
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new SecurityException("INSTALL_SESSION_TEXT_ENCODING_INVALID", invalid);
        }
    }

    static final class PreparedSession {
        final int id;
        final String expectedPackageName;
        final InstallSessionParamsSnapshot params;
        final List<File> artifacts;

        PreparedSession(int id, InstallSessionParamsSnapshot params, List<File> artifacts) {
            this.id = id;
            this.params = params;
            this.expectedPackageName = params.expectedPackageName();
            this.artifacts = List.copyOf(artifacts);
        }
    }

    private static final class Session {
        final int id;
        final String state;
        final InstallSessionParamsSnapshot params;
        final int artifactCount;
        final long bytesStaged;
        final float progress;
        final long createdAt;
        final long updatedAt;
        final int attemptCount;
        final String failureCode;
        final String failureMessage;

        Session(int id, String state, InstallSessionParamsSnapshot params,
                int artifactCount, long bytesStaged, float progress,
                long createdAt, long updatedAt, int attemptCount,
                String failureCode, String failureMessage) {
            this.id = id;
            this.state = state;
            this.params = params;
            this.artifactCount = artifactCount;
            this.bytesStaged = bytesStaged;
            this.progress = progress;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.attemptCount = attemptCount;
            this.failureCode = failureCode == null ? "" : failureCode;
            this.failureMessage = failureMessage == null ? "" : failureMessage;
        }

        Session withState(String nextState, int count, long bytes, float nextProgress,
                          int attempts, String code, String message) {
            long now = Math.max(createdAt, System.currentTimeMillis());
            return new Session(id, nextState, params, count, bytes, nextProgress,
                    createdAt, now, attempts, code, message);
        }

        InstallSessionInfoSnapshot info() {
            return new InstallSessionInfoSnapshot(id, state, params, artifactCount,
                    bytesStaged, progress, createdAt, updatedAt, attemptCount,
                    failureCode, failureMessage);
        }
    }
}
