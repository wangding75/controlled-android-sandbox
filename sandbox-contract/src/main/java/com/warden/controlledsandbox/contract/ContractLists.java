package com.warden.controlledsandbox.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Shared bounded immutable string-list validation for typed contracts. */
final class ContractLists {
    private ContractLists() { }

    static List<String> unique(
            List<String> values, String field, int maximum, int maximumChars, boolean optional) {
        List<String> normalized = values(values, field, maximum, maximumChars, optional);
        if (new LinkedHashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException(field + " contains duplicates");
        }
        return normalized;
    }

    static List<String> values(
            List<String> values, String field, int maximum, int maximumChars, boolean optional) {
        if (values == null) return List.of();
        if (values.size() > maximum) throw new IllegalArgumentException(field + " limit exceeded");
        ArrayList<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            String normalized = optional
                    ? ContractChecks.optionalText(value, field, maximumChars)
                    : ContractChecks.requiredText(value, field, maximumChars);
            result.add(normalized.trim());
        }
        return List.copyOf(result);
    }
}
