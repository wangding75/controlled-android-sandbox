package com.warden.controlledsandbox.domain.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DurableAtomicFileSelfTest {
    public static void main(String[] args) throws Exception {
        run();
        System.out.println("PASS durable atomic file self-test");
    }

    public static void run() throws Exception {
        testActualDurableReplace();
        testOrdering();
        testNoNonAtomicFallback();
        testDirectorySyncFailureReturnsCommittedUncertain();
        testMoveDirectorySyncFailureReturnsCommittedUncertain();
    }

    private static void testActualDurableReplace() throws Exception {
        Path root = Files.createTempDirectory("durable-atomic-");
        try {
            Path file = root.resolve("state.json");
            DurableAtomicFile.write(file, "one".getBytes(StandardCharsets.UTF_8));
            DurableAtomicFile.write(file, "two".getBytes(StandardCharsets.UTF_8));
            require("two".equals(Files.readString(file)), "actual atomic replace");
            require(Files.list(root).noneMatch(path -> path.getFileName().toString().contains(".tmp-")),
                    "temporary cleanup");
        } finally {
            deleteTree(root);
        }
    }

    private static void testOrdering() throws Exception {
        Path root = Files.createTempDirectory("durable-order-");
        try {
            Path temporary = root.resolve("state.tmp");
            Path destination = root.resolve("state");
            Files.writeString(temporary, "value");
            List<String> events = new ArrayList<>();
            DurableAtomicFile.replacePrepared(temporary, destination, new DurableAtomicFile.Operations() {
                @Override public void forceFile(Path file) { events.add("file"); }
                @Override public void atomicReplace(Path source, Path target) throws IOException {
                    events.add("move");
                    Files.move(source, target);
                }
                @Override public void forceDirectory(Path directory) { events.add("directory"); }
            });
            require(events.equals(List.of("file", "move", "directory")), "durability order");
        } finally {
            deleteTree(root);
        }
    }

    private static void testNoNonAtomicFallback() throws Exception {
        Path root = Files.createTempDirectory("durable-unsupported-");
        try {
            Path temporary = root.resolve("state.tmp");
            Path destination = root.resolve("state");
            Files.writeString(temporary, "new");
            Files.writeString(destination, "old");
            boolean rejected = false;
            try {
                DurableAtomicFile.replacePrepared(temporary, destination, new DurableAtomicFile.Operations() {
                    @Override public void forceFile(Path file) { }
                    @Override public void atomicReplace(Path source, Path target)
                            throws AtomicMoveNotSupportedException {
                        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                    }
                    @Override public void forceDirectory(Path directory) {
                        throw new AssertionError("directory sync must not run after failed move");
                    }
                });
            } catch (IOException expected) {
                rejected = expected.getMessage().startsWith(DurableAtomicFile.ATOMIC_MOVE_UNSUPPORTED);
            }
            require(rejected, "unsupported atomic move rejected");
            require("old".equals(Files.readString(destination)), "old destination retained");
            require(Files.exists(temporary), "prepared source retained for caller cleanup");
        } finally {
            deleteTree(root);
        }
    }

    private static void testDirectorySyncFailureReturnsCommittedUncertain() throws Exception {
        Path root = Files.createTempDirectory("durable-dir-fail-");
        try {
            Path temporary = root.resolve("state.tmp");
            Path destination = root.resolve("state");
            Files.writeString(temporary, "new");
            DurableAtomicFile.CommitResult result = DurableAtomicFile.replacePrepared(
                    temporary, destination, new DurableAtomicFile.Operations() {
                        @Override public void forceFile(Path file) { }
                        @Override public void atomicReplace(Path source, Path target) throws IOException {
                            Files.move(source, target);
                        }
                        @Override public void forceDirectory(Path directory) throws IOException {
                            throw new IOException(DurableAtomicFile.DIRECTORY_SYNC_FAILED);
                        }
                    });
            require(result.state()
                            == DurableAtomicFile.CommitState.POST_RENAME_DURABILITY_UNCERTAIN,
                    "directory fsync failure did not return post-rename state");
            require(result.committed(), "post-rename result was not committed");
            require(!result.durabilityConfirmed(), "failed directory sync reported durable");
            require(result.durabilityFailure() != null
                            && result.durabilityFailure().getMessage()
                            .contains(DurableAtomicFile.DIRECTORY_SYNC_FAILED),
                    "directory fsync failure detail missing");
            require("new".equals(Files.readString(destination)),
                    "rename did not remain committed after durability uncertainty");
        } finally {
            deleteTree(root);
        }
    }


    private static void testMoveDirectorySyncFailureReturnsCommittedUncertain() throws Exception {
        Path root = Files.createTempDirectory("durable-move-dir-fail-");
        try {
            Path sourceDir = root.resolve("source");
            Path targetDir = root.resolve("target");
            Files.createDirectories(sourceDir);
            Files.createDirectories(targetDir);
            Path source = sourceDir.resolve("state");
            Path target = targetDir.resolve("state");
            Files.writeString(source, "moved");
            DurableAtomicFile.CommitResult result = DurableAtomicFile.move(
                    source, target, new DurableAtomicFile.MoveOperations() {
                        @Override public void createDirectories(Path directory) throws IOException {
                            Files.createDirectories(directory);
                        }
                        @Override public void atomicMove(Path from, Path to) throws IOException {
                            Files.move(from, to);
                        }
                        @Override public void forceDirectory(Path directory) throws IOException {
                            throw new IOException(DurableAtomicFile.DIRECTORY_SYNC_FAILED
                                    + ":" + directory);
                        }
                    });
            require(result.state()
                            == DurableAtomicFile.CommitState.POST_RENAME_DURABILITY_UNCERTAIN,
                    "move directory failure did not return post-rename state");
            require(!Files.exists(source), "source remained after committed move");
            require("moved".equals(Files.readString(target)),
                    "target content missing after committed move");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
