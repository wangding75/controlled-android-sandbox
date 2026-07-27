package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal clean-room Gradle distribution bootstrapper.
 * It intentionally implements only the behavior required by this repository.
 */
public final class GradleWrapperMain {
    private GradleWrapperMain() {}

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(System.getProperty("controlled.wrapper.projectDir", System.getProperty("user.dir"))).toAbsolutePath().normalize();
        Path propertiesPath = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(propertiesPath)) {
            properties.load(in);
        }
        String rawUrl = require(properties, "distributionUrl").replace("\\:", ":");
        URI distributionUri = URI.create(rawUrl);
        String zipName = Path.of(distributionUri.getPath()).getFileName().toString();
        String distributionName = zipName.endsWith(".zip")
                ? zipName.substring(0, zipName.length() - 4)
                : zipName;
        Path gradleHome = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
                "controlled-sandbox", distributionName);
        Path executable = findGradleExecutable(gradleHome);
        if (executable == null) {
            Files.createDirectories(gradleHome);
            Path zipPath = gradleHome.resolve(zipName);
            if (!Files.isRegularFile(zipPath)) {
                System.out.println("Downloading " + distributionUri);
                download(distributionUri, zipPath);
            }
            unzip(zipPath, gradleHome);
            executable = findGradleExecutable(gradleHome);
            if (executable == null) {
                throw new IOException("Gradle executable was not found after extraction: " + gradleHome);
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

    private static void download(URI uri, Path destination) throws IOException {
        URI current = uri;
        for (int redirect = 0; redirect < 8; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "ControlledSandbox-GradleBootstrap/1.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirect without Location from " + current);
                current = current.resolve(location);
                connection.disconnect();
                continue;
            }
            if (status != 200) throw new IOException("Download failed with HTTP " + status + " from " + current);
            Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        throw new IOException("Too many redirects while downloading " + uri);
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
        String fileName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "gradle.bat" : "gradle";
        try (var paths = Files.walk(root, 4)) {
            return paths.filter(path -> path.getFileName().toString().equals(fileName))
                    .filter(path -> path.getParent() != null && path.getParent().getFileName().toString().equals("bin"))
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
}
