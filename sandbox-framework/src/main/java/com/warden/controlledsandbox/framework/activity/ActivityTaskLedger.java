package com.warden.controlledsandbox.framework.activity;

import static com.warden.controlledsandbox.framework.activity.ActivityTaskTextPolicy.normalizeOptional;
import static com.warden.controlledsandbox.framework.activity.ActivityTaskTextPolicy.requireBoundedText;
import static com.warden.controlledsandbox.framework.activity.ActivityTaskTextPolicy.requireText;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker-owned deterministic Activity task/back-stack state.
 *
 * <p>This class does not call Android framework APIs. It computes the virtual task decision that the
 * runtime bridge must apply to its Stub Activity and one-time route-token layer.</p>
 */
public final class ActivityTaskLedger {
    public static final int RESULT_CANCELED = 0;

    private final AtomicInteger nextTaskId = new AtomicInteger(1);
    private final AtomicLong nextNewIntentSequence = new AtomicLong(1);
    private static final int MAX_ACTIVE_TASKS = 256;
    private static final int MAX_ACTIVE_ACTIVITIES = 2048;
    static final int MAX_RECENT_TASKS = 64;
    private static final int MAX_RESULT_REGISTRATIONS = 128;

    private final AtomicLong nextConfigurationSequence = new AtomicLong(1);
    private final AtomicLong nextActivationSequence = new AtomicLong(1);
    private final LinkedHashMap<Integer, ActivityTaskMutableTask> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, TaskQuerySnapshot> recentTasks = new LinkedHashMap<>();
    private final Map<String, ActivityTaskMutableActivity> activitiesByToken = new LinkedHashMap<>();
    private final Map<String, List<ActivityResultDelivery>> resultDeliveriesByCaller =
            new LinkedHashMap<>();
    private final ActivityTaskActivityStateCoordinator activityState =
            new ActivityTaskActivityStateCoordinator(
                    this, nextConfigurationSequence, tasks, activitiesByToken,
                    resultDeliveriesByCaller, MAX_RESULT_REGISTRATIONS);
    private final ActivityTaskCheckpointCoordinator checkpointCoordinator =
            new ActivityTaskCheckpointCoordinator(
                    nextTaskId, nextNewIntentSequence, nextConfigurationSequence,
                    nextActivationSequence, tasks, recentTasks, activitiesByToken,
                    resultDeliveriesByCaller, MAX_RECENT_TASKS);

    private boolean recordingLaunchRemovals;
    private final ArrayList<String> lastLaunchRemovedActivityTokens = new ArrayList<>();

    public synchronized LaunchDecision launch(LaunchRequest request) {
        lastLaunchRemovedActivityTokens.clear();
        recordingLaunchRemovals = true;
        try {
            return launchRecorded(request);
        } finally {
            recordingLaunchRemovals = false;
        }
    }

    public synchronized List<String> lastLaunchRemovedActivityTokens() {
        return List.copyOf(lastLaunchRemovedActivityTokens);
    }

    private LaunchDecision launchRecorded(LaunchRequest request) {
        Objects.requireNonNull(request, "request");
        validateLaunchFlagCombinations(request);
        validateCallerTask(request);
        String callerActivityToken = resolveCallerActivityToken(request);
        String resultCallerToken = resolveResultCallerToken(request, callerActivityToken);
        validateForwardResult(request, callerActivityToken);

        boolean clearTask = LaunchFlags.has(request.flags(), LaunchFlags.CLEAR_TASK);
        if (clearTask && !LaunchFlags.has(request.flags(), LaunchFlags.NEW_TASK)) {
            throw new IllegalArgumentException("CLEAR_TASK requires NEW_TASK");
        }

        if (!clearTask && request.documentLaunchMode() == DocumentLaunchMode.INTO_EXISTING) {
            ActivityTaskMatch document = findDocumentTask(request);
            if (document != null) {
                ActivityTaskMutableActivity root = document.task().activities.get(0);
                int removed = clearAbove(document.task(), 0);
                enqueueNewIntent(root, request);
                registerResultLink(root, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.CLEARED_TOP,
                        document.task(),
                        root,
                        removed,
                        false,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        if (!clearTask && request.launchMode() == LaunchMode.SINGLE_INSTANCE) {
            ActivityTaskMatch global = findAcrossTasks(request.identity(), request.packageRevision());
            if (global != null) {
                ActivityTaskMutableActivity activity = global.task().activities.get(global.index());
                int removed = retainOnly(global.task(), activity);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.DELIVERED_NEW_INTENT,
                        global.task(),
                        activity,
                        removed,
                        false,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        if (!clearTask && request.launchMode() == LaunchMode.SINGLE_TASK) {
            ActivityTaskMatch global = findAcrossTasks(request.identity(), request.packageRevision());
            if (global != null) {
                int removed = clearAbove(global.task(), global.index());
                ActivityTaskMutableActivity activity = global.task().activities.get(global.index());
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.CLEARED_TOP,
                        global.task(),
                        activity,
                        removed,
                        false,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        ActivityTaskMutableTask target = selectTargetTask(request);
        boolean createdTask = false;
        if (target == null) {
            target = createTask(request);
            createdTask = true;
        }

        if (clearTask) {
            clearTaskForReuse(target);
        }

        // Android performs task-reset pruning only when an existing task is being brought to the
        // front with RESET_TASK_IF_NEEDED. The launching ActivityInfo controls force-reset, while
        // each retained ActivityInfo controls whether that Activity is finished.
        int resetRemoved = 0;
        if (!clearTask && !createdTask
                && LaunchFlags.has(request.flags(), LaunchFlags.NEW_TASK)
                && LaunchFlags.has(request.flags(), LaunchFlags.RESET_TASK_IF_NEEDED)) {
            resetRemoved = resetTaskIfNeeded(target, request);
        }

        if (request.launchMode() == LaunchMode.SINGLE_INSTANCE_PER_TASK) {
            int existingIndex = findIndex(target, request.identity());
            if (existingIndex >= 0) {
                int removed = clearAbove(target, existingIndex);
                ActivityTaskMutableActivity activity = target.activities.get(existingIndex);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.DELIVERED_NEW_INTENT,
                        target,
                        activity,
                        removed + resetRemoved,
                        createdTask,
                        request.routeToken(),
                        callerActivityToken);
            }
            if (!target.activities.isEmpty()) {
                target = createTask(request);
                createdTask = true;
            }
        }

        if (LaunchFlags.has(request.flags(), LaunchFlags.REORDER_TO_FRONT)) {
            int existingIndex = findIndex(target, request.identity());
            if (existingIndex >= 0) {
                ActivityTaskMutableActivity activity = target.activities.remove(existingIndex);
                target.activities.add(activity);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.REORDERED_TO_FRONT,
                        target,
                        activity,
                        resetRemoved,
                        createdTask,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        if (LaunchFlags.has(request.flags(), LaunchFlags.CLEAR_TOP)) {
            int existingIndex = findIndex(target, request.identity());
            if (existingIndex >= 0) {
                int removed = clearAbove(target, existingIndex);
                ActivityTaskMutableActivity activity = target.activities.get(existingIndex);
                boolean deliver = request.launchMode() != LaunchMode.STANDARD
                        || LaunchFlags.has(request.flags(), LaunchFlags.SINGLE_TOP);
                if (deliver) {
                    enqueueNewIntent(activity, request);
                    registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                    return completeDecision(
                            LaunchAction.CLEARED_TOP,
                            target,
                            activity,
                            removed + resetRemoved,
                            createdTask,
                            request.routeToken(),
                            callerActivityToken);
                }
                removeActivityAt(target, existingIndex, false, RESULT_CANCELED, "");
                removed++;
                ActivityTaskMutableActivity replacement = createActivity(request);
                target.activities.add(replacement);
                registerResultLink(replacement, resultCallerToken, callerActivityToken, request);
                ensureTaskRegistered(target);
                return completeDecision(
                        LaunchAction.CREATED_ACTIVITY,
                        target,
                        replacement,
                        removed + resetRemoved,
                        createdTask,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        ActivityTaskMutableActivity top = top(target);
        boolean singleTop = request.launchMode() == LaunchMode.SINGLE_TOP
                || LaunchFlags.has(request.flags(), LaunchFlags.SINGLE_TOP);
        if (singleTop && top != null && top.identity.equals(request.identity())) {
            enqueueNewIntent(top, request);
            registerResultLink(top, resultCallerToken, callerActivityToken, request);
            return completeDecision(
                    LaunchAction.DELIVERED_NEW_INTENT,
                    target,
                    top,
                    resetRemoved,
                    createdTask,
                    request.routeToken(),
                    callerActivityToken);
        }

        if (request.launchMode() == LaunchMode.SINGLE_INSTANCE && !target.activities.isEmpty()) {
            target = createTask(request);
            createdTask = true;
        }

        ActivityTaskMutableActivity created = createActivity(request);
        target.activities.add(created);
        registerResultLink(created, resultCallerToken, callerActivityToken, request);
        ensureTaskRegistered(target);
        return completeDecision(
                createdTask ? LaunchAction.CREATED_TASK : LaunchAction.CREATED_ACTIVITY,
                target,
                created,
                resetRemoved,
                createdTask,
                request.routeToken(),
                callerActivityToken);
    }

    public synchronized boolean transition(String token, LifecycleState next) {
        return activityState.transition(token, next);
    }

    public synchronized boolean finish(String token) {
        return activityState.finish(token);
    }

    public synchronized boolean finishWithResult(String token, int resultCode, String dataToken) {
        return activityState.finishWithResult(token, resultCode, dataToken);
    }

    public synchronized boolean finishWithResult(
            String token,
            int resultCode,
            ResultIntentSnapshot resultIntent) {
        return activityState.finishWithResult(token, resultCode, resultIntent);
    }

    public synchronized ActivityResultRegistration registerActivityResult(
            String activityToken,
            String registrationKey) {
        return activityState.registerActivityResult(activityToken, registrationKey);
    }

    public synchronized boolean unregisterActivityResult(String activityToken, String registrationKey) {
        return activityState.unregisterActivityResult(activityToken, registrationKey);
    }

    public synchronized Optional<ActivityResultRegistration> activityResultRegistration(
            String activityToken,
            String registrationKey) {
        return activityState.activityResultRegistration(activityToken, registrationKey);
    }

    public synchronized boolean deliverActivityResult(String callerActivityToken,
            String resultWho, int requestCode, int resultCode, String intentSenderToken,
            ResultIntentSnapshot resultIntent) {
        return activityState.deliverActivityResult(callerActivityToken, resultWho, requestCode,
                resultCode, intentSenderToken, resultIntent);
    }

    public synchronized List<ActivityResultDelivery> drainActivityResults(String callerActivityToken) {
        return activityState.drainActivityResults(callerActivityToken);
    }

    public synchronized int pendingActivityResultCount(String callerActivityToken) {
        return activityState.pendingActivityResultCount(callerActivityToken);
    }

    public synchronized Optional<NewIntentDelivery> pollNewIntent(String activityToken) {
        return activityState.pollNewIntent(activityToken);
    }

    /** Returns whether a framework lifecycle callback still has a live virtual Activity owner. */
    public synchronized boolean containsActivity(String activityToken) {
        return activityState.containsActivity(activityToken);
    }

    /**
     * A framework-owned onNewIntent route is consumed by the client Instrumentation before the
     * asynchronous lifecycle evidence reaches the broker. Remove the matching ledger delivery
     * here so a successful callback cannot remain queued indefinitely. A missing delivery is
     * treated as an idempotent replay because process death/recovery may have already drained it.
     */
    public synchronized boolean acknowledgeNewIntent(String activityToken, String routeToken) {
        return activityState.acknowledgeNewIntent(activityToken, routeToken);
    }

    public synchronized List<NewIntentDelivery> drainNewIntents(String activityToken) {
        return activityState.drainNewIntents(activityToken);
    }

    public synchronized ActivityProcessIdentity processIdentity(String activityToken) {
        return activityState.processIdentity(activityToken);
    }

    public synchronized boolean saveInstanceState(String activityToken, SavedActivityState state) {
        return activityState.saveInstanceState(activityToken, state);
    }

    public synchronized Optional<SavedActivityState> savedInstanceState(String activityToken) {
        return activityState.savedInstanceState(activityToken);
    }

    public synchronized ConfigurationDecision handleConfigurationChange(
            String activityToken,
            String configurationToken,
            boolean handledByGuest) {
        return activityState.handleConfigurationChange(
                activityToken, configurationToken, handledByGuest);
    }

    public synchronized List<ActivityRecreation> recreateProcessGeneration(
            int virtualUserId,
            String packageName,
            String processName,
            long staleGeneration,
            long newGeneration) {
        return activityState.recreateProcessGeneration(
                virtualUserId, packageName, processName, staleGeneration, newGeneration);
    }

    public synchronized int invalidateProcessGeneration(
            int virtualUserId,
            String packageName,
            String processName,
            long staleGeneration) {
        return activityState.invalidateProcessGeneration(
                virtualUserId, packageName, processName, staleGeneration);
    }

    /** Returns tasks in back-to-front order; the last entry is the foreground task. */
    public synchronized List<TaskSnapshot> snapshot() {
        return tasks.values().stream().map(ActivityTaskMutableTask::snapshot)
                .collect(java.util.stream.Collectors.toList());
    }

    public synchronized List<TaskQuerySnapshot> runningTasks(
            int virtualUserId,
            String packageName,
            int maxCount) {
        return runningTasks(virtualUserId, packageName, "", maxCount);
    }

    public synchronized List<TaskQuerySnapshot> runningTasks(
            int virtualUserId,
            String packageName,
            String packageRevision,
            int maxCount) {
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedRevision = normalizeOptional(packageRevision);
        validateTaskQuery(virtualUserId, maxCount);
        List<TaskQuerySnapshot> result = new ArrayList<>();
        List<ActivityTaskMutableTask> ordered = new ArrayList<>(tasks.values());
        for (int index = ordered.size() - 1; index >= 0 && result.size() < maxCount; index--) {
            ActivityTaskMutableTask task = ordered.get(index);
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(normalizedPackageName)
                    && revisionActivityTaskMatches(task.packageRevision, normalizedRevision)) {
                result.add(querySnapshot(task, true));
            }
        }
        return List.copyOf(result);
    }

    public synchronized List<TaskQuerySnapshot> recentTasks(
            int virtualUserId,
            String packageName,
            int maxCount) {
        return recentTasks(virtualUserId, packageName, "", maxCount);
    }

    public synchronized List<TaskQuerySnapshot> recentTasks(
            int virtualUserId,
            String packageName,
            String packageRevision,
            int maxCount) {
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedRevision = normalizeOptional(packageRevision);
        validateTaskQuery(virtualUserId, maxCount);
        List<TaskQuerySnapshot> result = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
        List<ActivityTaskMutableTask> active = new ArrayList<>(tasks.values());
        for (int index = active.size() - 1; index >= 0 && result.size() < maxCount; index--) {
            ActivityTaskMutableTask task = active.get(index);
            if (task.virtualUserId != virtualUserId
                    || !task.packageName.equals(normalizedPackageName)
                    || !revisionActivityTaskMatches(task.packageRevision, normalizedRevision)
                    || task.excludedFromRecents) {
                continue;
            }
            TaskQuerySnapshot snapshot = querySnapshot(task, true);
            result.add(snapshot);
            seen.add(snapshot.taskId());
        }
        List<TaskQuerySnapshot> archived = new ArrayList<>(recentTasks.values());
        for (int index = archived.size() - 1; index >= 0 && result.size() < maxCount; index--) {
            TaskQuerySnapshot snapshot = archived.get(index);
            if (snapshot.virtualUserId() == virtualUserId
                    && snapshot.packageName().equals(normalizedPackageName)
                    && revisionActivityTaskMatches(snapshot.packageRevision(), normalizedRevision)
                    && !snapshot.excludedFromRecents()
                    && seen.add(snapshot.taskId())) {
                result.add(snapshot);
            }
        }
        return List.copyOf(result);
    }

    public synchronized boolean moveTaskToFront(
            int virtualUserId,
            String packageName,
            int taskId) {
        return moveTaskToFront(virtualUserId, packageName, "", taskId);
    }

    public synchronized boolean moveTaskToFront(
            int virtualUserId,
            String packageName,
            String packageRevision,
            int taskId) {
        ActivityTaskMutableTask task = requireOwnedTask(virtualUserId, packageName, packageRevision, taskId);
        ActivityTaskMutableTask currentFront = tasks.isEmpty() ? null
                : new ArrayList<>(tasks.values()).get(tasks.size() - 1);
        if (currentFront != null && currentFront.taskId == task.taskId) return false;
        moveTaskToFrontInternal(task.taskId);
        return true;
    }

    public synchronized boolean removeTask(
            int virtualUserId,
            String packageName,
            int taskId) {
        return removeTask(virtualUserId, packageName, "", taskId);
    }

    public synchronized boolean removeTask(
            int virtualUserId,
            String packageName,
            String packageRevision,
            int taskId) {
        ActivityTaskMutableTask task = requireOwnedTask(virtualUserId, packageName, packageRevision, taskId);
        tasks.remove(task.taskId);
        recentTasks.remove(task.taskId);
        for (ActivityTaskMutableActivity activity : new ArrayList<>(task.activities)) {
            finalizeActivity(activity, RESULT_CANCELED, "");
        }
        task.activities.clear();
        return true;
    }

    public synchronized boolean moveTaskToBack(
            int virtualUserId,
            String packageName,
            String packageRevision,
            int taskId) {
        ActivityTaskMutableTask task = requireOwnedTask(virtualUserId, packageName, packageRevision, taskId);
        if (tasks.size() < 2 || tasks.keySet().iterator().next() == task.taskId) return false;
        LinkedHashMap<Integer, ActivityTaskMutableTask> reordered = new LinkedHashMap<>();
        reordered.put(task.taskId, task);
        for (Map.Entry<Integer, ActivityTaskMutableTask> entry : tasks.entrySet()) {
            if (entry.getKey() != task.taskId) reordered.put(entry.getKey(), entry.getValue());
        }
        tasks.clear();
        tasks.putAll(reordered);
        return true;
    }

    public synchronized int finishAffinity(String activityToken) {
        ActivityTaskMutableActivity activity = requireActivity(activityToken);
        ActivityTaskMutableTask task = taskContaining(activity.token);
        int index = indexOfToken(task, activity.token);
        int removed = 0;
        for (int removeIndex = index; removeIndex >= 0; removeIndex--) {
            removeActivityAt(task, removeIndex, false, RESULT_CANCELED, "");
            removed++;
        }
        if (task.activities.isEmpty()) {
            archiveTask(task);
            tasks.remove(task.taskId);
        }
        return removed;
    }

    public synchronized boolean finishAndRemoveTask(String activityToken) {
        ActivityTaskMutableActivity activity = requireActivity(activityToken);
        ActivityTaskMutableTask task = taskContaining(activity.token);
        return removeTask(task.virtualUserId, task.packageName, task.packageRevision, task.taskId);
    }

    public synchronized int clearPackageRevision(
            int virtualUserId,
            String packageName,
            String retainedRevision) {
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedRevision = requireText(retainedRevision, "retainedRevision");
        int removed = 0;
        for (ActivityTaskMutableTask task : new ArrayList<>(tasks.values())) {
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(normalizedPackageName)
                    && !task.packageRevision.equals(normalizedRevision)) {
                removeTask(task.virtualUserId, task.packageName, task.packageRevision, task.taskId);
                removed++;
            }
        }
        recentTasks.entrySet().removeIf(entry -> {
            TaskQuerySnapshot task = entry.getValue();
            return task.virtualUserId() == virtualUserId
                    && task.packageName().equals(normalizedPackageName)
                    && !task.packageRevision().equals(normalizedRevision);
        });
        return removed;
    }

    public synchronized int clearPackageInstance(int virtualUserId, String packageName) {
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String normalizedPackageName = requireText(packageName, "packageName");
        int removed = 0;
        for (ActivityTaskMutableTask task : new ArrayList<>(tasks.values())) {
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(normalizedPackageName)) {
                removeTask(task.virtualUserId, task.packageName, task.packageRevision, task.taskId);
                removed++;
            }
        }
        recentTasks.entrySet().removeIf(entry -> {
            TaskQuerySnapshot task = entry.getValue();
            return task.virtualUserId() == virtualUserId
                    && task.packageName().equals(normalizedPackageName);
        });
        return removed;
    }

    /** Exact in-memory snapshot used to roll back a mutation when durable persistence fails. */
    public synchronized RollbackState captureRollbackState() {
        return checkpointCoordinator.captureRollbackState();
    }

    /** Restores an exact in-memory state captured before a failed durable mutation. */
    public synchronized void restoreRollbackState(RollbackState state) {
        checkpointCoordinator.restoreRollbackState(state);
    }

    public synchronized ActivityTaskCheckpoint checkpoint() {
        return checkpointCoordinator.checkpoint();
    }

    public synchronized ActivityTaskRestoreOutcome restore(ActivityTaskCheckpoint checkpoint) {
        return checkpointCoordinator.restore(checkpoint);
    }

    public synchronized int adoptRestoredProcessGeneration(
            int virtualUserId,
            String packageName,
            String processName,
            long processGeneration) {
        return adoptRestoredProcessGeneration(
                virtualUserId, packageName, "", processName, processGeneration);
    }

    public synchronized int adoptRestoredProcessGeneration(
            int virtualUserId,
            String packageName,
            String packageRevision,
            String processName,
            long processGeneration) {
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedRevision = normalizeOptional(packageRevision);
        String normalizedProcessName = requireText(processName, "processName");
        if (virtualUserId < 0 || processGeneration < 1) {
            throw new IllegalArgumentException("invalid restored process identity");
        }
        int adopted = 0;
        List<ActivityTaskMutableActivity> candidates = new ArrayList<>(activitiesByToken.values());
        for (ActivityTaskMutableActivity activity : candidates) {
            if (activity.restoredFromCheckpoint
                    && activity.identity.virtualUserId() == virtualUserId
                    && activity.identity.packageName().equals(normalizedPackageName)
                    && activity.processName.equals(normalizedProcessName)
                    && revisionActivityTaskMatches(taskContaining(activity.token).packageRevision,
                            normalizedRevision)) {
                rotateActivityToken(activity, processGeneration, RecreationReason.PROCESS_RESTART);
                activity.restoredFromCheckpoint = false;
                adopted++;
            }
        }
        return adopted;
    }

    /**
     * Returns whether the virtual task has to be rebound to a new Host task after a Host restart.
     * The virtual task/back stack remains durable, but the Android task owned by the previous Host
     * process is not assumed to survive force-stop, crash, or LMK.
     */
    public synchronized boolean hostTaskRebindRequired(int taskId) {
        ActivityTaskMutableTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown taskId: " + taskId);
        return task.hostTaskDetached;
    }

    /** Marks the physical Host task as attached after a route was consumed by ActivityThread. */
    public synchronized boolean attachHostTask(int taskId) {
        ActivityTaskMutableTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown taskId: " + taskId);
        if (!task.hostTaskDetached) return false;
        task.hostTaskDetached = false;
        return true;
    }

    public synchronized int taskCount() {
        return tasks.size();
    }

    public synchronized int activityCount() {
        return activitiesByToken.size();
    }

    /** Returns whether the live Activity is the root entry in its virtual task stack. */
    public synchronized boolean isRootActivity(String activityToken) {
        ActivityTaskMutableActivity activity = requireActivity(activityToken);
        ActivityTaskMutableTask task = taskContaining(activity.token);
        return !task.activities.isEmpty() && task.activities.get(0) == activity;
    }

    public synchronized void clearVirtualUser(int virtualUserId) {
        if (virtualUserId < 0) {
            throw new IllegalArgumentException("virtualUserId must be non-negative");
        }
        Iterator<Map.Entry<Integer, ActivityTaskMutableTask>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            ActivityTaskMutableTask task = iterator.next().getValue();
            if (task.virtualUserId == virtualUserId) {
                for (ActivityTaskMutableActivity activity : new ArrayList<>(task.activities)) {
                    finalizeActivity(activity, RESULT_CANCELED, "");
                }
                task.activities.clear();
                iterator.remove();
            }
        }
        recentTasks.entrySet().removeIf(entry -> entry.getValue().virtualUserId() == virtualUserId);
    }

    private static void validateTaskQuery(int virtualUserId, int maxCount) {
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (maxCount < 1 || maxCount > 100) {
            throw new IllegalArgumentException("maxCount must be between 1 and 100");
        }
    }

    private ActivityTaskMutableTask requireOwnedTask(
            int virtualUserId,
            String packageName,
            String packageRevision,
            int taskId) {
        if (virtualUserId < 0 || taskId < 1) throw new IllegalArgumentException("invalid task identity");
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedRevision = normalizeOptional(packageRevision);
        ActivityTaskMutableTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown taskId: " + taskId);
        if (task.virtualUserId != virtualUserId || !task.packageName.equals(normalizedPackageName)) {
            throw new SecurityException("TASK_OWNER_MISMATCH");
        }
        if (!revisionActivityTaskMatches(task.packageRevision, normalizedRevision)) {
            throw new SecurityException("TASK_REVISION_MISMATCH");
        }
        return task;
    }

    private TaskQuerySnapshot querySnapshot(ActivityTaskMutableTask task, boolean active) {
        ActivityTaskMutableActivity base = task.activities.isEmpty() ? null : task.activities.get(0);
        ActivityTaskMutableActivity top = top(task);
        return new TaskQuerySnapshot(
                task.taskId,
                task.virtualUserId,
                task.packageName,
                task.packageRevision,
                task.affinity,
                task.documentTask,
                task.documentLaunchMode,
                task.documentKey,
                active,
                task.excludedFromRecents,
                task.retainInRecents,
                task.activities.size(),
                base == null ? "" : base.identity.componentName(),
                top == null ? "" : top.identity.componentName(),
                task.lastActiveSequence,
                task.moveToFrontCount,
                task.rootIntentFlags,
                task.baseIntentAction,
                task.baseIntentDataUri,
                task.baseIntentMimeType,
                task.baseIntentCategories,
                task.lastActiveTimeMillis);
    }

    private void archiveTask(ActivityTaskMutableTask task) {
        if (task.excludedFromRecents) return;
        if (task.documentTask && !task.retainInRecents) {
            recentTasks.remove(task.taskId);
            return;
        }
        ActivityTaskMutableActivity base = task.activities.isEmpty() ? null : task.activities.get(0);
        ActivityTaskMutableActivity top = top(task);
        TaskQuerySnapshot snapshot = new TaskQuerySnapshot(
                task.taskId,
                task.virtualUserId,
                task.packageName,
                task.packageRevision,
                task.affinity,
                task.documentTask,
                task.documentLaunchMode,
                task.documentKey,
                false,
                task.excludedFromRecents,
                task.retainInRecents,
                task.activities.size(),
                base == null ? "" : base.identity.componentName(),
                top == null ? "" : top.identity.componentName(),
                task.lastActiveSequence,
                task.moveToFrontCount,
                task.rootIntentFlags,
                task.baseIntentAction,
                task.baseIntentDataUri,
                task.baseIntentMimeType,
                task.baseIntentCategories,
                task.lastActiveTimeMillis);
        recentTasks.remove(task.taskId);
        recentTasks.put(task.taskId, snapshot);
        trimRecentTasks();
    }

    private void trimRecentTasks() {
        while (recentTasks.size() > MAX_RECENT_TASKS) {
            Integer oldest = recentTasks.keySet().iterator().next();
            recentTasks.remove(oldest);
        }
    }

    private int maximumTaskId() {
        int maximum = 0;
        for (Integer taskId : tasks.keySet()) maximum = Math.max(maximum, taskId);
        for (Integer taskId : recentTasks.keySet()) maximum = Math.max(maximum, taskId);
        return maximum;
    }

    private long maximumActivationSequence() {
        long maximum = 0;
        for (ActivityTaskMutableTask task : tasks.values()) maximum = Math.max(maximum, task.lastActiveSequence);
        for (TaskQuerySnapshot task : recentTasks.values()) {
            maximum = Math.max(maximum, task.lastActiveSequence());
        }
        return maximum;
    }

    private void validateCallerTask(LaunchRequest request) {
        if (request.callerTaskId() == null) {
            return;
        }
        ActivityTaskMutableTask caller = tasks.get(request.callerTaskId());
        if (caller == null) {
            throw new IllegalArgumentException(
                    "callerTaskId does not exist: " + request.callerTaskId());
        }
        if (caller.virtualUserId != request.identity().virtualUserId()) {
            throw new SecurityException("Cross-virtual-user caller task is forbidden");
        }
        if (!caller.packageName.equals(request.identity().packageName())) {
            throw new SecurityException("Cross-package caller task is forbidden");
        }
        if (!caller.packageRevision.equals(request.packageRevision())) {
            throw new SecurityException("Cross-revision caller task is forbidden");
        }
    }

    private String resolveCallerActivityToken(LaunchRequest request) {
        if (request.callerTaskId() == null) return null;
        ActivityTaskMutableTask callerTask = tasks.get(request.callerTaskId());
        ActivityTaskMutableActivity caller = top(callerTask);
        if (caller == null) throw new IllegalArgumentException("callerTaskId has no live Activity");
        return caller.token;
    }

    private String resolveResultCallerToken(LaunchRequest request, String callerActivityToken) {
        if (request.requestCode() < 0) return null;
        if (callerActivityToken == null) {
            throw new IllegalArgumentException(
                    "requestCode requires a callerTaskId with a live top Activity");
        }
        return callerActivityToken;
    }

    private void validateForwardResult(LaunchRequest request, String callerActivityToken) {
        if (!LaunchFlags.has(request.flags(), LaunchFlags.FORWARD_RESULT)) return;
        if (request.requestCode() >= 0) {
            throw new IllegalArgumentException("FORWARD_RESULT cannot be combined with requestCode");
        }
        if (callerActivityToken == null) {
            throw new IllegalArgumentException("FORWARD_RESULT requires a live caller Activity");
        }
        ActivityTaskMutableActivity caller = requireActivity(callerActivityToken);
        if (caller.pendingResultLinks.isEmpty()) {
            throw new IllegalStateException("FORWARD_RESULT caller has no pending result owner");
        }
    }

    private static void validateLaunchFlagCombinations(LaunchRequest request) {
        boolean newTask = LaunchFlags.has(request.flags(), LaunchFlags.NEW_TASK);
        boolean newDocument = LaunchFlags.has(request.flags(), LaunchFlags.NEW_DOCUMENT);
        boolean multipleTask = LaunchFlags.has(request.flags(), LaunchFlags.MULTIPLE_TASK);
        boolean clearTask = LaunchFlags.has(request.flags(), LaunchFlags.CLEAR_TASK);
        if (multipleTask && !newTask && !newDocument) {
            throw new IllegalArgumentException("MULTIPLE_TASK requires NEW_TASK or NEW_DOCUMENT");
        }
        if (clearTask && !newTask) {
            throw new IllegalArgumentException("CLEAR_TASK requires NEW_TASK");
        }
        if (request.documentLaunchMode() == DocumentLaunchMode.NEVER && newDocument) {
            throw new IllegalArgumentException("documentLaunchMode NEVER forbids NEW_DOCUMENT");
        }
        if (request.documentLaunchMode() == DocumentLaunchMode.INTO_EXISTING
                && request.documentKey().isEmpty()) {
            throw new IllegalArgumentException("INTO_EXISTING requires a stable documentKey");
        }
        if (request.documentLaunchMode() == DocumentLaunchMode.ALWAYS
                && !newTask && request.callerTaskId() != null) {
            // ALWAYS is modeled as a new document task even when the caller omitted NEW_TASK.
            return;
        }
        if (request.launchMode() == LaunchMode.SINGLE_INSTANCE
                && request.documentLaunchMode() == DocumentLaunchMode.INTO_EXISTING) {
            throw new IllegalArgumentException(
                    "singleInstance cannot use INTO_EXISTING document mode");
        }
    }

    private ActivityTaskMutableTask selectTargetTask(LaunchRequest request) {
        boolean forceNew = request.launchMode() == LaunchMode.SINGLE_INSTANCE
                || LaunchFlags.has(request.flags(), LaunchFlags.MULTIPLE_TASK)
                || LaunchFlags.has(request.flags(), LaunchFlags.NEW_DOCUMENT)
                || request.documentLaunchMode() == DocumentLaunchMode.ALWAYS;
        if (forceNew) {
            return null;
        }

        ActivityTaskMutableTask candidate;
        if (LaunchFlags.has(request.flags(), LaunchFlags.NEW_TASK)
                || request.launchMode() == LaunchMode.SINGLE_TASK) {
            candidate = findByAffinity(
                    request.identity().virtualUserId(),
                    request.identity().packageName(),
                    request.packageRevision(),
                    request.taskAffinity());
        } else if (request.callerTaskId() != null) {
            candidate = tasks.get(request.callerTaskId());
        } else {
            candidate = findFrontTaskForPackage(
                    request.identity().virtualUserId(),
                    request.identity().packageName(),
                    request.packageRevision());
        }

        if (candidate != null && !canAcceptActivity(candidate, request.identity())) {
            return null;
        }
        return candidate;
    }

    private ActivityTaskMutableTask createTask(LaunchRequest request) {
        if (tasks.size() >= MAX_ACTIVE_TASKS) {
            throw new IllegalStateException("ACTIVITY_TASK_LIMIT_EXCEEDED");
        }
        int id = nextTaskId.getAndIncrement();
        boolean documentTask = request.documentRequested()
                && request.documentLaunchMode() != DocumentLaunchMode.NEVER;
        DocumentLaunchMode documentMode = documentTask
                ? effectiveDocumentMode(request) : DocumentLaunchMode.NONE;
        ActivityTaskMutableTask task = new ActivityTaskMutableTask(
                id,
                request.identity().virtualUserId(),
                request.identity().packageName(),
                request.packageRevision(),
                request.taskAffinity(),
                documentTask,
                documentMode,
                documentTask ? request.documentKey() : "",
                request.flags(),
                request.intentAction(),
                request.intentDataUri(),
                request.intentMimeType(),
                request.intentCategories(),
                LaunchFlags.has(request.flags(), LaunchFlags.EXCLUDE_FROM_RECENTS),
                LaunchFlags.has(request.flags(), LaunchFlags.RETAIN_IN_RECENTS),
                nextActivationSequence.getAndIncrement(),
                System.currentTimeMillis());
        tasks.put(id, task);
        return task;
    }

    private ActivityTaskMutableActivity createActivity(LaunchRequest request) {
        if (activitiesByToken.size() >= MAX_ACTIVE_ACTIVITIES) {
            throw new IllegalStateException("ACTIVITY_INSTANCE_LIMIT_EXCEEDED");
        }
        String token = UUID.randomUUID().toString();
        ActivityTaskMutableActivity activity = new ActivityTaskMutableActivity(
                request.identity(),
                UUID.randomUUID().toString(),
                token,
                request.launchMode(),
                request.processName(),
                request.processGeneration(),
                request.resultWho(),
                request.requestCode(),
                request.flags(),
                request.activityInfoFlags(),
                request.taskAffinity(),
                ActivityInfoTaskFlags.has(
                        request.activityInfoFlags(), ActivityInfoTaskFlags.ALLOW_TASK_REPARENTING),
                LaunchFlags.has(request.flags(), LaunchFlags.NO_HISTORY));
        activitiesByToken.put(token, activity);
        return activity;
    }

    private ActivityTaskMutableTask findByAffinity(
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity) {
        ActivityTaskMutableTask found = null;
        for (ActivityTaskMutableTask task : tasks.values()) {
            if (task.virtualUserId == virtualUserId
                    && task.affinity.equals(affinity)
                    && !task.documentTask) {
                // A task affinity is a virtual Android-world namespace, not a package-private
                // key. Preserve revision fencing for same-package reuse, but allow an exported
                // cross-package launch (already authorized by the broker resolver) to reuse the
                // matching affinity task just like ActivityTaskManager/VA/NBB.
                boolean samePackage = task.packageName.equals(packageName);
                if (samePackage && !task.packageRevision.equals(packageRevision)) continue;
                found = task;
            }
        }
        return found;
    }

    private ActivityTaskMatch findDocumentTask(LaunchRequest request) {
        ActivityTaskMatch found = null;
        for (ActivityTaskMutableTask task : tasks.values()) {
            if (!task.documentTask
                    || task.virtualUserId != request.identity().virtualUserId()
                    || !task.packageName.equals(request.identity().packageName())
                    || !task.packageRevision.equals(request.packageRevision())
                    || !task.documentKey.equals(request.documentKey())
                    || task.activities.isEmpty()) {
                continue;
            }
            ActivityTaskMutableActivity root = task.activities.get(0);
            if (root.identity.equals(request.identity())) found = new ActivityTaskMatch(task, 0);
        }
        return found;
    }

    private static DocumentLaunchMode effectiveDocumentMode(LaunchRequest request) {
        if (request.documentLaunchMode() != DocumentLaunchMode.NONE) {
            return request.documentLaunchMode();
        }
        return LaunchFlags.has(request.flags(), LaunchFlags.NEW_DOCUMENT)
                ? DocumentLaunchMode.ALWAYS : DocumentLaunchMode.NONE;
    }

    private ActivityTaskMutableTask findFrontTaskForPackage(
            int virtualUserId,
            String packageName,
            String packageRevision) {
        ActivityTaskMutableTask found = null;
        for (ActivityTaskMutableTask task : tasks.values()) {
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(packageName)
                    && task.packageRevision.equals(packageRevision)) {
                found = task;
            }
        }
        return found;
    }

    private ActivityTaskMatch findAcrossTasks(ActivityIdentity identity, String packageRevision) {
        ActivityTaskMatch found = null;
        for (ActivityTaskMutableTask task : tasks.values()) {
            if (task.virtualUserId != identity.virtualUserId()
                    || !task.packageName.equals(identity.packageName())
                    || !task.packageRevision.equals(packageRevision)) {
                continue;
            }
            int index = findIndex(task, identity);
            if (index >= 0) {
                found = new ActivityTaskMatch(task, index);
            }
        }
        return found;
    }

    private static int findIndex(ActivityTaskMutableTask task, ActivityIdentity identity) {
        for (int index = task.activities.size() - 1; index >= 0; index--) {
            if (task.activities.get(index).identity.equals(identity)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean canAcceptActivity(ActivityTaskMutableTask task, ActivityIdentity identity) {
        for (ActivityTaskMutableActivity activity : task.activities) {
            if (activity.launchMode == LaunchMode.SINGLE_INSTANCE
                    && !activity.identity.equals(identity)) {
                return false;
            }
        }
        return true;
    }

    private int retainOnly(ActivityTaskMutableTask task, ActivityTaskMutableActivity retained) {
        int removed = 0;
        for (int index = task.activities.size() - 1; index >= 0; index--) {
            if (task.activities.get(index) != retained) {
                removeActivityAt(task, index, false, RESULT_CANCELED, "");
                removed++;
            }
        }
        ensureTaskRegistered(task);
        return removed;
    }

    private int clearAbove(ActivityTaskMutableTask task, int index) {
        int removed = 0;
        while (task.activities.size() > index + 1) {
            removeActivityAt(
                    task,
                    task.activities.size() - 1,
                    true,
                    RESULT_CANCELED,
                    "");
            removed++;
        }
        return removed;
    }

    /**
     * Applies the broker equivalent of ActivityTaskManager's resetTaskIfNeeded pass.
     *
     * <p>The task root is never removed by a reset. A launcher Activity with
     * {@code FLAG_CLEAR_TASK_ON_LAUNCH} requests a force reset; otherwise only Activities whose
     * virtual ActivityInfo carries {@code FLAG_FINISH_ON_TASK_LAUNCH} are removed. A root carrying
     * {@code FLAG_ALWAYS_RETAIN_TASK_STATE} suppresses ordinary pruning, matching the platform
     * contract while still allowing an explicit force reset.</p>
     */
    private int resetTaskIfNeeded(ActivityTaskMutableTask task, LaunchRequest request) {
        if (task == null) return 0;
        reparentActivitiesToTask(task);
        if (task.activities.size() <= 1) return 0;
        ActivityTaskMutableActivity root = task.activities.get(0);
        boolean forceReset = ActivityInfoTaskFlags.has(
                request.activityInfoFlags(), ActivityInfoTaskFlags.CLEAR_TASK_ON_LAUNCH);
        boolean rootRetains = ActivityInfoTaskFlags.has(
                root.activityInfoFlags, ActivityInfoTaskFlags.ALWAYS_RETAIN_TASK_STATE);
        if (rootRetains && !forceReset) return 0;

        int removed = 0;
        for (int index = task.activities.size() - 1; index > 0; index--) {
            ActivityTaskMutableActivity activity = task.activities.get(index);
            boolean finishOnReset = ActivityInfoTaskFlags.has(
                    activity.activityInfoFlags, ActivityInfoTaskFlags.FINISH_ON_TASK_LAUNCH);
            if (forceReset || finishOnReset) {
                removeActivityAt(task, index, false, RESULT_CANCELED, "");
                removed++;
            }
        }
        ensureTaskRegistered(task);
        return removed;
    }

    /**
     * Applies the ActivityInfo {@code allowTaskReparenting} edge during a task reset.
     *
     * <p>The old implementation parsed and projected the flag but never moved the Activity. That
     * made cross-package task affinity appear correct in PackageManager while leaving the actual
     * Activity stack inconsistent with Android. Reparenting is deliberately limited to the same
     * virtual user, non-document tasks, matching affinity, and a target that can legally accept the
     * incoming Activity (notably preserving singleInstance isolation).</p>
     */
    private void reparentActivitiesToTask(ActivityTaskMutableTask target) {
        if (target == null || target.documentTask || target.affinity.isEmpty()) return;
        for (ActivityTaskMutableTask source : new ArrayList<>(tasks.values())) {
            if (source == target || source.documentTask
                    || source.virtualUserId != target.virtualUserId) continue;
            for (ActivityTaskMutableActivity activity : new ArrayList<>(source.activities)) {
                if (!activity.allowTaskReparenting
                        || !activity.taskAffinity.equals(target.affinity)
                        || !canAcceptActivity(target, activity.identity)
                        || (activity.launchMode == LaunchMode.SINGLE_INSTANCE
                        && !target.activities.isEmpty())) {
                    continue;
                }
                source.activities.remove(activity);
                target.activities.add(activity);
            }
            if (source.activities.isEmpty()) {
                recentTasks.remove(source.taskId);
                tasks.remove(source.taskId);
            }
        }
        ensureTaskRegistered(target);
    }

    private void clearTaskForReuse(ActivityTaskMutableTask task) {
        while (!task.activities.isEmpty()) {
            removeActivityAt(
                    task,
                    task.activities.size() - 1,
                    false,
                    RESULT_CANCELED,
                    "");
        }
        ensureTaskRegistered(task);
    }

    private void removeActivityAt(
            ActivityTaskMutableTask task,
            int index,
            boolean removeEmptyTask,
            int resultCode,
            String dataToken) {
        removeActivityAt(task, index, removeEmptyTask, resultCode, dataToken, ResultIntentSnapshot.EMPTY);
    }

    private void removeActivityAt(
            ActivityTaskMutableTask task,
            int index,
            boolean removeEmptyTask,
            int resultCode,
            String dataToken,
            ResultIntentSnapshot resultIntent) {
        boolean archiveWhenEmpty = removeEmptyTask && task.activities.size() == 1;
        if (archiveWhenEmpty) archiveTask(task);
        ActivityTaskMutableActivity removed = task.activities.remove(index);
        if (recordingLaunchRemovals) lastLaunchRemovedActivityTokens.add(removed.token);
        finalizeActivity(removed, resultCode, dataToken, resultIntent);
        if (removeEmptyTask && task.activities.isEmpty()) {
            tasks.remove(task.taskId);
        }
    }

    boolean removeActivityByToken(String token, int resultCode, String dataToken) {
        return removeActivityByToken(token, resultCode, dataToken, ResultIntentSnapshot.EMPTY);
    }

    boolean removeActivityByToken(
            String token,
            int resultCode,
            String dataToken,
            ResultIntentSnapshot resultIntent) {
        ActivityTaskMutableActivity activity = activitiesByToken.get(Objects.requireNonNull(token, "token"));
        if (activity == null) {
            return false;
        }
        for (ActivityTaskMutableTask task : new ArrayList<>(tasks.values())) {
            for (int index = 0; index < task.activities.size(); index++) {
                if (task.activities.get(index).token.equals(token)) {
                    removeActivityAt(task, index, true, resultCode, dataToken, resultIntent);
                    return true;
                }
            }
        }
        finalizeActivity(activity, resultCode, dataToken, resultIntent);
        return false;
    }

    private void finalizeActivity(
            ActivityTaskMutableActivity activity,
            int resultCode,
            String dataToken) {
        finalizeActivity(activity, resultCode, dataToken, ResultIntentSnapshot.EMPTY);
    }

    private void finalizeActivity(
            ActivityTaskMutableActivity activity,
            int resultCode,
            String dataToken,
            ResultIntentSnapshot resultIntent) {
        deliverPendingResults(activity, resultCode, dataToken, resultIntent);
        activity.lifecycleState = LifecycleState.DESTROYED;
        activitiesByToken.remove(activity.token);
        resultDeliveriesByCaller.remove(activity.token);
        for (ActivityTaskMutableActivity other : activitiesByToken.values()) {
            other.pendingResultLinks.removeIf(link -> link.callerActivityToken().equals(activity.token));
        }
        activity.pendingNewIntents.clear();
    }

    private void deliverPendingResults(
            ActivityTaskMutableActivity callee,
            int resultCode,
            String dataToken,
            ResultIntentSnapshot resultIntent) {
        for (ActivityTaskPendingResultLink link : callee.pendingResultLinks) {
            if (!activitiesByToken.containsKey(link.callerActivityToken())) {
                continue;
            }
            ActivityResultDelivery delivery = new ActivityResultDelivery(
                    link.callerActivityToken(),
                    callee.token,
                    link.resultWho(),
                    link.registryKey(),
                    link.requestCode(),
                    resultCode,
                    link.intentSenderToken(),
                    dataToken,
                    resultIntent);
            resultDeliveriesByCaller
                    .computeIfAbsent(link.callerActivityToken(), ignored -> new ArrayList<>())
                    .add(delivery);
        }
        callee.pendingResultLinks.clear();
    }

    private void registerResultLink(
            ActivityTaskMutableActivity callee,
            String callerActivityToken,
            String launchCallerActivityToken,
            LaunchRequest request) {
        if (LaunchFlags.has(request.flags(), LaunchFlags.FORWARD_RESULT)) {
            ActivityTaskMutableActivity forwardingCaller = requireActivity(launchCallerActivityToken);
            callee.pendingResultLinks.addAll(forwardingCaller.pendingResultLinks);
            forwardingCaller.pendingResultLinks.clear();
            return;
        }
        if (callerActivityToken == null || request.requestCode() < 0) return;
        if (!request.activityResultKey().isEmpty()) {
            ActivityTaskMutableActivity caller = requireActivity(callerActivityToken);
            Integer registeredCode = caller.resultRegistrations.get(request.activityResultKey());
            if (registeredCode == null || registeredCode != request.requestCode()) {
                throw new SecurityException("ACTIVITY_RESULT_REGISTRATION_MISMATCH");
            }
        }
        callee.pendingResultLinks.add(new ActivityTaskPendingResultLink(
                callerActivityToken,
                request.resultWho(),
                request.activityResultKey(),
                request.requestCode(),
                request.intentSenderToken()));
    }

    private void enqueueNewIntent(ActivityTaskMutableActivity activity, LaunchRequest request) {
        long sequence = nextNewIntentSequence.getAndIncrement();
        activity.newIntentCount++;
        activity.pendingNewIntents.add(new NewIntentDelivery(
                activity.token,
                sequence,
                request.routeToken(),
                request.flags(),
                request.callerTaskId(),
                request.resultWho(),
                request.requestCode()));
    }

    ActivityRecreation rotateActivityToken(
            ActivityTaskMutableActivity activity,
            long newProcessGeneration,
            RecreationReason reason) {
        String previousToken = activity.token;
        long previousGeneration = activity.processGeneration;
        String currentToken = UUID.randomUUID().toString();

        activitiesByToken.remove(previousToken);
        activity.token = currentToken;
        activity.processGeneration = newProcessGeneration;
        activity.lifecycleState = LifecycleState.INITIALIZED;
        activity.recreationCount++;
        boolean preserveTransportPayloads = reason == RecreationReason.CONFIGURATION_CHANGE;
        if (preserveTransportPayloads) {
            rewriteNewIntentTargets(activity, currentToken);
        } else {
            activity.pendingNewIntents.clear();
        }
        activitiesByToken.put(currentToken, activity);
        rewriteTokenReferences(previousToken, currentToken, preserveTransportPayloads);

        return new ActivityRecreation(
                previousToken,
                currentToken,
                previousGeneration,
                newProcessGeneration,
                reason);
    }

    private static void rewriteNewIntentTargets(ActivityTaskMutableActivity activity, String currentToken) {
        for (int index = 0; index < activity.pendingNewIntents.size(); index++) {
            NewIntentDelivery delivery = activity.pendingNewIntents.get(index);
            activity.pendingNewIntents.set(index, new NewIntentDelivery(
                    currentToken,
                    delivery.sequence(),
                    delivery.routeToken(),
                    delivery.flags(),
                    delivery.sourceTaskId(),
                    delivery.resultWho(),
                    delivery.requestCode()));
        }
    }

    private void rewriteTokenReferences(
            String previousToken,
            String currentToken,
            boolean preserveCallerDeliveries) {
        for (ActivityTaskMutableActivity activity : activitiesByToken.values()) {
            for (int index = 0; index < activity.pendingResultLinks.size(); index++) {
                ActivityTaskPendingResultLink link = activity.pendingResultLinks.get(index);
                if (link.callerActivityToken().equals(previousToken)) {
                    activity.pendingResultLinks.set(index, new ActivityTaskPendingResultLink(
                            currentToken,
                            link.resultWho(),
                            link.registryKey(),
                            link.requestCode(),
                            link.intentSenderToken()));
                }
            }
        }

        List<ActivityResultDelivery> moved = resultDeliveriesByCaller.remove(previousToken);
        LinkedHashMap<String, List<ActivityResultDelivery>> rewritten = new LinkedHashMap<>();
        for (Map.Entry<String, List<ActivityResultDelivery>> entry
                : resultDeliveriesByCaller.entrySet()) {
            String callerKey = entry.getKey().equals(previousToken)
                    ? currentToken
                    : entry.getKey();
            List<ActivityResultDelivery> deliveries = rewritten.computeIfAbsent(
                    callerKey,
                    ignored -> new ArrayList<>());
            for (ActivityResultDelivery delivery : entry.getValue()) {
                deliveries.add(rewriteDeliveryToken(delivery, previousToken, currentToken));
            }
        }
        resultDeliveriesByCaller.clear();
        resultDeliveriesByCaller.putAll(rewritten);
        if (preserveCallerDeliveries && moved != null) {
            List<ActivityResultDelivery> deliveries = resultDeliveriesByCaller.computeIfAbsent(
                    currentToken,
                    ignored -> new ArrayList<>());
            for (ActivityResultDelivery delivery : moved) {
                deliveries.add(rewriteDeliveryToken(delivery, previousToken, currentToken));
            }
        }
    }

    private static ActivityResultDelivery rewriteDeliveryToken(
            ActivityResultDelivery delivery,
            String previousToken,
            String currentToken) {
        String callerToken = delivery.callerActivityToken().equals(previousToken)
                ? currentToken
                : delivery.callerActivityToken();
        String calleeToken = delivery.calleeActivityToken().equals(previousToken)
                ? currentToken
                : delivery.calleeActivityToken();
        return new ActivityResultDelivery(
                callerToken,
                calleeToken,
                delivery.resultWho(),
                delivery.registryKey(),
                delivery.requestCode(),
                delivery.resultCode(),
                delivery.intentSenderToken(),
                delivery.dataToken(),
                delivery.resultIntent());
    }

    ActivityTaskMutableActivity requireActivity(String token) {
        ActivityTaskMutableActivity activity = activitiesByToken.get(Objects.requireNonNull(token, "token"));
        if (activity == null) {
            throw new IllegalArgumentException("Unknown activity token: " + token);
        }
        return activity;
    }

    private ActivityTaskMutableTask taskContaining(String activityToken) {
        for (ActivityTaskMutableTask task : tasks.values()) {
            if (indexOfToken(task, activityToken) >= 0) return task;
        }
        throw new IllegalArgumentException("Activity token has no live task: " + activityToken);
    }

    private static int indexOfToken(ActivityTaskMutableTask task, String activityToken) {
        for (int index = 0; index < task.activities.size(); index++) {
            if (task.activities.get(index).token.equals(activityToken)) return index;
        }
        return -1;
    }

    private void ensureTaskRegistered(ActivityTaskMutableTask task) {
        tasks.putIfAbsent(task.taskId, task);
    }

    private void moveTaskToFrontInternal(int taskId) {
        ActivityTaskMutableTask previousFront = tasks.isEmpty() ? null
                : new ArrayList<>(tasks.values()).get(tasks.size() - 1);
        ActivityTaskMutableTask task = tasks.remove(taskId);
        if (task != null) {
            if (previousFront != null && previousFront.taskId != taskId) {
                removeNoHistoryTop(previousFront);
            }
            task.lastActiveSequence = nextActivationSequence.getAndIncrement();
            task.lastActiveTimeMillis = System.currentTimeMillis();
            task.moveToFrontCount++;
            recentTasks.remove(taskId);
            tasks.put(taskId, task);
        }
    }

    private void removeNoHistoryTop(ActivityTaskMutableTask task) {
        ActivityTaskMutableActivity top = top(task);
        if (top == null || !top.noHistory) return;
        removeActivityAt(task, task.activities.size() - 1, true, RESULT_CANCELED, "");
    }

    private void retireNoHistoryCaller(String callerActivityToken, String selectedActivityToken) {
        if (callerActivityToken == null || callerActivityToken.equals(selectedActivityToken)) return;
        ActivityTaskMutableActivity caller = activitiesByToken.get(callerActivityToken);
        if (caller == null || !caller.noHistory) return;
        removeActivityByToken(callerActivityToken, RESULT_CANCELED, "");
    }

    private static ActivityTaskMutableActivity top(ActivityTaskMutableTask task) {
        return task == null || task.activities.isEmpty()
                ? null
                : task.activities.get(task.activities.size() - 1);
    }

    private LaunchDecision completeDecision(
            LaunchAction action,
            ActivityTaskMutableTask task,
            ActivityTaskMutableActivity activity,
            int removedActivityCount,
            boolean createdNewTask,
            String routeToken,
            String callerActivityToken) {
        retireNoHistoryCaller(callerActivityToken, activity.token);
        moveTaskToFrontInternal(task.taskId);
        return new LaunchDecision(
                action,
                task.taskId,
                activity.token,
                routeToken,
                removedActivityCount,
                createdNewTask,
                task.hostTaskDetached);
    }











    private static boolean revisionActivityTaskMatches(String taskRevision, String requestedRevision) {
        return requestedRevision.isEmpty() || taskRevision.equals(requestedRevision);
    }

    public static final class RollbackState {
        final int nextTaskId;
        final long nextNewIntentSequence;
        final long nextConfigurationSequence;
        final long nextActivationSequence;
        final LinkedHashMap<Integer, ActivityTaskMutableTask> tasks;
        final LinkedHashMap<Integer, TaskQuerySnapshot> recentTasks;
        final LinkedHashMap<String, ActivityTaskMutableActivity> activitiesByToken;
        final LinkedHashMap<String, List<ActivityResultDelivery>> resultDeliveriesByCaller;

        RollbackState(
                int nextTaskId,
                long nextNewIntentSequence,
                long nextConfigurationSequence,
                long nextActivationSequence,
                LinkedHashMap<Integer, ActivityTaskMutableTask> tasks,
                LinkedHashMap<Integer, TaskQuerySnapshot> recentTasks,
                LinkedHashMap<String, ActivityTaskMutableActivity> activitiesByToken,
                LinkedHashMap<String, List<ActivityResultDelivery>> resultDeliveriesByCaller) {
            this.nextTaskId = nextTaskId;
            this.nextNewIntentSequence = nextNewIntentSequence;
            this.nextConfigurationSequence = nextConfigurationSequence;
            this.nextActivationSequence = nextActivationSequence;
            this.tasks = tasks;
            this.recentTasks = recentTasks;
            this.activitiesByToken = activitiesByToken;
            this.resultDeliveriesByCaller = resultDeliveriesByCaller;
        }
    }

}
