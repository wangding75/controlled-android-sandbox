package com.warden.controlledsandbox.runtime.guest;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import java.io.File;
import java.io.IOException;
import java.util.Objects;

/** Establishes one deterministic WebView profile for each isolated Guest process before WebView startup. */
public final class WebViewProfileManager {
    private static String configuredKey = "";
    private static Profile configuredProfile;

    private WebViewProfileManager() { }

    static synchronized Profile install(GuestPackageSpec spec) {
        return install(spec, null);
    }

    static synchronized Profile install(GuestPackageSpec spec, VirtualWebViewProfileSnapshot policy) {
        Profile profile = plan(spec.packageName, spec.virtualUserId, spec.processName,
                spec.processSlot, spec.dataRootFile(), policy);
        if (!configuredKey.isEmpty()) {
            if (!configuredKey.equals(profile.key)) {
                throw new IllegalStateException("WEBVIEW_PROFILE_PROCESS_REUSE:" + configuredKey + "->" + profile.key);
            }
            return configuredProfile;
        }
        // Platform isolated UIDs cannot traverse the host package data label.  The isolated
        // WebView profile is therefore provisioned by the capability-backed storage bridge when
        // WebView is actually requested; eagerly mkdir'ing the logical Guest path here makes the
        // whole process fail before Application.onCreate.
        if (!spec.isolatedProcess) {
            createDirectory(profile.root);
            createDirectory(profile.cache);
            createDirectory(profile.databases);
            createDirectory(profile.serviceWorker);
            createDirectory(profile.cookies);
            createDirectory(profile.webStorage);
            createDirectory(profile.fileChooser);
        }
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
        return plan(packageName, virtualUserId, processName, processSlot, instanceRoot, null);
    }

    static Profile plan(String packageName, int virtualUserId, String processName,
                        int processSlot, File instanceRoot, VirtualWebViewProfileSnapshot policy) {
        requireName(packageName, "packageName");
        requireName(processName, "processName");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (processSlot < 0) throw new IllegalArgumentException("processSlot must be non-negative");
        Objects.requireNonNull(instanceRoot, "instanceRoot");
        String processPart = safe(processName);
        String key = virtualUserId + ":" + packageName + ":" + processName;
        if (policy != null && VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(policy.mode())) {
            throw new SecurityException("VIRTUAL_WEBVIEW_BLOCKED");
        }
        String suffix = policy != null && !VirtualLocationProfileSnapshot.MODE_HOST.equals(policy.mode())
                ? policy.dataDirectorySuffix() + "_" + Integer.toUnsignedString(processName.hashCode(), 36)
                : "u" + virtualUserId + "_" + Integer.toUnsignedString(key.hashCode(), 36);
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
        VirtualWebViewProfileSnapshot effective = policy == null
                ? new VirtualWebViewProfileSnapshot(VirtualLocationProfileSnapshot.MODE_STATIC,
                        "com.android.webview", "virtual", suffix, "sandbox_webview", true, true, false, 4)
                : policy;
        return new Profile(key, suffix, root, new File(root, "cache"),
                new File(root, "databases"), new File(root, "service-worker"),
                new File(root, "cookies"), new File(root, "web-storage"),
                new File(root, "file-chooser"), Build.VERSION.SDK_INT >= 28,
                new WebViewRendererRegistry(effective), effective.rendererProcessPrefix(),
                effective.maximumRendererProcesses());
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
        final File cookies;
        final File webStorage;
        final File fileChooser;
        final WebViewStorageBoundary storage;
        final boolean suffixApplied;
        final WebViewRendererRegistry renderers;
        final String rendererProcessPrefix;
        final int maximumRendererProcesses;

        Profile(String key, String suffix, File root, File cache, File databases,
                File serviceWorker, File cookies, File webStorage, File fileChooser,
                boolean suffixApplied, WebViewRendererRegistry renderers,
                String rendererProcessPrefix, int maximumRendererProcesses) {
            this.key = key;
            this.suffix = suffix;
            this.root = root;
            this.cache = cache;
            this.databases = databases;
            this.serviceWorker = serviceWorker;
            this.cookies = cookies;
            this.webStorage = webStorage;
            this.fileChooser = fileChooser;
            this.storage = new WebViewStorageBoundary(root);
            this.suffixApplied = suffixApplied;
            this.renderers = renderers;
            this.rendererProcessPrefix = rendererProcessPrefix;
            this.maximumRendererProcesses = maximumRendererProcesses;
        }

        Bundle toBundle() {
            Bundle out = new Bundle();
            out.putString("webViewProfileKey", key);
            out.putString("webViewDataSuffix", suffix);
            out.putString("webViewProfileRoot", root.getAbsolutePath());
            out.putString("webViewCacheRoot", cache.getAbsolutePath());
            out.putString("webViewDatabaseRoot", databases.getAbsolutePath());
            out.putString("webViewServiceWorkerRoot", serviceWorker.getAbsolutePath());
            out.putString("webViewCookieRoot", cookies.getAbsolutePath());
            out.putString("webViewStorageRoot", webStorage.getAbsolutePath());
            out.putString("webViewFileChooserRoot", fileChooser.getAbsolutePath());
            out.putBoolean("webViewSuffixApplied", suffixApplied);
            out.putString("webViewRendererProcessPrefix", rendererProcessPrefix);
            out.putInt("webViewMaximumRendererProcesses", maximumRendererProcesses);
            out.putInt("webViewActiveRendererProcesses", renderers.activeCount());
            return out;
        }
    }
}
