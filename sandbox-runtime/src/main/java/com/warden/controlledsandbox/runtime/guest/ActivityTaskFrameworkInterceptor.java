package com.warden.controlledsandbox.runtime.guest;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Fail-closed Framework bridge for Broker-owned Running/Recent/AppTask state and mutations. */
final class ActivityTaskFrameworkInterceptor implements FrameworkCallInterceptor, AutoCloseable {
    private final GuestPackageSpec spec;
    private final GuestActivityTaskClient client;
    private final Map<IBinder, HostBinding> hostBindings = new IdentityHashMap<>();
    private final ThreadLocal<Boolean> hostBypass = ThreadLocal.withInitial(() -> Boolean.FALSE);

    ActivityTaskFrameworkInterceptor(GuestPackageSpec spec) {
        this(spec, new GuestActivityTaskClient(spec));
    }

    ActivityTaskFrameworkInterceptor(GuestPackageSpec spec, GuestActivityTaskClient client) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.client = Objects.requireNonNull(client, "client");
    }

    synchronized void bindHostActivity(IBinder frameworkToken, String activityToken, int taskId,
                                       Runnable moveToFront, BooleanSupplier moveToBack,
                                       Runnable finishAffinity, Runnable finishAndRemoveTask) {
        Objects.requireNonNull(frameworkToken, "frameworkToken");
        if (activityToken == null || activityToken.isBlank() || taskId < 1) {
            throw new IllegalArgumentException("virtual Activity task identity is incomplete");
        }
        hostBindings.put(frameworkToken, new HostBinding(activityToken, taskId,
                Objects.requireNonNull(moveToFront, "moveToFront"),
                Objects.requireNonNull(moveToBack, "moveToBack"),
                Objects.requireNonNull(finishAffinity, "finishAffinity"),
                Objects.requireNonNull(finishAndRemoveTask, "finishAndRemoveTask")));
    }

    synchronized void updateHostActivity(IBinder frameworkToken, String activityToken) {
        HostBinding binding = requireBinding(frameworkToken);
        if (activityToken == null || activityToken.isBlank()) {
            throw new IllegalArgumentException("activityToken is required");
        }
        binding.activityToken = activityToken;
    }

    synchronized boolean consumeBrokerFinalized(IBinder frameworkToken) {
        HostBinding binding = hostBindings.get(frameworkToken);
        if (binding == null) return false;
        boolean value = binding.brokerFinalized;
        binding.brokerFinalized = false;
        return value;
    }

    synchronized void unbindHostActivity(IBinder frameworkToken) {
        if (frameworkToken != null) hostBindings.remove(frameworkToken);
    }

    @Override public Interception intercept(String serviceName, Method method, Object[] arguments)
            throws Throwable {
        if (Boolean.TRUE.equals(hostBypass.get())) return Interception.passThrough();
        if (!"activity-manager".equals(serviceName)
                && !"activity-task-manager".equals(serviceName)) {
            return Interception.passThrough();
        }
        String name = method.getName();
        return switch (name) {
            case "getTasks", "getRunningTasks" -> running(method, arguments);
            case "getRecentTasks" -> recent(method, arguments);
            case "getAppTasks" -> appTasks(method, arguments);
            case "moveTaskToFront" -> taskMutation(method, arguments,
                    ActivityTaskRequest.MOVE_TO_FRONT, HostAction.MOVE_FRONT);
            case "removeTask" -> taskMutation(method, arguments,
                    ActivityTaskRequest.REMOVE_TASK, HostAction.FINISH_REMOVE);
            case "moveActivityTaskToBack" -> activityMutation(method, arguments,
                    ActivityTaskRequest.MOVE_TO_BACK, HostAction.MOVE_BACK);
            case "finishActivityAffinity" -> activityMutation(method, arguments,
                    ActivityTaskRequest.FINISH_AFFINITY, HostAction.FINISH_AFFINITY);
            case "finishActivityAndRemoveTask" -> activityMutation(method, arguments,
                    ActivityTaskRequest.FINISH_AND_REMOVE_TASK, HostAction.FINISH_REMOVE);
            case "getTaskForActivity" -> taskForActivity(arguments);
            default -> Interception.passThrough();
        };
    }

    private Interception running(Method method, Object[] arguments) {
        ActivityTaskResult result = client.query(ActivityTaskRequest.QUERY_RUNNING,
                maxCount(arguments));
        return Interception.handled(AndroidTaskInfoProjector.running(result.tasks(), method));
    }

    private Interception recent(Method method, Object[] arguments) {
        ActivityTaskResult result = client.query(ActivityTaskRequest.QUERY_RECENT,
                maxCount(arguments));
        return Interception.handled(AndroidTaskInfoProjector.recent(result.tasks(), method));
    }

    private Interception appTasks(Method method, Object[] arguments) {
        verifyAppTasksPackage(arguments);
        ActivityTaskResult result = client.query(ActivityTaskRequest.QUERY_RECENT, 100);
        return Interception.handled(AndroidTaskInfoProjector.appTasks(
                result.tasks(), method, new AndroidTaskInfoProjector.AppTaskOperations() {
                    @Override public void moveToFront(int taskId) {
                        mutateFromAppTask(taskId, ActivityTaskRequest.MOVE_TO_FRONT, HostAction.MOVE_FRONT);
                    }
                    @Override public void finishAndRemoveTask(int taskId) {
                        mutateFromAppTask(taskId, ActivityTaskRequest.REMOVE_TASK, HostAction.FINISH_REMOVE);
                    }
                }));
    }

    private void mutateFromAppTask(int taskId, String operation, HostAction hostAction) {
        ActivityTaskResult result = client.mutateTask(operation, taskId);
        if (result.changed()) invokeForTask(taskId, hostAction);
    }

    private Interception taskMutation(Method method, Object[] arguments, String operation,
                                      HostAction hostAction) {
        int taskId = requireTaskId(arguments);
        ActivityTaskResult result = client.mutateTask(operation, taskId);
        if (result.changed()) invokeForTask(taskId, hostAction);
        return Interception.handled(returnValue(method.getReturnType(), result.changed()));
    }

    private Interception activityMutation(Method method, Object[] arguments, String operation,
                                          HostAction hostAction) {
        IBinder token = requireFrameworkToken(arguments);
        HostBinding binding;
        synchronized (this) { binding = requireBinding(token); }
        final ActivityTaskResult result;
        if (ActivityTaskRequest.MOVE_TO_BACK.equals(operation)) {
            result = client.mutateTask(operation, binding.taskId);
        } else {
            result = client.mutateActivity(operation, binding.activityToken);
        }
        if (result.changed()) invokeForTask(binding.taskId, hostAction);
        return Interception.handled(returnValue(method.getReturnType(), result.changed()));
    }

    private Interception taskForActivity(Object[] arguments) {
        IBinder token = requireFrameworkToken(arguments);
        synchronized (this) {
            return Interception.handled(requireBinding(token).taskId);
        }
    }

    private void invokeForTask(int taskId, HostAction action) {
        List<HostBinding> matches = new ArrayList<>();
        synchronized (this) {
            for (HostBinding binding : hostBindings.values()) {
                if (binding.taskId == taskId) matches.add(binding);
            }
            if (action == HostAction.FINISH_AFFINITY || action == HostAction.FINISH_REMOVE) {
                for (HostBinding binding : matches) binding.brokerFinalized = true;
            }
        }
        if (matches.isEmpty()) return;
        HostBinding primary = matches.get(0);
        withHostBypass(() -> {
            switch (action) {
                case MOVE_FRONT -> primary.moveToFront.run();
                case MOVE_BACK -> primary.moveToBack.getAsBoolean();
                case FINISH_AFFINITY -> primary.finishAffinity.run();
                case FINISH_REMOVE -> primary.finishAndRemoveTask.run();
            }
        });
    }

    private void withHostBypass(Runnable action) {
        boolean previous = hostBypass.get();
        hostBypass.set(Boolean.TRUE);
        try { action.run(); }
        finally { hostBypass.set(previous); }
    }

    private void verifyAppTasksPackage(Object[] arguments) {
        if (arguments == null || arguments.length == 0 || !(arguments[0] instanceof String value)) return;
        if (!value.isBlank() && !spec.packageName.equals(value)) {
            throw new SecurityException("VIRTUAL_APP_TASK_PACKAGE_MISMATCH");
        }
    }

    private static int maxCount(Object[] arguments) {
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof Integer value && value > 0) return Math.min(value, 100);
            }
        }
        return 100;
    }

    private static int requireTaskId(Object[] arguments) {
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof Integer value && value > 0) return value;
            }
        }
        throw new IllegalArgumentException("VIRTUAL_TASK_ID_MISSING");
    }

    private static IBinder requireFrameworkToken(Object[] arguments) {
        if (arguments != null) {
            for (Object argument : arguments) if (argument instanceof IBinder token) return token;
        }
        throw new IllegalArgumentException("VIRTUAL_ACTIVITY_FRAMEWORK_TOKEN_MISSING");
    }

    private synchronized HostBinding requireBinding(IBinder token) {
        HostBinding binding = hostBindings.get(token);
        if (binding == null) throw new SecurityException("VIRTUAL_ACTIVITY_FRAMEWORK_TOKEN_UNKNOWN");
        return binding;
    }

    private static Object returnValue(Class<?> type, boolean changed) {
        if (type == void.class) return null;
        if (type == boolean.class || type == Boolean.class) return changed;
        if (type == int.class || type == Integer.class) return changed ? 1 : 0;
        if (type == long.class || type == Long.class) return changed ? 1L : 0L;
        return null;
    }

    @Override public synchronized void close() {
        hostBindings.clear();
        hostBypass.remove();
    }

    private enum HostAction { MOVE_FRONT, MOVE_BACK, FINISH_AFFINITY, FINISH_REMOVE }

    private static final class HostBinding {
        private String activityToken;
        private final int taskId;
        private final Runnable moveToFront;
        private final BooleanSupplier moveToBack;
        private final Runnable finishAffinity;
        private final Runnable finishAndRemoveTask;
        private boolean brokerFinalized;

        private HostBinding(String activityToken, int taskId, Runnable moveToFront,
                            BooleanSupplier moveToBack, Runnable finishAffinity,
                            Runnable finishAndRemoveTask) {
            this.activityToken = activityToken;
            this.taskId = taskId;
            this.moveToFront = moveToFront;
            this.moveToBack = moveToBack;
            this.finishAffinity = finishAffinity;
            this.finishAndRemoveTask = finishAndRemoveTask;
        }
    }
}
