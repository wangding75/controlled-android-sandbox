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
        testDirectorySyncFailureIsReported();
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

    private static void testDirectorySyncFailureIsReported() throws Exception {
        Path root = Files.createTempDirectory("durable-dir-fail-");
        try {
            Path temporary = root.resolve("state.tmp");
            Path destination = root.resolve("state");
            Files.writeString(temporary, "new");
            boolean rejected = false;
            try {
                DurableAtomicFile.replacePrepared(temporary, destination, new DurableAtomicFile.Operations() {
                    @Override public void forceFile(Path file) { }
                    @Override public void atomicReplace(Path source, Path target) throws IOException {
                        Files.move(source, target);
                    }
                    @Override public void forceDirectory(Path directory) throws IOException {
                        throw new IOException(DurableAtomicFile.DIRECTORY_SYNC_FAILED);
                    }
                });
            } catch (IOException expected) {
                rejected = expected.getMessage().contains(DurableAtomicFile.DIRECTORY_SYNC_FAILED);
            }
            require(rejected, "directory fsync failure reported");
            require("new".equals(Files.readString(destination)), "rename completed before durability failure");
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
