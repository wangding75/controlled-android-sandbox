package com.warden.controlledsandbox.runtime.guest;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import java.io.File;
import java.io.IOException;
import java.util.Objects;

/** Establishes one deterministic WebView profile for each isolated Guest process before WebView startup. */
public final class WebViewProfileManager {
    private static String configuredKey = "";
    private static Profile configuredProfile;

    private WebViewProfileManager() { }

    static synchronized Profile install(GuestPackageSpec spec) {
        Profile profile = plan(spec.packageName, spec.virtualUserId, spec.processName,
                spec.processSlot, spec.dataRootFile());
        if (!configuredKey.isEmpty()) {
            if (!configuredKey.equals(profile.key)) {
                throw new IllegalStateException("WEBVIEW_PROFILE_PROCESS_REUSE:" + configuredKey + "->" + profile.key);
            }
            return configuredProfile;
        }
        createDirectory(profile.root);
        createDirectory(profile.cache);
        createDirectory(profile.databases);
        createDirectory(profile.serviceWorker);
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WebView.setDataDirectorySuffix(profile.suffix);
            } catch (IllegalStateException alreadyInitialized) {
                throw new IllegalStateException("WEBVIEW_ALREADY_INITIALIZED_IN_GUEST_PROCESS", alreadyInitialized);
            }
        }
        configuredKey = profile.key;
        configuredProfile = profile;
        return profile;
    }

    static synchronized Profile configured() { return configuredProfile; }

    static Profile plan(String packageName, int virtualUserId, String processName,
                        int processSlot, File instanceRoot) {
        requireName(packageName, "packageName");
        requireName(processName, "processName");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (processSlot < 0) throw new IllegalArgumentException("processSlot must be non-negative");
        Objects.requireNonNull(instanceRoot, "instanceRoot");
        String processPart = safe(processName);
        String key = virtualUserId + ":" + packageName + ":" + processName;
        String suffix = "u" + virtualUserId + "_" + Integer.toUnsignedString(key.hashCode(), 36);
        File root;
        try {
            root = new File(instanceRoot, "webview/" + processPart).getCanonicalFile();
            File canonicalInstance = instanceRoot.getCanonicalFile();
            if (!root.toPath().startsWith(canonicalInstance.toPath())) {
                throw new SecurityException("WEBVIEW_PROFILE_ESCAPES_INSTANCE");
            }
        } catch (IOException error) {
            throw new IllegalStateException("WEBVIEW_PROFILE_PATH_FAILED", error);
        }
        return new Profile(key, suffix, root, new File(root, "cache"),
                new File(root, "databases"), new File(root, "service-worker"), Build.VERSION.SDK_INT >= 28);
    }

    private static void createDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Cannot create WebView profile directory " + directory);
        }
    }

    private static void requireName(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    static final class Profile {
        final String key;
        final String suffix;
        final File root;
        final File cache;
        final File databases;
        final File serviceWorker;
        final boolean suffixApplied;

        Profile(String key, String suffix, File root, File cache, File databases,
                File serviceWorker, boolean suffixApplied) {
            this.key = key;
            this.suffix = suffix;
            this.root = root;
            this.cache = cache;
            this.databases = databases;
            this.serviceWorker = serviceWorker;
            this.suffixApplied = suffixApplied;
        }

        Bundle toBundle() {
            Bundle out = new Bundle();
            out.putString("webViewProfileKey", key);
            out.putString("webViewDataSuffix", suffix);
            out.putString("webViewProfileRoot", root.getAbsolutePath());
            out.putString("webViewCacheRoot", cache.getAbsolutePath());
            out.putString("webViewDatabaseRoot", databases.getAbsolutePath());
            out.putString("webViewServiceWorkerRoot", serviceWorker.getAbsolutePath());
            out.putBoolean("webViewSuffixApplied", suffixApplied);
            return out;
        }
    }
}
