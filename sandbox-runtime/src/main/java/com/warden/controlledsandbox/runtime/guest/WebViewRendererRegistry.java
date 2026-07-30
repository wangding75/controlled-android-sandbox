package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded renderer-process ownership model for one Guest WebView profile. */
final class WebViewRendererRegistry implements AutoCloseable {
    private final VirtualWebViewProfileSnapshot policy;
    private final Map<String, String> active = new LinkedHashMap<>();

    WebViewRendererRegistry(VirtualWebViewProfileSnapshot policy) {
        this.policy = policy;
    }

    synchronized String reserve(String rendererToken) {
        if (rendererToken == null || rendererToken.trim().isEmpty()) {
            throw new IllegalArgumentException("rendererToken is required");
        }
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(policy.mode())) {
            throw new SecurityException("VIRTUAL_WEBVIEW_RENDERER_BLOCKED");
        }
        String normalized = rendererToken.trim();
        String existing = active.get(normalized);
        if (existing != null) return existing;
        if (!policy.multiprocessEnabled()) {
            throw new SecurityException("VIRTUAL_WEBVIEW_MULTIPROCESS_DISABLED");
        }
        if (active.size() >= policy.maximumRendererProcesses()) {
            throw new IllegalStateException("VIRTUAL_WEBVIEW_RENDERER_LIMIT");
        }
        String prefix = policy.rendererProcessPrefix().isEmpty()
                ? "sandbox_webview" : policy.rendererProcessPrefix();
        String processName = prefix + ":" + Integer.toUnsignedString(normalized.hashCode(), 36);
        active.put(normalized, processName);
        return processName;
    }

    synchronized boolean release(String rendererToken) {
        return rendererToken != null && active.remove(rendererToken.trim()) != null;
    }

    synchronized int activeCount() { return active.size(); }

    synchronized Map<String, String> snapshot() { return Map.copyOf(active); }

    @Override public synchronized void close() { active.clear(); }
}
