package com.warden.controlledsandbox.domain.component.provider;

/** Immutable Provider path permission or URI-grant rule. */
public final class ProviderPathRule {
    private final String path;
    private final String pathPrefix;
    private final String pathPattern;
    private final String readPermission;
    private final String writePermission;
    private final boolean uriGrantRule;

    public ProviderPathRule(String path, String pathPrefix, String pathPattern,
                            String readPermission, String writePermission, boolean uriGrantRule) {
        this.path = normalizePath(path);
        this.pathPrefix = normalizePath(pathPrefix);
        this.pathPattern = normalizePath(pathPattern);
        int count = (this.path.isEmpty() ? 0 : 1) + (this.pathPrefix.isEmpty() ? 0 : 1)
                + (this.pathPattern.isEmpty() ? 0 : 1);
        if (count != 1) throw new IllegalArgumentException(
                "Provider path rule requires exactly one matcher");
        this.readPermission = normalize(readPermission);
        this.writePermission = normalize(writePermission);
        this.uriGrantRule = uriGrantRule;
    }

    public String path() { return path; }
    public String pathPrefix() { return pathPrefix; }
    public String pathPattern() { return pathPattern; }
    public String readPermission() { return readPermission; }
    public String writePermission() { return writePermission; }
    public boolean uriGrantRule() { return uriGrantRule; }

    boolean matches(String uriPath) {
        String actual = normalizePath(uriPath);
        if (!path.isEmpty()) return path.equals(actual);
        if (!pathPrefix.isEmpty()) return actual.startsWith(pathPrefix);
        return AndroidSimpleGlobMatcher.matches(pathPattern, actual);
    }

    int specificity() {
        if (!path.isEmpty()) return 3_000_000 + path.length();
        if (!pathPrefix.isEmpty()) return 2_000_000 + pathPrefix.length();
        return 1_000_000 + pathPattern.length();
    }

    boolean equivalent(ProviderPathRule other) {
        return other != null && path.equals(other.path) && pathPrefix.equals(other.pathPrefix)
                && pathPattern.equals(other.pathPattern)
                && readPermission.equals(other.readPermission)
                && writePermission.equals(other.writePermission)
                && uriGrantRule == other.uriGrantRule;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static String normalizePath(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() || normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
