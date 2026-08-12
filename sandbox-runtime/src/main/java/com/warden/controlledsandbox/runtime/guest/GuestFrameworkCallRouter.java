package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Method;

/** Composite runtime-owned interceptor for Activity tasks, ordered Receivers and PendingIntent senders. */
final class GuestFrameworkCallRouter implements FrameworkCallInterceptor, AutoCloseable {
    private final ActivityTaskFrameworkInterceptor activityTasks;
    private final OrderedReceiverFinishInterceptor orderedReceivers;
    private final PendingIntentFrameworkInterceptor pendingIntents;
    private final GuestContentProviderFrameworkInterceptor contentProviders;

    GuestFrameworkCallRouter(GuestContext context, GuestPackageSpec spec,
            VirtualSystemServiceState.PendingIntentState pendingIntentState,
            PendingIntentFrameworkInterceptor.Dispatcher dispatcher,
            String hostPackageName) {
        activityTasks = new ActivityTaskFrameworkInterceptor(spec,
                new GuestActivityTaskClient(spec), hostPackageName);
        orderedReceivers = new OrderedReceiverFinishInterceptor();
        pendingIntents = new PendingIntentFrameworkInterceptor(spec, pendingIntentState,
                activityTasks::virtualActivityToken, dispatcher);
        contentProviders = new GuestContentProviderFrameworkInterceptor(context, spec);
    }

    ActivityTaskFrameworkInterceptor activityTasks() { return activityTasks; }
    OrderedReceiverFinishInterceptor orderedReceivers() { return orderedReceivers; }
    PendingIntentFrameworkInterceptor pendingIntents() { return pendingIntents; }
    boolean sendPersistentPendingIntent(String tokenId) { return pendingIntents.sendPersistent(tokenId); }

    @Override public Interception intercept(String serviceName, Method method, Object[] arguments) throws Throwable {
        Interception provider = contentProviders.intercept(serviceName, method, arguments);
        if (provider != null && provider.handled()) return provider;
        Interception tasks = activityTasks.intercept(serviceName, method, arguments);
        if (tasks != null && tasks.handled()) return tasks;
        Interception ordered = orderedReceivers.intercept(serviceName, method, arguments);
        if (ordered != null && ordered.handled()) return ordered;
        return pendingIntents.intercept(serviceName, method, arguments);
    }

    @Override public void close() {
        contentProviders.close();
        activityTasks.close();
        pendingIntents.close();
        orderedReceivers.close();
    }
}
