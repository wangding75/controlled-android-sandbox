package com.warden.controlledsandbox.runtime.guest;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Guest-owned WebView storage namespace; it never resolves a Host WebView data directory. */
public final class WebViewStorageBoundary implements AutoCloseable {
    private static final int MAX_KEY = 512;
    private static final int MAX_VALUE = 64 * 1024;

    private final File root;
    private final File cookies;
    private final File webStorage;
    private final File cache;
    private final File fileChooser;
    private final Map<String, String> cookieValues = new LinkedHashMap<>();
    private final Map<String, String> webStorageValues = new LinkedHashMap<>();
    private boolean closed;

    public WebViewStorageBoundary(File profileRoot) {
        root = canonical(profileRoot, "profileRoot");
        cookies = child(root, "cookies");
        webStorage = child(root, "web-storage");
        cache = child(root, "cache");
        fileChooser = child(root, "file-chooser");
    }

    public File root() { return root; }
    public File cookies() { return cookies; }
    public File webStorage() { return webStorage; }
    public File cache() { return cache; }
    public File fileChooser() { return fileChooser; }

    public synchronized void putCookie(String origin, String value) {
        put(cookieValues, origin, value, "origin", "cookie");
    }

    public synchronized String cookie(String origin) {
        return cookieValues.get(normalized(origin, "origin", MAX_KEY));
    }

    public synchronized void removeCookie(String origin) {
        cookieValues.remove(normalized(origin, "origin", MAX_KEY));
    }

    public synchronized void putWebStorage(String origin, String key, String value) {
        String normalizedOrigin = normalized(origin, "origin", MAX_KEY);
        String normalizedKey = normalized(key, "key", MAX_KEY);
        put(webStorageValues, normalizedOrigin + "\n" + normalizedKey, value, "key", "value");
    }

    public synchronized String webStorage(String origin, String key) {
        return webStorageValues.get(normalized(origin, "origin", MAX_KEY)
                + "\n" + normalized(key, "key", MAX_KEY));
    }

    /** Rejects path traversal and any path outside the Guest profile root. */
    public void requireGuestPath(File value) {
        File candidate = canonical(value, "path");
        if (!candidate.toPath().startsWith(root.toPath())) {
            throw new SecurityException("WEBVIEW_HOST_STORAGE_PATH_HIDDEN");
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cookieValues.clear();
        webStorageValues.clear();
    }

    private void put(Map<String, String> target, String key, String value,
                     String keyName, String valueName) {
        ensureOpen();
        target.put(normalized(key, keyName, MAX_KEY), normalized(value, valueName, MAX_VALUE));
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("WEBVIEW_STORAGE_CLOSED");
    }

    private static String normalized(String value, String name, int maximum) {
        if (value == null || value.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    private static File child(File parent, String name) {
        File value = canonical(new File(parent, name), name);
        if (!value.toPath().startsWith(parent.toPath())) throw new SecurityException("WEBVIEW_STORAGE_ESCAPE");
        return value;
    }

    private static File canonical(File value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        try { return value.getCanonicalFile(); }
        catch (IOException error) { throw new IllegalStateException("WEBVIEW_STORAGE_PATH_FAILED", error); }
    }
}
