package com.warden.controlledsandbox.framework.identity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Package-private intent filter matcher and query evaluator for VirtualPackageMetadata. */
final class VirtualPackageFilterMatcher {
    static final int MATCH_CATEGORY_EMPTY = 0x00100000;
    static final int MATCH_CATEGORY_SCHEME = 0x00200000;
    static final int MATCH_CATEGORY_HOST = 0x00300000;
    static final int MATCH_CATEGORY_PORT = 0x00400000;
    static final int MATCH_CATEGORY_PATH = 0x00500000;
    static final int MATCH_CATEGORY_TYPE = 0x00600000;
    static final int MATCH_ADJUSTMENT_NORMAL = 0x00008000;

    static final int NO_MATCH_TYPE = -1;
    static final int NO_MATCH_DATA = -2;

    static final class Match {
        final VirtualPackageMetadata.Component component;
        final VirtualPackageMetadata.Filter filter;
        final int score;

        Match(VirtualPackageMetadata.Component component,
              VirtualPackageMetadata.Filter filter,
              int score) {
            this.component = component;
            this.filter = filter;
            this.score = score;
        }
    }

    private VirtualPackageFilterMatcher() { }

    static ResolveInfo chooseBestActivity(List<ResolveInfo> query) {
        if (query == null || query.isEmpty()) return null;
        if (query.size() == 1) return query.get(0);
        ResolveInfo first = query.get(0);
        ResolveInfo second = query.get(1);
        if (first.priority != second.priority
                || preferredOrder(first) != preferredOrder(second)
                || first.isDefault != second.isDefault) {
            return first;
        }
        return null;
    }

    static int preferredOrder(ResolveInfo value) {
        if (value == null) return 0;
        try {
            java.lang.reflect.Field field = value.getClass().getField("preferredOrder");
            return field.getInt(value);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return 0;
        }
    }

    static boolean sameComponent(ComponentName left, ComponentName right) {
        return left == right || (left != null && right != null
                && value(left.getPackageName()).equals(value(right.getPackageName()))
                && value(left.getClassName()).equals(value(right.getClassName())));
    }

    static Intent selectedIntent(Intent intent) {
        if (intent == null) return new Intent();
        try {
            java.lang.reflect.Method getter = intent.getClass().getMethod("getSelector");
            getter.setAccessible(true);
            Object selector = getter.invoke(intent);
            return selector instanceof Intent ? (Intent) selector : intent;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return intent;
        }
    }

    static boolean matchesQueryFilter(List<VirtualPackageMetadata.Component> components,
                                      VirtualPackageMetadata.Filter query) {
        if (query == null) return false;
        for (VirtualPackageMetadata.Component component : components) {
            if (!component.exported()) continue;
            for (VirtualPackageMetadata.Filter declared : component.filters()) {
                if (filtersIntersect(query, declared)) return true;
            }
        }
        return false;
    }

    static boolean filtersIntersect(VirtualPackageMetadata.Filter query,
                                    VirtualPackageMetadata.Filter declared) {
        if (!query.actions().isEmpty() && !declared.actions().isEmpty()) {
            boolean actionMatch = false;
            for (String action : query.actions()) {
                if (declared.actions().contains(action)) { actionMatch = true; break; }
            }
            if (!actionMatch) return false;
        }
        if (!query.categories().isEmpty()
                && !declared.categories().containsAll(query.categories())) return false;
        if (query.data().isEmpty()) return true;
        if (declared.data().isEmpty()) return false;
        return dataRulesIntersect(query.data(), declared.data());
    }

    static boolean dataRulesIntersect(List<VirtualPackageMetadata.DataRule> leftRules,
                                      List<VirtualPackageMetadata.DataRule> rightRules) {
        boolean leftMime = hasMime(leftRules);
        boolean rightMime = hasMime(rightRules);
        if (leftMime != rightMime) return false;
        if (leftMime && !mimeListsIntersect(leftRules, rightRules)) return false;

        boolean leftScheme = hasScheme(leftRules);
        boolean rightScheme = hasScheme(rightRules);
        if (leftScheme && rightScheme) {
            if (!schemeListsIntersect(leftRules, rightRules)) return false;
        } else if (leftScheme || rightScheme) {
            String explicit = firstScheme(leftScheme ? leftRules : rightRules);
            if (!implicitDataScheme(explicit)) return false;
        }

        if (hasHost(leftRules) && hasHost(rightRules)) {
            if (!authorityListsIntersect(leftRules, rightRules)) return false;
        } else if (hasPort(leftRules) && hasPort(rightRules)
                && !portListsIntersect(leftRules, rightRules)) return false;
        return !hasPath(leftRules) || !hasPath(rightRules)
                || pathListsIntersect(leftRules, rightRules);
    }

    static Match match(Intent intent, VirtualPackageMetadata.Filter filter, long flags) {
        String action = value(intent.getAction());
        if (!action.isEmpty() && !filter.actions().contains(action)) return null;
        if (action.isEmpty() && !filter.actions().isEmpty()) return null;
        Set<String> categories = intent.getCategories();
        if (categories != null && !filter.categories().containsAll(categories)) return null;
        if ((flags & VirtualPackageMetadata.MATCH_DEFAULT_ONLY) != 0 && !filter.defaultCategory()) return null;
        String type = value(intent.getType()).toLowerCase(Locale.ROOT);
        String data = intent.getData() == null ? "" : intent.getData().toString();
        if (filter.data().isEmpty()) {
            if (!type.isEmpty() || !data.isEmpty()) return null;
            return new Match(null, filter, MATCH_CATEGORY_EMPTY + MATCH_ADJUSTMENT_NORMAL);
        }
        int best = dataMatch(filter.data(), type, data);
        return best < 0 ? null : new Match(null, filter, best);
    }

    static int dataMatch(List<VirtualPackageMetadata.DataRule> rules, String type, String rawData) {
        boolean hasMime = hasMime(rules);
        boolean hasScheme = hasScheme(rules);
        boolean hasHost = hasHost(rules);
        boolean hasPort = hasPort(rules);
        boolean hasPath = hasPath(rules);

        if (!hasMime && !hasScheme) {
            return type.isEmpty() && rawData.isEmpty()
                    ? MATCH_CATEGORY_EMPTY + MATCH_ADJUSTMENT_NORMAL : NO_MATCH_DATA;
        }
        if (hasPort && !hasHost) return NO_MATCH_DATA;

        URI uri = null;
        if (!rawData.isEmpty()) {
            try { uri = URI.create(rawData); } catch (IllegalArgumentException ignored) {
                return NO_MATCH_DATA;
            }
        }
        String actualScheme = uri == null ? "" : value(uri.getScheme()).toLowerCase(Locale.ROOT);
        int match = MATCH_CATEGORY_EMPTY;

        if (hasScheme) {
            if (uri == null || !schemeMatches(rules, actualScheme)) return NO_MATCH_DATA;
            match = MATCH_CATEGORY_SCHEME;

            if (hasHost) {
                int authority = uri == null || value(uri.getHost()).isEmpty()
                        ? NO_MATCH_DATA : authorityMatch(rules, value(uri.getHost()), uri.getPort());
                if (authority < 0) {
                    return NO_MATCH_DATA;
                }
                match = authority;
                if (hasPath) {
                    if (!pathMatches(rules, value(uri.getPath()))) return NO_MATCH_DATA;
                    match = MATCH_CATEGORY_PATH;
                }
            }
        } else {
            if (!actualScheme.isEmpty() && !"content".equals(actualScheme)
                    && !"file".equals(actualScheme)) return NO_MATCH_DATA;
        }

        if (hasMime) {
            boolean matchedMime = false;
            for (VirtualPackageMetadata.DataRule rule : rules) {
                if (rule != null && !rule.mimeType().isEmpty()
                        && mimeMatches(rule.mimeType(), type)) {
                    matchedMime = true;
                    break;
                }
            }
            if (!matchedMime) return NO_MATCH_TYPE;
            match = MATCH_CATEGORY_TYPE;
        } else if (!type.isEmpty()) {
            return NO_MATCH_TYPE;
        }
        return match + MATCH_ADJUSTMENT_NORMAL;
    }

    private static boolean schemeMatches(List<VirtualPackageMetadata.DataRule> rules, String actualScheme) {
        for (VirtualPackageMetadata.DataRule rule : rules) {
            if (rule != null && !rule.scheme().isEmpty() && rule.scheme().equals(actualScheme)) {
                return true;
            }
        }
        return false;
    }

    private static int authorityMatch(List<VirtualPackageMetadata.DataRule> rules, String actualHost, int actualPort) {
        int best = NO_MATCH_DATA;
        for (VirtualPackageMetadata.DataRule rule : rules) {
            if (rule != null && !rule.host().isEmpty()
                    && hostPatternMatches(rule.host(), actualHost)
                    && (rule.port() < 0 || rule.port() == actualPort)) {
                best = Math.max(best, rule.port() < 0 ? MATCH_CATEGORY_HOST : MATCH_CATEGORY_PORT);
            }
        }
        return best;
    }

    private static boolean pathMatches(List<VirtualPackageMetadata.DataRule> rules, String path) {
        for (VirtualPackageMetadata.DataRule rule : rules) {
            if (rule != null && hasPath(List.of(rule)) && pathMatches(rule, path)) return true;
        }
        return false;
    }

    private static boolean pathMatches(VirtualPackageMetadata.DataRule rule, String path) {
        if (!rule.path().isEmpty() && !rule.path().equals(path)) return false;
        if (!rule.pathPrefix().isEmpty() && !path.startsWith(rule.pathPrefix())) return false;
        return rule.pathPattern().isEmpty() || simpleGlob(rule.pathPattern(), path);
    }

    static boolean simpleGlob(String pattern, String value) {
        return AndroidSimpleGlobMatcher.matches(pattern, value);
    }

    private static boolean mimeMatches(String filter, String actual) {
        if (filter.isEmpty()) return actual.isEmpty();
        if (actual.isEmpty()) return false;
        if (filter.equals("*/*")) return actual.contains("/");
        int slash = filter.indexOf('/');
        if (slash > 0 && filter.endsWith("/*")) return actual.startsWith(filter.substring(0, slash + 1));
        return filter.equalsIgnoreCase(actual);
    }

    private static boolean hasMime(List<VirtualPackageMetadata.DataRule> rules) {
        if (rules == null) return false;
        for (VirtualPackageMetadata.DataRule rule : rules) if (rule != null && !rule.mimeType().isEmpty()) return true;
        return false;
    }

    private static boolean hasScheme(List<VirtualPackageMetadata.DataRule> rules) {
        if (rules == null) return false;
        for (VirtualPackageMetadata.DataRule rule : rules) if (rule != null && !rule.scheme().isEmpty()) return true;
        return false;
    }

    private static boolean hasHost(List<VirtualPackageMetadata.DataRule> rules) {
        if (rules == null) return false;
        for (VirtualPackageMetadata.DataRule rule : rules) if (rule != null && !rule.host().isEmpty()) return true;
        return false;
    }

    private static boolean hasPort(List<VirtualPackageMetadata.DataRule> rules) {
        if (rules == null) return false;
        for (VirtualPackageMetadata.DataRule rule : rules) if (rule != null && rule.port() >= 0) return true;
        return false;
    }

    private static boolean hasPath(List<VirtualPackageMetadata.DataRule> rules) {
        if (rules == null) return false;
        for (VirtualPackageMetadata.DataRule rule : rules) {
            if (rule != null && (!rule.path().isEmpty() || !rule.pathPrefix().isEmpty()
                    || !rule.pathPattern().isEmpty())) return true;
        }
        return false;
    }

    private static boolean mimeListsIntersect(List<VirtualPackageMetadata.DataRule> leftRules,
                                              List<VirtualPackageMetadata.DataRule> rightRules) {
        for (VirtualPackageMetadata.DataRule left : leftRules) {
            if (left == null || left.mimeType().isEmpty()) continue;
            for (VirtualPackageMetadata.DataRule right : rightRules) {
                if (right != null && !right.mimeType().isEmpty()
                        && mimeIntersects(left.mimeType(), right.mimeType())) return true;
            }
        }
        return false;
    }

    private static boolean schemeListsIntersect(List<VirtualPackageMetadata.DataRule> leftRules,
                                                List<VirtualPackageMetadata.DataRule> rightRules) {
        for (VirtualPackageMetadata.DataRule left : leftRules) {
            if (left == null || left.scheme().isEmpty()) continue;
            for (VirtualPackageMetadata.DataRule right : rightRules) {
                if (right != null && !right.scheme().isEmpty()
                        && schemeIntersects(left.scheme(), right.scheme())) return true;
            }
        }
        return false;
    }

    private static String firstScheme(List<VirtualPackageMetadata.DataRule> rules) {
        if (rules != null) {
            for (VirtualPackageMetadata.DataRule rule : rules) {
                if (rule != null && !rule.scheme().isEmpty()) return rule.scheme();
            }
        }
        return "";
    }

    private static boolean authorityListsIntersect(List<VirtualPackageMetadata.DataRule> leftRules,
                                                   List<VirtualPackageMetadata.DataRule> rightRules) {
        for (VirtualPackageMetadata.DataRule left : leftRules) {
            if (left == null || left.host().isEmpty()) continue;
            for (VirtualPackageMetadata.DataRule right : rightRules) {
                if (right != null && !right.host().isEmpty()
                        && hostIntersects(left.host(), right.host())
                        && (left.port() < 0 || right.port() < 0 || left.port() == right.port())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean portListsIntersect(List<VirtualPackageMetadata.DataRule> leftRules,
                                              List<VirtualPackageMetadata.DataRule> rightRules) {
        for (VirtualPackageMetadata.DataRule left : leftRules) {
            if (left == null || left.port() < 0) continue;
            for (VirtualPackageMetadata.DataRule right : rightRules) {
                if (right != null && right.port() == left.port()) return true;
            }
        }
        return false;
    }

    private static boolean pathListsIntersect(List<VirtualPackageMetadata.DataRule> leftRules,
                                              List<VirtualPackageMetadata.DataRule> rightRules) {
        for (VirtualPackageMetadata.DataRule left : leftRules) {
            if (left == null || !hasPath(List.of(left))) continue;
            for (VirtualPackageMetadata.DataRule right : rightRules) {
                if (right != null && hasPath(List.of(right)) && pathIntersects(left, right)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mimeIntersects(String left, String right) {
        String first = value(left).toLowerCase(Locale.ROOT);
        String second = value(right).toLowerCase(Locale.ROOT);
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        if ("*/*".equals(first) || "*/*".equals(second)) return true;
        int firstSlash = first.indexOf('/');
        int secondSlash = second.indexOf('/');
        if (firstSlash <= 0 || secondSlash <= 0) return first.equals(second);
        String firstType = first.substring(0, firstSlash);
        String secondType = second.substring(0, secondSlash);
        String firstSubtype = first.substring(firstSlash + 1);
        String secondSubtype = second.substring(secondSlash + 1);
        boolean typeOverlap = firstType.equals("*") || secondType.equals("*")
                || firstType.equals(secondType);
        boolean subtypeOverlap = firstSubtype.equals("*") || secondSubtype.equals("*")
                || firstSubtype.equals(secondSubtype);
        return typeOverlap && subtypeOverlap;
    }

    private static boolean schemeIntersects(String left, String right) {
        String first = value(left).toLowerCase(Locale.ROOT);
        String second = value(right).toLowerCase(Locale.ROOT);
        if (first.isEmpty() && second.isEmpty()) return true;
        if (first.isEmpty()) return implicitDataScheme(second);
        if (second.isEmpty()) return implicitDataScheme(first);
        return first.equals(second);
    }

    private static boolean implicitDataScheme(String scheme) {
        return scheme.isEmpty() || "content".equals(scheme) || "file".equals(scheme);
    }

    private static boolean hostIntersects(String left, String right) {
        String first = value(left).toLowerCase(Locale.ROOT);
        String second = value(right).toLowerCase(Locale.ROOT);
        if (first.isEmpty() || second.isEmpty()) return true;
        if (first.equals(second)) return true;
        if (first.startsWith("*") && second.startsWith("*")) {
            String firstSuffix = first.substring(1);
            String secondSuffix = second.substring(1);
            return firstSuffix.endsWith(secondSuffix) || secondSuffix.endsWith(firstSuffix);
        }
        if (first.startsWith("*")) return hostPatternMatches(first, second);
        if (second.startsWith("*")) return hostPatternMatches(second, first);
        return false;
    }

    private static boolean hostPatternMatches(String pattern, String actual) {
        String suffix = value(pattern).toLowerCase(Locale.ROOT);
        if (!suffix.startsWith("*")) return suffix.equals(actual);
        suffix = suffix.substring(1);
        if (suffix.isEmpty()) return true;
        if (suffix.startsWith(".")) suffix = suffix.substring(1);
        return actual.equals(suffix) || actual.endsWith("." + suffix);
    }

    private static boolean pathIntersects(VirtualPackageMetadata.DataRule left,
                                         VirtualPackageMetadata.DataRule right) {
        String[] first = pathConstraints(left);
        String[] second = pathConstraints(right);
        if (first.length == 0 || second.length == 0) return true;
        for (String firstConstraint : first) {
            for (String secondConstraint : second) {
                if (pathConstraintsIntersect(firstConstraint, secondConstraint)) return true;
            }
        }
        return false;
    }

    private static String[] pathConstraints(VirtualPackageMetadata.DataRule rule) {
        ArrayList<String> constraints = new ArrayList<>(3);
        if (!rule.path().isEmpty()) constraints.add("=:" + rule.path());
        if (!rule.pathPrefix().isEmpty()) constraints.add("^:" + rule.pathPrefix());
        if (!rule.pathPattern().isEmpty()) constraints.add("*:" + rule.pathPattern());
        return constraints.toArray(new String[0]);
    }

    private static boolean pathConstraintsIntersect(String left, String right) {
        char leftKind = left.charAt(0);
        char rightKind = right.charAt(0);
        String first = left.substring(2);
        String second = right.substring(2);
        if (leftKind == '=' && rightKind == '=') return first.equals(second);
        if (leftKind == '=' && rightKind == '^') return first.startsWith(second);
        if (leftKind == '^' && rightKind == '=') return second.startsWith(first);
        if (leftKind == '=' && rightKind == '*') return simpleGlob(second, first);
        if (leftKind == '*' && rightKind == '=') return simpleGlob(first, second);
        if (leftKind == '^' && rightKind == '^') {
            return first.startsWith(second) || second.startsWith(first);
        }
        if (leftKind == '^' && rightKind == '*') {
            return globIntersectsPrefix(second, first);
        }
        if (leftKind == '*' && rightKind == '^') {
            return globIntersectsPrefix(first, second);
        }
        return globIntersects(second, first);
    }

    private static boolean globIntersectsPrefix(String pattern, String prefix) {
        if (simpleGlob(pattern, prefix)) return true;
        if (simpleGlob(pattern, prefix + "x")) return true;
        String literal = globLiteralPrefix(pattern);
        return literal.isEmpty() || literal.startsWith(prefix) || prefix.startsWith(literal);
    }

    private static boolean globIntersects(String first, String second) {
        if (first.equals(second) || simpleGlob(first, second) || simpleGlob(second, first)) {
            return true;
        }
        String left = globLiteralPrefix(first);
        String right = globLiteralPrefix(second);
        return left.isEmpty() || right.isEmpty() || left.startsWith(right) || right.startsWith(left);
    }

    private static String globLiteralPrefix(String pattern) {
        String value = value(pattern);
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length()) {
                prefix.append(value.charAt(++index));
                continue;
            }
            if (current == '.' || (index + 1 < value.length() && value.charAt(index + 1) == '*')) {
                break;
            }
            prefix.append(current);
        }
        return prefix.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
