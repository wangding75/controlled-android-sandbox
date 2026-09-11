package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/**
 * Gives U4 a real Java-visible native-library directory without exposing the CAS directory shape.
 *
 * <p>UC's U4 process-mode detector treats a deeply nested path containing two dotted components
 * as a multi-process environment.  A CAS package directory necessarily contains both the Host
 * package and Guest package names, so passing that physical directory directly changes U4's
 * process mode.  Passing the Android logical {@code /data/app/...} path avoids the detector but
 * makes U4's Java {@code File.exists()} check fail.  This class bridges only that incompatible
 * metadata contract: the alias is a symlink created below the Host app's private files root,
 * its leaf contains no dots, and NativePolicy maps it back to the verified CAS directory.</p>
 */
final class GuestNativeLibraryAlias {
    private static final String ALIAS_DIRECTORY = "guest-native-aliases";
    private static final String CORE_LIBRARY = "libwebviewuc.so";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private GuestNativeLibraryAlias() { }

    /**
     * Returns a lexical path (never a canonicalized path) that U4 can inspect with java.io.File.
     * Non-U4 native packages keep their existing packaged-library projection.
     */
    static String ensure(Context host, GuestPackageSpec spec, String packagedNativeLibraryDir) {
        String fallback = normalize(packagedNativeLibraryDir);
        if (spec == null || spec.isolatedProcess || fallback.isEmpty()) return fallback;
        File target = new File(fallback);
        try {
            if (!target.isAbsolute()) {
                throw new IllegalStateException("GUEST_NATIVE_LIBRARY_PATH_NOT_ABSOLUTE");
            }
            // Do not use getCanonicalFile() here. After libjavacore realpath is hooked,
            // NativePolicy reverse-maps the CAS package directory onto /data/app/<pkg>/lib/<abi>,
            // which is the Guest-visible name, not the host-files confinement root.
            File targetAbs = target.getAbsoluteFile().toPath().normalize().toFile();
            if (Files.isSymbolicLink(targetAbs.toPath())
                    || !Files.isDirectory(targetAbs.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return fallback;
            }
            File core = new File(targetAbs, CORE_LIBRARY);
            if (!Files.isRegularFile(core.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return fallback;
            }
            if (host == null) throw new IllegalStateException("GUEST_NATIVE_ALIAS_HOST_MISSING");
            File hostFiles = host.getFilesDir();
            if (hostFiles == null || !hostFiles.isAbsolute()) {
                throw new IllegalStateException("GUEST_NATIVE_ALIAS_HOST_FILES_INVALID");
            }
            if (Files.isSymbolicLink(hostFiles.toPath())
                    || !Files.isDirectory(hostFiles.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("GUEST_NATIVE_ALIAS_HOST_FILES_UNTRUSTED");
            }
            File hostRoot = hostFiles.getAbsoluteFile().toPath().normalize().toFile();
            if (!containedBy(hostRoot, targetAbs)) {
                throw new IllegalStateException("GUEST_NATIVE_ALIAS_TARGET_OUTSIDE_HOST_FILES");
            }

            File parent = new File(hostRoot, ALIAS_DIRECTORY).getAbsoluteFile();
            if (Files.isSymbolicLink(parent.toPath())) {
                throw new IllegalStateException("GUEST_NATIVE_ALIAS_PARENT_SYMLINK");
            }
            Files.createDirectories(parent.toPath());
            File parentAbs = parent.getAbsoluteFile().toPath().normalize().toFile();
            if (!containedBy(hostRoot, parentAbs)) {
                throw new IllegalStateException("GUEST_NATIVE_ALIAS_PARENT_OUTSIDE_HOST_FILES");
            }

            File alias = new File(parentAbs, aliasName(spec)).getAbsoluteFile();
            if (Files.exists(alias.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                verifyAlias(alias, targetAbs);
            } else {
                try {
                    Files.createSymbolicLink(alias.toPath(), targetAbs.toPath());
                } catch (FileAlreadyExistsException raced) {
                    verifyAlias(alias, targetAbs);
                }
                verifyAlias(alias, targetAbs);
            }
            String result = alias.getAbsolutePath();
            android.util.Log.i("CS_NATIVE_ALIAS", "applied=1 package=" + spec.packageName
                    + " abi=" + spec.nativeAbi + " alias=" + result
                    + " target=" + targetAbs.getAbsolutePath());
            return result;
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalStateException) throw (IllegalStateException) error;
            throw new IllegalStateException("GUEST_NATIVE_LIBRARY_ALIAS_FAILED", error);
        }
    }

    private static void verifyAlias(File alias, File target) throws IOException {
        if (!Files.isSymbolicLink(alias.toPath())) {
            throw new IllegalStateException("GUEST_NATIVE_ALIAS_COLLISION");
        }
        if (!alias.toPath().toRealPath().equals(target.toPath().toRealPath())) {
            throw new IllegalStateException("GUEST_NATIVE_ALIAS_TARGET_MISMATCH");
        }
        if (!Files.isDirectory(alias.toPath())
                || !Files.isRegularFile(new File(alias, CORE_LIBRARY).toPath(),
                        LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("GUEST_NATIVE_ALIAS_INCOMPLETE");
        }
    }

    static boolean containedBy(File root, File child) {
        if (root == null || child == null) return false;
        if (pathInside(root.getAbsoluteFile().toPath().normalize().toString(),
                child.getAbsoluteFile().toPath().normalize().toString())) {
            return true;
        }
        try {
            return pathInside(root.getCanonicalPath(), child.getCanonicalPath());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean pathInside(String rootPath, String childPath) {
        return !rootPath.equals(childPath) && childPath.startsWith(rootPath + File.separator);
    }

    private static String aliasName(GuestPackageSpec spec) {
        return "v1-" + safeComponent(spec.packageName) + "-"
                + safeComponent(spec.packageRevision) + "-" + safeComponent(spec.nativeAbi);
    }

    private static String safeComponent(String value) {
        if (value == null || value.isEmpty()) return "empty";
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_') {
                out.append(character);
            } else {
                out.append('_');
                out.append(HEX[(character >>> 12) & 0x0f]);
                out.append(HEX[(character >>> 8) & 0x0f]);
                out.append(HEX[(character >>> 4) & 0x0f]);
                out.append(HEX[character & 0x0f]);
            }
        }
        return out.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
