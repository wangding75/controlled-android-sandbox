package com.warden.controlledsandbox.companion32;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactResult;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.ControlledReleaseIdentity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, revision-scoped companion-private artifact workspace. */
final class NativeCompanionWorkspaceStore {
    private static final int MAX_WORKSPACES = 64;
    private static final int MAX_ARTIFACTS_PER_WORKSPACE = 512;
    private static final long MAX_WORKSPACE_BYTES = 1024L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 64 * 1024;

    private final File root;
    private final Map<String, WorkspaceState> workspaces = new LinkedHashMap<>();

    NativeCompanionWorkspaceStore(File filesDir) {
        root = new File(filesDir, "companion-runtime");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("COMPANION_WORKSPACE_ROOT_CREATE_FAILED");
        }
    }

    synchronized NativeCompanionArtifactResult prepare(NativeCompanionRequest request) {
        try {
            requirePrepare(request);
            WorkspaceState state = requireWorkspace(request);
            ensureDirectory(state.workspaceRoot);
            ensureDirectory(state.dataRoot);
            ensureDirectory(state.nativeLibraryRoot);
            ensureDirectory(new File(state.workspaceRoot, "splits"));
            return result("PREPARE_WORKSPACE", "", "", "", state);
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
            return failure("PREPARE_WORKSPACE", error);
        }
    }

    synchronized NativeCompanionArtifactResult stage(NativeCompanionArtifactRequest request,
            ParcelFileDescriptor source) {
        if (request == null) return NativeCompanionArtifactResult.failure(
                "STAGE_ARTIFACT", "REQUEST_REQUIRED", "request is required");
        if (source == null) return NativeCompanionArtifactResult.failure(
                "STAGE_ARTIFACT", "SOURCE_REQUIRED", "source is required");
        try {
            requireProtocol(request.protocol());
            WorkspaceState state = requireWorkspace(request);
            if (state.artifacts >= MAX_ARTIFACTS_PER_WORKSPACE
                    && !new File(state.workspaceRoot, request.relativePath()).isFile()) {
                throw new IllegalStateException("COMPANION_ARTIFACT_COUNT_LIMIT_EXCEEDED");
            }
            if (request.sizeBytes() > MAX_WORKSPACE_BYTES - state.bytes) {
                throw new IllegalStateException("COMPANION_WORKSPACE_SIZE_LIMIT_EXCEEDED");
            }
            File target = safeTarget(state.workspaceRoot, request.relativePath());
            ensureDirectory(target.getParentFile());
            File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
            long copied;
            String digest;
            try (InputStream input = new FileInputStream(source.getFileDescriptor());
                 FileOutputStream output = new FileOutputStream(temporary, false)) {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[BUFFER_BYTES];
                copied = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    copied = Math.addExact(copied, read);
                    if (copied > request.sizeBytes() || copied > MAX_WORKSPACE_BYTES) {
                        throw new IllegalStateException("COMPANION_ARTIFACT_SIZE_EXCEEDED");
                    }
                    sha256.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
                digest = hex(sha256.digest());
            } finally {
                try { source.close(); } catch (IOException ignored) { }
            }
            if (copied != request.sizeBytes()) {
                deleteQuietly(temporary);
                throw new SecurityException("COMPANION_ARTIFACT_SIZE_MISMATCH");
            }
            if (!digest.equals(request.sha256())) {
                deleteQuietly(temporary);
                throw new SecurityException("COMPANION_ARTIFACT_HASH_MISMATCH");
            }
            long previous = target.isFile() ? target.length() : 0L;
            atomicReplace(temporary, target);
            state.bytes = Math.addExact(Math.max(0L, state.bytes - previous), copied);
            if (previous == 0L) state.artifacts++;
            return result("STAGE_ARTIFACT", request.artifactKind(), request.relativePath(),
                    target.getCanonicalPath(), state);
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
            return failure("STAGE_ARTIFACT", error);
        }
    }

    synchronized NativeCompanionArtifactResult clear(NativeCompanionRequest request) {
        try {
            requireProtocol(request == null ? 0 : request.protocol());
            if (request == null || !NativeCompanionRequest.OP_CLEAR_GENERATION.equals(request.operation())) {
                throw new IllegalArgumentException("CLEAR_GENERATION_REQUEST_REQUIRED");
            }
            String key = key(request.packageName(), request.virtualUserId(), request.packageRevision(),
                    request.requestedAbi());
            WorkspaceState state = workspaces.remove(key);
            if (state == null) state = workspace(request.packageName(), request.virtualUserId(),
                    request.packageRevision(), request.requestedAbi());
            deleteTree(state.workspaceRoot);
            return result("CLEAR_WORKSPACE", "", "", "", state);
        } catch (Throwable error) {
            FatalErrorPolicy.rethrowIfFatal(error);
            return failure("CLEAR_WORKSPACE", error);
        }
    }

    private WorkspaceState requireWorkspace(NativeCompanionRequest request) throws IOException {
        return requireWorkspace(request.packageName(), request.virtualUserId(), request.packageRevision(),
                request.requestedAbi());
    }

    private WorkspaceState requireWorkspace(NativeCompanionArtifactRequest request) throws IOException {
        return requireWorkspace(request.packageName(), request.virtualUserId(), request.packageRevision(),
                request.requestedAbi());
    }

    private WorkspaceState requireWorkspace(String packageName, int virtualUserId,
            String revision, String abi) throws IOException {
        String key = key(packageName, virtualUserId, revision, abi);
        WorkspaceState state = workspaces.get(key);
        if (state != null) return state;
        if (workspaces.size() >= MAX_WORKSPACES) {
            Map.Entry<String, WorkspaceState> oldest = workspaces.entrySet().iterator().next();
            workspaces.remove(oldest.getKey());
            deleteTree(oldest.getValue().workspaceRoot);
        }
        state = workspace(packageName, virtualUserId, revision, abi);
        ensureDirectory(state.workspaceRoot);
        ensureDirectory(state.dataRoot);
        ensureDirectory(state.nativeLibraryRoot);
        state.recount();
        workspaces.put(key, state);
        return state;
    }

    private WorkspaceState workspace(String packageName, int virtualUserId,
            String revision, String abi) throws IOException {
        File packageRoot = new File(root, safe(packageName));
        File userRoot = new File(packageRoot, "u" + virtualUserId);
        File workspace = new File(new File(userRoot, safe(revision)), safe(abi)).getCanonicalFile();
        File canonicalRoot = root.getCanonicalFile();
        if (!workspace.toPath().startsWith(canonicalRoot.toPath())) {
            throw new SecurityException("COMPANION_WORKSPACE_TRAVERSAL");
        }
        return new WorkspaceState(workspace, new File(workspace, "data"),
                new File(workspace, "lib"));
    }

    private static NativeCompanionArtifactResult result(String operation, String kind,
            String relativePath, String absolutePath, WorkspaceState state) throws IOException {
        return NativeCompanionArtifactResult.success(operation, kind, relativePath, absolutePath,
                state.workspaceRoot.getCanonicalPath(), state.dataRoot.getCanonicalPath(),
                state.nativeLibraryRoot.getCanonicalPath());
    }

    private static NativeCompanionArtifactResult failure(String operation, Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return NativeCompanionArtifactResult.failure(operation, root.getClass().getSimpleName(),
                String.valueOf(root.getMessage()));
    }

    private static void requirePrepare(NativeCompanionRequest request) {
        requireProtocol(request == null ? 0 : request.protocol());
        if (request == null || !NativeCompanionRequest.OP_PREPARE_GENERATION.equals(request.operation())) {
            throw new IllegalArgumentException("PREPARE_GENERATION_REQUEST_REQUIRED");
        }
    }

    private static void requireProtocol(int protocol) {
        if (protocol != ControlledReleaseIdentity.COMPANION_PROTOCOL) {
            throw new SecurityException("NATIVE_COMPANION_PROTOCOL_MISMATCH");
        }
    }

    private static File safeTarget(File workspaceRoot, String relativePath) throws IOException {
        File target = new File(workspaceRoot, relativePath).getCanonicalFile();
        if (!target.toPath().startsWith(workspaceRoot.getCanonicalFile().toPath())) {
            throw new SecurityException("COMPANION_ARTIFACT_PATH_TRAVERSAL");
        }
        return target;
    }

    private static void ensureDirectory(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory is required");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("COMPANION_DIRECTORY_CREATE_FAILED:" + directory);
        }
    }

    private static void atomicReplace(File source, File target) throws IOException {
        DurableAtomicFile.replacePreparedAcknowledged(source.toPath(), target.toPath());
    }

    private static void deleteTree(File value) throws IOException {
        if (value == null || !value.exists()) return;
        File[] children = value.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!value.delete() && value.exists()) throw new IOException("cannot delete " + value);
    }

    private static void deleteQuietly(File value) {
        if (value != null) try { Files.deleteIfExists(value.toPath()); } catch (IOException ignored) { }
    }

    private static String key(String packageName, int userId, String revision, String abi) {
        return packageName + "\n" + userId + "\n" + revision + "\n" + abi;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static final class WorkspaceState {
        final File workspaceRoot;
        final File dataRoot;
        final File nativeLibraryRoot;
        int artifacts;
        long bytes;

        WorkspaceState(File workspaceRoot, File dataRoot, File nativeLibraryRoot) {
            this.workspaceRoot = workspaceRoot;
            this.dataRoot = dataRoot;
            this.nativeLibraryRoot = nativeLibraryRoot;
        }

        void recount() {
            artifacts = 0;
            bytes = 0L;
            recount(workspaceRoot);
        }

        private void recount(File file) {
            if (file == null || !file.exists()) return;
            if (file.isFile()) {
                artifacts++;
                bytes = Math.addExact(bytes, file.length());
                return;
            }
            File[] children = file.listFiles();
            if (children != null) for (File child : children) recount(child);
        }
    }
}
