package com.warden.controlledsandbox.framework.core;

import java.lang.reflect.Method;

/** Optional framework-call interception surface used by runtime-owned bridges. */
@FunctionalInterface
public interface FrameworkCallInterceptor {
    FrameworkCallInterceptor NO_OP = (serviceName, method, arguments) -> Interception.passThrough();

    Interception intercept(String serviceName, Method method, Object[] arguments) throws Throwable;

    final class Interception {
        private static final Interception PASS_THROUGH = new Interception(false, null);
        private final boolean handled;
        private final Object result;

        private Interception(boolean handled, Object result) {
            this.handled = handled;
            this.result = result;
        }

        public static Interception passThrough() { return PASS_THROUGH; }
        public static Interception handled(Object result) { return new Interception(true, result); }
        public boolean handled() { return handled; }
        public Object result() { return result; }
    }
}
