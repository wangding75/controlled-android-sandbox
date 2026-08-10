package com.warden.controlledsandbox.framework.contract;

import java.util.Locale;

/** Exact-first method classifier used where inverse operation names overlap by substring. */
public final class InvocationMethodMatcher {
    private InvocationMethodMatcher() { }

    public static boolean named(String normalizedName, String... candidates) {
        if (normalizedName == null || candidates == null) return false;
        String name = normalize(normalizedName);
        for (String candidate : candidates) {
            if (name.equals(normalize(candidate))) return true;
        }
        return false;
    }

    public static boolean startsWith(String normalizedName, String... prefixes) {
        if (normalizedName == null || prefixes == null) return false;
        String name = normalize(normalizedName);
        for (String prefix : prefixes) {
            if (name.startsWith(normalize(prefix))) return true;
        }
        return false;
    }

    public static boolean containsAny(String normalizedName, String... fragments) {
        if (normalizedName == null || fragments == null) return false;
        String name = normalize(normalizedName);
        for (String fragment : fragments) {
            if (name.contains(normalize(fragment))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
