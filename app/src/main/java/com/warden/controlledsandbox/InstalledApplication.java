package com.warden.controlledsandbox;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Product-facing snapshot of a host-installed application.
 *
 * The paths are deliberately retained as metadata only. The package service resolves them again
 * from PackageManager when the user confirms an import, so the UI cannot turn a stale or forged
 * path into a package revision.
 */
final class InstalledApplication {
    final String packageName;
    final String label;
    final String versionName;
    final long versionCode;
    final String sourceDir;
    final List<String> splitSourceDirs;
    final String signatureSha256;
    final String nativeAbi;
    final boolean containsNativeCode;
    final Drawable icon;
    final int sandboxInstanceCount;

    private InstalledApplication(String packageName, String label, String versionName,
                                 long versionCode, String sourceDir, List<String> splitSourceDirs,
                                 String signatureSha256, String nativeAbi,
                                 boolean containsNativeCode, Drawable icon,
                                 int sandboxInstanceCount) {
        this.packageName = packageName;
        this.label = label;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.sourceDir = sourceDir;
        this.splitSourceDirs = Collections.unmodifiableList(new ArrayList<>(splitSourceDirs));
        this.signatureSha256 = signatureSha256;
        this.nativeAbi = nativeAbi;
        this.containsNativeCode = containsNativeCode;
        this.icon = icon;
        this.sandboxInstanceCount = sandboxInstanceCount;
    }

    static InstalledApplication from(ApplicationInfo application, PackageInfo packageInfo,
                                     PackageManager packageManager, int sandboxInstanceCount) {
        String sourceDir = value(application.sourceDir);
        List<String> splits = new ArrayList<>();
        if (application.splitSourceDirs != null) {
            for (String split : application.splitSourceDirs) if (!value(split).isEmpty()) splits.add(split);
        }
        String label = String.valueOf(application.loadLabel(packageManager));
        if (label.trim().isEmpty() || "null".equals(label)) label = application.packageName;
        return new InstalledApplication(application.packageName, label,
                packageInfo.versionName == null ? "" : packageInfo.versionName,
                Build.VERSION.SDK_INT >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode,
                sourceDir, splits, signingDigest(packageInfo), nativeAbi(application),
                application.nativeLibraryDir != null && !application.nativeLibraryDir.trim().isEmpty(),
                safeIcon(application, packageManager), sandboxInstanceCount);
    }

    List<String> artifactPaths() {
        List<String> paths = new ArrayList<>(1 + splitSourceDirs.size());
        paths.add(sourceDir);
        paths.addAll(splitSourceDirs);
        return paths;
    }

    private static Drawable safeIcon(ApplicationInfo application, PackageManager packageManager) {
        try {
            Drawable icon = application.loadIcon(packageManager);
            return icon == null ? packageManager.getDefaultActivityIcon() : icon;
        } catch (Exception ignored) {
            return packageManager.getDefaultActivityIcon();
        }
    }

    private static String nativeAbi(ApplicationInfo application) {
        String libraryDir = value(application.nativeLibraryDir);
        if (libraryDir.isEmpty()) return "";
        String[] parts = libraryDir.replace('\\', '/').split("/");
        String last = parts.length == 0 ? "" : parts[parts.length - 1];
        return last.isEmpty() ? "host-installed" : last;
    }

    private static String signingDigest(PackageInfo info) {
        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return "";
            List<String> digests = new ArrayList<>();
            for (Signature signature : signatures) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digests.add(toHex(digest.digest(signature.toByteArray())));
            }
            Collections.sort(digests);
            return String.join(",", digests);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }
}
