package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal clean-room Gradle distribution bootstrapper.
 *
 * <p>The repository intentionally keeps this implementation small, but it still
 * treats the Gradle distribution as a pinned build input. The archive is verified
 * before extraction, installation is serialized across concurrent processes and an
 * installed distribution is accepted only when its checksum marker matches the
 * repository lock.</p>
 */
public final class GradleWrapperMain {
    private static final String SHA_256 = "SHA-256";

    private GradleWrapperMain() {}

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(System.getProperty(
                "controlled.wrapper.projectDir", System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        Path propertiesPath = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(propertiesPath)) {
            properties.load(in);
        }

        URI distributionUri = URI.create(require(properties, "distributionUrl").replace("\\:", ":"));
        validateDistributionUri(distributionUri);
        String expectedSha256 = require(properties, "distributionSha256Sum").toLowerCase();
        if (!expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("distributionSha256Sum must be 64 lowercase hexadecimal characters");
        }

        String zipName = Path.of(distributionUri.getPath()).getFileName().toString();
        if (!zipName.endsWith(".zip")) {
            throw new IllegalArgumentException("Gradle distribution must be a ZIP archive: " + distributionUri);
        }
        String distributionName = zipName.substring(0, zipName.length() - 4);
        Path cacheRoot = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
                "controlled-sandbox");
        Files.createDirectories(cacheRoot);
        Path archivePath = cacheRoot.resolve(zipName);
        Path installPath = cacheRoot.resolve(distributionName);
        Path lockPath = cacheRoot.resolve("." + distributionName + ".lock");

        Path executable;
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            executable = verifiedInstalledExecutable(installPath, expectedSha256);
            if (executable == null) {
                ensureVerifiedArchive(distributionUri, archivePath, expectedSha256);
                executable = installVerifiedDistribution(archivePath, installPath, expectedSha256);
            }
        }

        makeExecutable(executable);
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectDir.toFile());
        builder.inheritIO();
        int exit = builder.start().waitFor();
        System.exit(exit);
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing property: " + key);
        }
        return value.trim();
    }

    private static void validateDistributionUri(URI uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only HTTPS and local file Gradle distributions are supported");
        }
        if ("https".equalsIgnoreCase(scheme) && uri.getHost() == null) {
            throw new IllegalArgumentException("Gradle distribution URL has no host: " + uri);
        }
    }

    private static Path verifiedInstalledExecutable(Path installPath, String expectedSha256) throws IOException {
        Path marker = installPath.resolve(".distribution.sha256");
        if (!Files.isRegularFile(marker)) return null;
        String recorded = Files.readString(marker).trim().toLowerCase();
        if (!recorded.equals(expectedSha256)) return null;
        return findGradleExecutable(installPath);
    }

    private static void ensureVerifiedArchive(URI uri, Path archivePath, String expectedSha256)
            throws IOException {
        if (Files.isRegularFile(archivePath)) {
            String actual = sha256(archivePath);
            if (actual.equals(expectedSha256)) return;
            Files.delete(archivePath);
        }
        download(uri, archivePath);
        String actual = sha256(archivePath);
        if (!actual.equals(expectedSha256)) {
            Files.deleteIfExists(archivePath);
            throw new IOException("Gradle distribution checksum mismatch: expected "
                    + expectedSha256 + " but was " + actual);
        }
    }

    private static Path installVerifiedDistribution(Path archivePath, Path installPath,
                                                     String expectedSha256) throws IOException {
        Path temporary = installPath.resolveSibling(installPath.getFileName()
                + ".install-" + UUID.randomUUID());
        deleteRecursively(temporary);
        Files.createDirectories(temporary);
        boolean installed = false;
        try {
            unzip(archivePath, temporary);
            Path executable = findGradleExecutable(temporary);
            if (executable == null) {
                throw new IOException("Gradle executable was not found after extraction: " + temporary);
            }
            Files.writeString(temporary.resolve(".distribution.sha256"), expectedSha256 + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            deleteRecursively(installPath);
            try {
                Files.move(temporary, installPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, installPath);
            }
            installed = true;
            Path installedExecutable = findGradleExecutable(installPath);
            if (installedExecutable == null) {
                throw new IOException("Installed Gradle executable was not found: " + installPath);
            }
            return installedExecutable;
        } finally {
            if (!installed) deleteRecursively(temporary);
        }
    }

    private static void download(URI uri, Path destination) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(temporary);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Files.copy(Path.of(uri), temporary, StandardCopyOption.REPLACE_EXISTING);
            moveDownloadedFile(temporary, destination);
            return;
        }

        URI current = uri;
        for (int redirect = 0; redirect < 8; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "ControlledSandbox-GradleBootstrap/2.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirect without Location from " + current);
                URI next = current.resolve(location);
                if (!"https".equalsIgnoreCase(next.getScheme())) {
                    throw new IOException("Refusing non-HTTPS Gradle redirect: " + next);
                }
                current = next;
                connection.disconnect();
                continue;
            }
            if (status != 200) throw new IOException("Download failed with HTTP " + status + " from " + current);
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
            }
            moveDownloadedFile(temporary, destination);
            return;
        }
        throw new IOException("Too many redirects while downloading " + uri);
    }

    private static void moveDownloadedFile(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(SHA_256 + " unavailable", impossible);
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void unzip(Path zip, Path destination) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination)) {
                    throw new IOException("Unsafe path in Gradle distribution: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                }
                input.closeEntry();
            }
        }
    }

    private static Path findGradleExecutable(Path root) throws IOException {
        if (!Files.isDirectory(root)) return null;
        String fileName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "gradle.bat" : "gradle";
        try (var paths = Files.walk(root, 5)) {
            return paths.filter(path -> path.getFileName().toString().equals(fileName))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals("bin"))
                    .findFirst().orElse(null);
        }
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or a file system without POSIX permission support.
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
