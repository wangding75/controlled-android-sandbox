package com.warden.controlledsandbox.domain.component.receiver;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Android-independent broadcast intent descriptor used by the Broker matcher. */
public final class BroadcastIntent {
    public static final int MAX_CATEGORIES = 128;
    private final String action;
    private final Set<String> categories;
    private final String scheme;
    private final String host;
    private final String path;
    private final String mimeType;

    public BroadcastIntent(String action, Set<String> categories, String scheme,
                           String host, String path, String mimeType) {
        this.action = requireText(action, "action");
        this.categories = immutableTextSet(categories, "category");
        this.scheme = normalizeLimited(scheme, 128, "scheme");
        this.host = normalizeLimited(host, 255, "host").toLowerCase(Locale.ROOT);
        this.path = normalizePath(normalizeLimited(path, 4096, "path"));
        this.mimeType = normalizeLimited(mimeType, 255, "mimeType").toLowerCase(Locale.ROOT);
        if (!this.mimeType.isEmpty() && !validMime(this.mimeType)) {
            throw new IllegalArgumentException("Invalid broadcast MIME type: " + mimeType);
        }
        if (!this.host.isEmpty() && this.scheme.isEmpty()) {
            throw new IllegalArgumentException("Broadcast host requires scheme");
        }
        if (!this.path.isEmpty() && this.scheme.isEmpty()) {
            throw new IllegalArgumentException("Broadcast path requires scheme");
        }
    }

    public String action() { return action; }
    public Set<String> categories() { return categories; }
    public String scheme() { return scheme; }
    public String host() { return host; }
    public String path() { return path; }
    public String mimeType() { return mimeType; }
    public boolean hasData() {
        return !scheme.isEmpty() || !host.isEmpty() || !path.isEmpty() || !mimeType.isEmpty();
    }

    static boolean validMime(String value) {
        int slash = value.indexOf('/');
        return slash > 0 && slash == value.lastIndexOf('/') && slash < value.length() - 1;
    }

    private static Set<String> immutableTextSet(Set<String> values, String label) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) result.add(requireText(value, label));
        }
        if (result.size() > MAX_CATEGORIES) throw new IllegalArgumentException("Too many broadcast categories");
        return Collections.unmodifiableSet(result);
    }

    private static String requireText(String value, String label) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        if (normalized.length() > 1024) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static String normalizeLimited(String value, int limit, String label) {
        String normalized = normalize(value);
        if (normalized.length() > limit) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }
    private static String normalizePath(String value) {
        String path = normalize(value);
        if (!path.isEmpty() && !path.startsWith("/")) path = "/" + path;
        return path;
    }
}
