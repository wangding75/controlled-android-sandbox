package com.warden.controlledsandbox.framework.identity;

import java.util.Objects;

public record ArgumentRewriteRule(int index, IdentityValueKind kind) {
    public ArgumentRewriteRule {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        kind = Objects.requireNonNull(kind, "kind");
    }

    public static ArgumentRewriteRule packageName(int index) {
        return new ArgumentRewriteRule(index, IdentityValueKind.PACKAGE_NAME);
    }

    public static ArgumentRewriteRule uid(int index) {
        return new ArgumentRewriteRule(index, IdentityValueKind.UID);
    }

    public static ArgumentRewriteRule packageNameArray(int index) {
        return new ArgumentRewriteRule(index, IdentityValueKind.PACKAGE_NAME_ARRAY);
    }

    public static ArgumentRewriteRule attributionSource(int index) {
        return new ArgumentRewriteRule(index, IdentityValueKind.ATTRIBUTION_SOURCE);
    }
    public boolean acceptsParameterType(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return switch (kind) {
            case PACKAGE_NAME -> type == String.class;
            case UID -> type == int.class || type == Integer.class;
            case PACKAGE_NAME_ARRAY -> (type.isArray() && type.getComponentType() == String.class)
                    || java.util.List.class.isAssignableFrom(type);
            case ATTRIBUTION_SOURCE -> type.getName().equals("android.content.AttributionSource");
        };
    }

}
