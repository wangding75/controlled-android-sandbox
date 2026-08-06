package com.warden.controlledsandbox.domain.component.provider;

import java.net.URI;
import java.util.List;

final class ProviderAuthorityAccessPolicy {
    private ProviderAuthorityAccessPolicy() { }

    static String requiredPermission(List<ProviderPathRule> rules, String fallback,
                                     String uri, boolean read) {
        ProviderPathRule rule = bestPermissionRule(rules, uri);
        String selected = rule == null ? "" : (read ? rule.readPermission() : rule.writePermission());
        return selected.isEmpty() ? normalize(fallback) : selected;
    }

    static boolean allowsUriGrant(boolean globalGrant, List<ProviderPathRule> rules, String uri) {
        if (globalGrant) return true;
        String path = uriPath(uri);
        for (ProviderPathRule rule : rules) {
            if (rule.uriGrantRule() && rule.matches(path)) return true;
        }
        return false;
    }

    static boolean equivalent(List<ProviderPathRule> first, List<ProviderPathRule> second) {
        List<ProviderPathRule> right = second == null ? List.of() : second;
        if (first.size() != right.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            if (!first.get(index).equivalent(right.get(index))) return false;
        }
        return true;
    }

    private static ProviderPathRule bestPermissionRule(List<ProviderPathRule> rules, String uri) {
        String path = uriPath(uri);
        ProviderPathRule best = null;
        for (ProviderPathRule rule : rules) {
            if (rule.uriGrantRule() || !rule.matches(path)) continue;
            if (best == null || rule.specificity() > best.specificity()) best = rule;
        }
        return best;
    }

    private static String uriPath(String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) return "/";
        try {
            String path = URI.create(rawUri).getPath();
            String normalized = normalize(path);
            return normalized.isEmpty() || normalized.startsWith("/") ? normalized : "/" + normalized;
        } catch (IllegalArgumentException ignored) {
            return "/";
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }
}
