package com.warden.controlledsandbox.framework.core;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handle for deterministic rollback during Guest process teardown. */
public final class InstalledFrameworkProxy {
    private final FrameworkServiceSpec spec;
    private final Object singleton;
    private final Field instanceField;
    private final Object original;
    private final Object proxy;
    private final FrameworkIdentityInvocationHandler handler;
    private final AtomicBoolean active = new AtomicBoolean(true);

    InstalledFrameworkProxy(
            FrameworkServiceSpec spec,
            Object singleton,
            Field instanceField,
            Object original,
            Object proxy,
            FrameworkIdentityInvocationHandler handler) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.singleton = Objects.requireNonNull(singleton, "singleton");
        this.instanceField = Objects.requireNonNull(instanceField, "instanceField");
        this.original = Objects.requireNonNull(original, "original");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public String serviceName() {
        return spec.serviceName();
    }

    public boolean isActive() {
        return active.get();
    }

    public synchronized boolean rollback() throws IllegalAccessException {
        if (!active.get()) {
            return false;
        }
        handler.invalidateBinderBoundary("ROLLBACK");
        Object current = instanceField.get(singleton);
        if (current == proxy) {
            instanceField.set(singleton, original);
        }
        active.set(false);
        return current == proxy;
    }
}
