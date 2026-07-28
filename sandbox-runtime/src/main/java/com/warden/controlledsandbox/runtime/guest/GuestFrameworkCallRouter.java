package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import java.lang.reflect.Method;

/** Composite runtime-owned interceptor for ordered Receiver completion and virtual PendingIntent senders. */
final class GuestFrameworkCallRouter implements FrameworkCallInterceptor, AutoCloseable {
    private final OrderedReceiverFinishInterceptor orderedReceivers;
    private final PendingIntentFrameworkInterceptor pendingIntents;

    GuestFrameworkCallRouter(GuestPackageSpec spec, PendingIntentFrameworkInterceptor.Dispatcher dispatcher) {
        orderedReceivers = new OrderedReceiverFinishInterceptor();
        pendingIntents = new PendingIntentFrameworkInterceptor(spec, dispatcher);
    }

    OrderedReceiverFinishInterceptor orderedReceivers() { return orderedReceivers; }
    PendingIntentFrameworkInterceptor pendingIntents() { return pendingIntents; }

    @Override public Interception intercept(String serviceName, Method method, Object[] arguments) throws Throwable {
        Interception ordered = orderedReceivers.intercept(serviceName, method, arguments);
        if (ordered != null && ordered.handled()) return ordered;
        return pendingIntents.intercept(serviceName, method, arguments);
    }

    @Override public void close() {
        pendingIntents.close();
        orderedReceivers.close();
    }
}
