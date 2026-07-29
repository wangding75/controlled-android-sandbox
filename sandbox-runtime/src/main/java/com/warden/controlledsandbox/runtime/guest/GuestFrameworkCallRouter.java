package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Method;

/** Composite runtime-owned interceptor for Activity tasks, ordered Receivers and PendingIntent senders. */
final class GuestFrameworkCallRouter implements FrameworkCallInterceptor, AutoCloseable {
    private final ActivityTaskFrameworkInterceptor activityTasks;
    private final OrderedReceiverFinishInterceptor orderedReceivers;
    private final PendingIntentFrameworkInterceptor pendingIntents;

    GuestFrameworkCallRouter(GuestPackageSpec spec,
            VirtualSystemServiceState.PendingIntentState pendingIntentState,
            PendingIntentFrameworkInterceptor.Dispatcher dispatcher) {
        activityTasks = new ActivityTaskFrameworkInterceptor(spec);
        orderedReceivers = new OrderedReceiverFinishInterceptor();
        pendingIntents = new PendingIntentFrameworkInterceptor(spec, pendingIntentState,
                activityTasks::virtualActivityToken, dispatcher);
    }

    ActivityTaskFrameworkInterceptor activityTasks() { return activityTasks; }
    OrderedReceiverFinishInterceptor orderedReceivers() { return orderedReceivers; }
    PendingIntentFrameworkInterceptor pendingIntents() { return pendingIntents; }
    boolean sendPersistentPendingIntent(String tokenId) { return pendingIntents.sendPersistent(tokenId); }

    @Override public Interception intercept(String serviceName, Method method, Object[] arguments) throws Throwable {
        Interception tasks = activityTasks.intercept(serviceName, method, arguments);
        if (tasks != null && tasks.handled()) return tasks;
        Interception ordered = orderedReceivers.intercept(serviceName, method, arguments);
        if (ordered != null && ordered.handled()) return ordered;
        return pendingIntents.intercept(serviceName, method, arguments);
    }

    @Override public void close() {
        activityTasks.close();
        pendingIntents.close();
        orderedReceivers.close();
    }
}
