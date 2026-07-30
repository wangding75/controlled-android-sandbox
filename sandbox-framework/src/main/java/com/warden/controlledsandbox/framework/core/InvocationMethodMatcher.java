package com.warden.controlledsandbox.framework.core;

import java.util.Locale;

/** Exact-first method classifier used where inverse operation names overlap by substring. */
final class InvocationMethodMatcher {
    private InvocationMethodMatcher() { }

    static boolean named(String normalizedName, String... candidates) {
        if (normalizedName == null || candidates == null) return false;
        for (String candidate : candidates) {
            if (normalizedName.equals(normalize(candidate))) return true;
        }
        return false;
    }

    static boolean startsWith(String normalizedName, String... prefixes) {
        if (normalizedName == null || prefixes == null) return false;
        for (String prefix : prefixes) {
            if (normalizedName.startsWith(normalize(prefix))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
