package com.warden.controlledsandbox.domain.identity;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Deterministic path mapper used by Java and native runtime adapters. */
public final class VirtualPathPolicy {
    private final Path root;
    private final String packageName;
    private final int userId;

    public VirtualPathPolicy(String root, String packageName, int userId) {
        if (root == null || root.trim().isEmpty()) throw new IllegalArgumentException("root is required");
        if (packageName == null || packageName.trim().isEmpty()) throw new IllegalArgumentException("packageName is required");
        if (userId < 0) throw new IllegalArgumentException("userId must be non-negative");
        this.root = Paths.get(root).toAbsolutePath().normalize();
        this.packageName = packageName;
        this.userId = userId;
    }

    public Path instanceRoot() { return root.resolve("users").resolve(String.valueOf(userId)).resolve("apps").resolve(packageName); }
    public Path dataDir() { return instanceRoot().resolve("data"); }
    public Path cacheDir() { return instanceRoot().resolve("cache"); }
    public Path filesDir() { return instanceRoot().resolve("files"); }
    public Path databasesDir() { return instanceRoot().resolve("databases"); }
    public Path webViewDir() { return instanceRoot().resolve("webview"); }

    public Path mapGuestPath(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("path is required");
        String dataData = "/data/data/" + packageName;
        String dataUser = "/data/user/" + userId + "/" + packageName;
        String dataUserZero = "/data/user/0/" + packageName;
        if (raw.equals(dataData) || raw.equals(dataUser) || raw.equals(dataUserZero)) return dataDir();
        if (raw.startsWith(dataData + "/")) return safeResolve(dataDir(), raw.substring(dataData.length() + 1));
        if (raw.startsWith(dataUser + "/")) return safeResolve(dataDir(), raw.substring(dataUser.length() + 1));
        if (raw.startsWith(dataUserZero + "/")) return safeResolve(dataDir(), raw.substring(dataUserZero.length() + 1));
        return Paths.get(raw).normalize();
    }

    private static Path safeResolve(Path base, String relative) {
        Path normalized = base.resolve(relative).normalize();
        if (!normalized.startsWith(base.normalize())) throw new SecurityException("PATH_TRAVERSAL");
        return normalized;
    }
}
