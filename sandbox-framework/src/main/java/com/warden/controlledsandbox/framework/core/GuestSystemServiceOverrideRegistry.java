package com.warden.controlledsandbox.framework.core;

import android.content.Context;
import java.util.IdentityHashMap;
import java.util.Map;

/** Process-local, reversible overrides visible only through the supplied Guest Context. */
public final class GuestSystemServiceOverrideRegistry {
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<Context, Map<String, Object>> OVERRIDES =
            new IdentityHashMap<>();

    private GuestSystemServiceOverrideRegistry() { }

    public static Object get(Context context, String serviceName) {
        synchronized (LOCK) {
            Map<String, Object> values = OVERRIDES.get(context);
            return values == null ? null : values.get(serviceName);
        }
    }

    public static AutoCloseable install(Context context, String serviceName, Object service) {
        if (context == null || serviceName == null || service == null) {
            throw new IllegalArgumentException("override context, name and service are required");
        }
        synchronized (LOCK) {
            Map<String, Object> values = OVERRIDES.computeIfAbsent(context,
                    ignored -> new java.util.LinkedHashMap<>());
            Object previous = values.put(serviceName, service);
            return () -> {
                synchronized (LOCK) {
                    Map<String, Object> current = OVERRIDES.get(context);
                    if (current == null || current.get(serviceName) != service) return;
                    if (previous == null) current.remove(serviceName);
                    else current.put(serviceName, previous);
                    if (current.isEmpty()) OVERRIDES.remove(context);
                }
            };
        }
    }
}
