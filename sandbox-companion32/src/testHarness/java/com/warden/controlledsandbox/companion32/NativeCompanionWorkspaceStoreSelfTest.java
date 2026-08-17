package com.warden.controlledsandbox.companion32;

import com.warden.controlledsandbox.contract.NativeCompanionArtifactResult;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Verifies package/user scoped Companion workspace reclamation across revisions. */
public final class NativeCompanionWorkspaceStoreSelfTest {
    private NativeCompanionWorkspaceStoreSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("companion-workspace-lifecycle");
        try {
            NativeCompanionWorkspaceStore store =
                    new NativeCompanionWorkspaceStore(root.toFile());
            NativeCompanionArtifactResult oldRevision = store.prepare(
                    request("com.example.fixture", 0, "rev-old", "x86", 1));
            NativeCompanionArtifactResult currentRevision = store.prepare(
                    request("com.example.fixture", 0, "rev-current", "x86", 2));
            NativeCompanionArtifactResult otherUser = store.prepare(
                    request("com.example.fixture", 1, "rev-old", "x86", 3));
            NativeCompanionArtifactResult otherPackage = store.prepare(
                    request("com.example.other", 0, "rev-old", "x86", 4));

            Files.write(Path.of(oldRevision.dataRoot(), "old-state.bin"),
                    "old".getBytes(StandardCharsets.UTF_8));
            Files.write(Path.of(currentRevision.dataRoot(), "current-state.bin"),
                    "current".getBytes(StandardCharsets.UTF_8));
            Files.write(Path.of(otherUser.dataRoot(), "other-user.bin"),
                    "other-user".getBytes(StandardCharsets.UTF_8));
            Files.write(Path.of(otherPackage.dataRoot(), "other-package.bin"),
                    "other-package".getBytes(StandardCharsets.UTF_8));

            NativeCompanionArtifactResult cleared = store.clear(
                    request("com.example.fixture", 0, "rev-current", "x86", 5,
                            NativeCompanionRequest.OP_CLEAR_GENERATION));
            require(cleared.successful(), "package/user clear failed");
            require(!Files.exists(Path.of(oldRevision.workspaceRoot())),
                    "old revision workspace survived clear");
            require(!Files.exists(Path.of(currentRevision.workspaceRoot())),
                    "current revision workspace survived clear");
            require(Files.exists(Path.of(otherUser.dataRoot(), "other-user.bin")),
                    "clear crossed virtual user boundary");
            require(Files.exists(Path.of(otherPackage.dataRoot(), "other-package.bin")),
                    "clear crossed package boundary");
            System.out.println("PASS native companion package-user workspace lifecycle");
        } finally {
            if (Files.exists(root)) {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static NativeCompanionRequest request(String packageName, int userId,
                                                   String revision, String abi, int seed) {
        return request(packageName, userId, revision, abi, seed,
                NativeCompanionRequest.OP_PREPARE_GENERATION);
    }

    private static NativeCompanionRequest request(String packageName, int userId,
                                                   String revision, String abi, int seed,
                                                   String operation) {
        byte[] nonce = new byte[32];
        for (int index = 0; index < nonce.length; index++) nonce[index] = (byte) (seed + index);
        return new NativeCompanionRequest(1, "workspace-session-" + seed, 1L, userId,
                packageName, revision, nonce, abi, operation);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
