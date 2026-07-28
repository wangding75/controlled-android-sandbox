package com.warden.controlledsandbox.framework.activity;


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
    private static final int MAX_RECENT_TASKS = 64;

    private final AtomicLong nextConfigurationSequence = new AtomicLong(1);
    private final AtomicLong nextActivationSequence = new AtomicLong(1);
    private final LinkedHashMap<Integer, MutableTask> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, TaskQuerySnapshot> recentTasks = new LinkedHashMap<>();
    private final Map<String, MutableActivity> activitiesByToken = new LinkedHashMap<>();
    private final Map<String, List<ActivityResultDelivery>> resultDeliveriesByCaller =
            new LinkedHashMap<>();

    public synchronized LaunchDecision launch(LaunchRequest request) {
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
            Match document = findDocumentTask(request);
            if (document != null) {
                MutableActivity root = document.task.activities.get(0);
                int removed = clearAbove(document.task, 0);
                enqueueNewIntent(root, request);
                registerResultLink(root, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.CLEARED_TOP,
                        document.task,
                        root,
                        removed,
                        false,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        if (!clearTask && request.launchMode() == LaunchMode.SINGLE_INSTANCE) {
            Match global = findAcrossTasks(request.identity(), request.packageRevision());
            if (global != null) {
                MutableActivity activity = global.task.activities.get(global.index);
                int removed = retainOnly(global.task, activity);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.DELIVERED_NEW_INTENT,
                        global.task,
                        activity,
                        removed,
                        false,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        if (!clearTask && request.launchMode() == LaunchMode.SINGLE_TASK) {
            Match global = findAcrossTasks(request.identity(), request.packageRevision());
            if (global != null) {
                int removed = clearAbove(global.task, global.index);
                MutableActivity activity = global.task.activities.get(global.index);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.CLEARED_TOP,
                        global.task,
                        activity,
                        removed,
                        false,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        MutableTask target = selectTargetTask(request);
        boolean createdTask = false;
        if (target == null) {
            target = createTask(request);
            createdTask = true;
        }

        if (clearTask) {
            clearTaskForReuse(target);
        }

        if (request.launchMode() == LaunchMode.SINGLE_INSTANCE_PER_TASK) {
            int existingIndex = findIndex(target, request.identity());
            if (existingIndex >= 0) {
                int removed = clearAbove(target, existingIndex);
                MutableActivity activity = target.activities.get(existingIndex);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.DELIVERED_NEW_INTENT,
                        target,
                        activity,
                        removed,
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
                MutableActivity activity = target.activities.remove(existingIndex);
                target.activities.add(activity);
                registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                return completeDecision(
                        LaunchAction.REORDERED_TO_FRONT,
                        target,
                        activity,
                        0,
                        createdTask,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        if (LaunchFlags.has(request.flags(), LaunchFlags.CLEAR_TOP)) {
            int existingIndex = findIndex(target, request.identity());
            if (existingIndex >= 0) {
                int removed = clearAbove(target, existingIndex);
                MutableActivity activity = target.activities.get(existingIndex);
                boolean deliver = request.launchMode() != LaunchMode.STANDARD
                        || LaunchFlags.has(request.flags(), LaunchFlags.SINGLE_TOP);
                if (deliver) {
                    enqueueNewIntent(activity, request);
                    registerResultLink(activity, resultCallerToken, callerActivityToken, request);
                    return completeDecision(
                            LaunchAction.CLEARED_TOP,
                            target,
                            activity,
                            removed,
                            createdTask,
                            request.routeToken(),
                            callerActivityToken);
                }
                removeActivityAt(target, existingIndex, false, RESULT_CANCELED, "");
                removed++;
                MutableActivity replacement = createActivity(request);
                target.activities.add(replacement);
                registerResultLink(replacement, resultCallerToken, callerActivityToken, request);
                ensureTaskRegistered(target);
                return completeDecision(
                        LaunchAction.CLEARED_TOP,
                        target,
                        replacement,
                        removed,
                        createdTask,
                        request.routeToken(),
                        callerActivityToken);
            }
        }

        MutableActivity top = top(target);
        boolean singleTop = request.launchMode() == LaunchMode.SINGLE_TOP
                || LaunchFlags.has(request.flags(), LaunchFlags.SINGLE_TOP);
        if (singleTop && top != null && top.identity.equals(request.identity())) {
            enqueueNewIntent(top, request);
            registerResultLink(top, resultCallerToken, callerActivityToken, request);
            return completeDecision(
                    LaunchAction.DELIVERED_NEW_INTENT,
                    target,
                    top,
                    0,
                    createdTask,
                    request.routeToken(),
                    callerActivityToken);
        }

        if (request.launchMode() == LaunchMode.SINGLE_INSTANCE && !target.activities.isEmpty()) {
            target = createTask(request);
            createdTask = true;
        }

        MutableActivity created = createActivity(request);
        target.activities.add(created);
        registerResultLink(created, resultCallerToken, callerActivityToken, request);
        ensureTaskRegistered(target);
        return completeDecision(
                createdTask ? LaunchAction.CREATED_TASK : LaunchAction.CREATED_ACTIVITY,
                target,
                created,
                0,
                createdTask,
                request.routeToken(),
                callerActivityToken);
    }

    public synchronized boolean transition(String token, LifecycleState next) {
        MutableActivity activity = requireActivity(token);
        Objects.requireNonNull(next, "next");
        if (activity.lifecycleState == next) {
            return false;
        }
        if (!activity.lifecycleState.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Invalid lifecycle transition " + activity.lifecycleState + " -> " + next);
        }
        if (next == LifecycleState.DESTROYED) {
            return removeActivityByToken(token, RESULT_CANCELED, "");
        }
        activity.lifecycleState = next;
        return true;
    }

    public synchronized boolean finish(String token) {
        return finishWithResult(token, RESULT_CANCELED, "");
    }

    public synchronized boolean finishWithResult(String token, int resultCode, String dataToken) {
        String normalizedDataToken = dataToken == null ? "" : dataToken;
        return removeActivityByToken(token, resultCode, normalizedDataToken);
    }

    public synchronized List<ActivityResultDelivery> drainActivityResults(String callerActivityToken) {
        MutableActivity caller = requireActivity(callerActivityToken);
        List<ActivityResultDelivery> deliveries = resultDeliveriesByCaller.remove(caller.token);
        return deliveries == null ? List.of() : List.copyOf(deliveries);
    }

    public synchronized int pendingActivityResultCount(String callerActivityToken) {
        MutableActivity caller = requireActivity(callerActivityToken);
        List<ActivityResultDelivery> deliveries = resultDeliveriesByCaller.get(caller.token);
        return deliveries == null ? 0 : deliveries.size();
    }

    public synchronized Optional<NewIntentDelivery> pollNewIntent(String activityToken) {
        MutableActivity activity = requireActivity(activityToken);
        if (activity.pendingNewIntents.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(activity.pendingNewIntents.remove(0));
    }

    public synchronized List<NewIntentDelivery> drainNewIntents(String activityToken) {
        MutableActivity activity = requireActivity(activityToken);
        List<NewIntentDelivery> deliveries = List.copyOf(activity.pendingNewIntents);
        activity.pendingNewIntents.clear();
        return deliveries;
    }

    public synchronized ActivityProcessIdentity processIdentity(String activityToken) {
        MutableActivity activity = requireActivity(activityToken);
        return new ActivityProcessIdentity(
                activity.identity.virtualUserId(),
                activity.identity.packageName(),
                activity.processName,
                activity.processGeneration);
    }

    public synchronized boolean saveInstanceState(String activityToken, SavedActivityState state) {
        MutableActivity activity = requireActivity(activityToken);
        Objects.requireNonNull(state, "state");
        if (activity.savedState != null) {
            if (state.version() < activity.savedState.version()) {
                throw new IllegalArgumentException("saved-state version must be monotonic");
            }
            if (state.version() == activity.savedState.version()) {
                if (!state.equals(activity.savedState)) {
                    throw new IllegalArgumentException(
                            "saved-state version collision with different content");
                }
                return false;
            }
        }
        activity.savedState = state;
        return true;
    }

    public synchronized Optional<SavedActivityState> savedInstanceState(String activityToken) {
        return Optional.ofNullable(requireActivity(activityToken).savedState);
    }

    public synchronized ConfigurationDecision handleConfigurationChange(
            String activityToken,
            String configurationToken,
            boolean handledByGuest) {
        MutableActivity activity = requireActivity(activityToken);
        String normalizedConfigurationToken = requireText(configurationToken, "configurationToken");
        long sequence = nextConfigurationSequence.getAndIncrement();
        activity.configurationCount++;
        activity.lastConfigurationToken = normalizedConfigurationToken;
        if (handledByGuest) {
            return new ConfigurationDecision(
                    ConfigurationAction.DELIVERED_TO_EXISTING,
                    activity.token,
                    activity.token,
                    sequence,
                    normalizedConfigurationToken);
        }
        ActivityRecreation recreation = rotateActivityToken(
                activity,
                activity.processGeneration,
                RecreationReason.CONFIGURATION_CHANGE);
        return new ConfigurationDecision(
                ConfigurationAction.RECREATED,
                recreation.previousActivityToken(),
                recreation.currentActivityToken(),
                sequence,
                normalizedConfigurationToken);
    }

    public synchronized List<ActivityRecreation> recreateProcessGeneration(
            int virtualUserId,
            String packageName,
            String processName,
            long staleGeneration,
            long newGeneration) {
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedProcessName = requireText(processName, "processName");
        if (staleGeneration < 1 || newGeneration <= staleGeneration) {
            throw new IllegalArgumentException("new generation must be greater than stale generation");
        }
        List<MutableActivity> candidates = new ArrayList<>();
        for (MutableTask task : tasks.values()) {
            for (MutableActivity activity : task.activities) {
                if (activity.identity.virtualUserId() == virtualUserId
                        && activity.identity.packageName().equals(normalizedPackageName)
                        && activity.processName.equals(normalizedProcessName)
                        && activity.processGeneration <= staleGeneration) {
                    candidates.add(activity);
                }
            }
        }
        List<ActivityRecreation> recreations = new ArrayList<>();
        for (MutableActivity activity : candidates) {
            recreations.add(rotateActivityToken(
                    activity,
                    newGeneration,
                    RecreationReason.PROCESS_RESTART));
        }
        return List.copyOf(recreations);
    }

    public synchronized int invalidateProcessGeneration(
            int virtualUserId,
            String packageName,
            String processName,
            long staleGeneration) {
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedProcessName = requireText(processName, "processName");
        if (staleGeneration < 1) {
            throw new IllegalArgumentException("staleGeneration must be positive");
        }
        int removed = 0;
        List<String> tokens = new ArrayList<>();
        for (MutableActivity activity : activitiesByToken.values()) {
            if (activity.identity.virtualUserId() == virtualUserId
                    && activity.identity.packageName().equals(normalizedPackageName)
                    && activity.processName.equals(normalizedProcessName)
                    && activity.processGeneration <= staleGeneration) {
                tokens.add(activity.token);
            }
        }
        for (String token : tokens) {
            if (removeActivityByToken(token, RESULT_CANCELED, "")) {
                removed++;
            }
        }
        return removed;
    }

    /** Returns tasks in back-to-front order; the last entry is the foreground task. */
    public synchronized List<TaskSnapshot> snapshot() {
        return tasks.values().stream().map(MutableTask::snapshot).toList();
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
        List<MutableTask> ordered = new ArrayList<>(tasks.values());
        for (int index = ordered.size() - 1; index >= 0 && result.size() < maxCount; index--) {
            MutableTask task = ordered.get(index);
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(normalizedPackageName)
                    && revisionMatches(task.packageRevision, normalizedRevision)) {
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
        List<MutableTask> active = new ArrayList<>(tasks.values());
        for (int index = active.size() - 1; index >= 0 && result.size() < maxCount; index--) {
            MutableTask task = active.get(index);
            if (task.virtualUserId != virtualUserId
                    || !task.packageName.equals(normalizedPackageName)
                    || !revisionMatches(task.packageRevision, normalizedRevision)
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
                    && revisionMatches(snapshot.packageRevision(), normalizedRevision)
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
        MutableTask task = requireOwnedTask(virtualUserId, packageName, packageRevision, taskId);
        MutableTask currentFront = tasks.isEmpty() ? null
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
        MutableTask task = requireOwnedTask(virtualUserId, packageName, packageRevision, taskId);
        tasks.remove(task.taskId);
        recentTasks.remove(task.taskId);
        for (MutableActivity activity : new ArrayList<>(task.activities)) {
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
        MutableTask task = requireOwnedTask(virtualUserId, packageName, packageRevision, taskId);
        if (tasks.size() < 2 || tasks.keySet().iterator().next() == task.taskId) return false;
        LinkedHashMap<Integer, MutableTask> reordered = new LinkedHashMap<>();
        reordered.put(task.taskId, task);
        for (Map.Entry<Integer, MutableTask> entry : tasks.entrySet()) {
            if (entry.getKey() != task.taskId) reordered.put(entry.getKey(), entry.getValue());
        }
        tasks.clear();
        tasks.putAll(reordered);
        return true;
    }

    public synchronized int finishAffinity(String activityToken) {
        MutableActivity activity = requireActivity(activityToken);
        MutableTask task = taskContaining(activity.token);
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
        MutableActivity activity = requireActivity(activityToken);
        MutableTask task = taskContaining(activity.token);
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
        for (MutableTask task : new ArrayList<>(tasks.values())) {
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
        for (MutableTask task : new ArrayList<>(tasks.values())) {
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

    public synchronized ActivityTaskCheckpoint checkpoint() {
        List<TaskRestoreSnapshot> taskSnapshots = new ArrayList<>();
        int transportDeliveries = resultDeliveriesByCaller.values().stream()
                .mapToInt(List::size)
                .sum();
        for (MutableTask task : tasks.values()) {
            List<ActivityRestoreSnapshot> activities = new ArrayList<>();
            for (MutableActivity activity : task.activities) {
                transportDeliveries += activity.pendingNewIntents.size();
                transportDeliveries += activity.pendingResultLinks.size();
                activities.add(new ActivityRestoreSnapshot(
                        activity.identity,
                        activity.launchMode,
                        activity.processName,
                        activity.processGeneration,
                        activity.resultWho,
                        activity.requestCode,
                        activity.launchFlags,
                        activity.noHistory,
                        activity.newIntentCount,
                        activity.recreationCount,
                        activity.savedState,
                        activity.configurationCount,
                        activity.lastConfigurationToken));
            }
            taskSnapshots.add(new TaskRestoreSnapshot(
                    task.taskId,
                    task.virtualUserId,
                    task.packageName,
                    task.packageRevision,
                    task.affinity,
                    task.documentTask,
                    task.documentLaunchMode,
                    task.documentKey,
                    task.rootIntentFlags,
                    task.excludedFromRecents,
                    task.retainInRecents,
                    task.lastActiveSequence,
                    task.moveToFrontCount,
                    activities));
        }
        return new ActivityTaskCheckpoint(
                ActivityTaskCheckpoint.CURRENT_SCHEMA,
                nextTaskId.get(),
                nextNewIntentSequence.get(),
                nextConfigurationSequence.get(),
                nextActivationSequence.get(),
                transportDeliveries,
                taskSnapshots,
                List.copyOf(recentTasks.values()));
    }

    public synchronized ActivityTaskRestoreOutcome restore(ActivityTaskCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!tasks.isEmpty() || !activitiesByToken.isEmpty() || !recentTasks.isEmpty()
                || !resultDeliveriesByCaller.isEmpty()) {
            throw new IllegalStateException("Activity task ledger must be empty before restore");
        }
        java.util.Set<Integer> taskIds = new java.util.LinkedHashSet<>();
        int restoredActivities = 0;
        for (TaskRestoreSnapshot snapshot : checkpoint.tasks()) {
            if (!taskIds.add(snapshot.taskId())) {
                throw new IllegalArgumentException("duplicate restored taskId: " + snapshot.taskId());
            }
            MutableTask task = new MutableTask(
                    snapshot.taskId(),
                    snapshot.virtualUserId(),
                    snapshot.packageName(),
                    snapshot.packageRevision(),
                    snapshot.affinity(),
                    snapshot.documentTask(),
                    snapshot.documentLaunchMode(),
                    snapshot.documentKey(),
                    snapshot.rootIntentFlags(),
                    snapshot.excludedFromRecents(),
                    snapshot.retainInRecents(),
                    snapshot.lastActiveSequence());
            task.moveToFrontCount = snapshot.moveToFrontCount();
            for (ActivityRestoreSnapshot activitySnapshot : snapshot.activities()) {
                String token = UUID.randomUUID().toString();
                MutableActivity activity = new MutableActivity(
                        activitySnapshot.identity(),
                        token,
                        activitySnapshot.launchMode(),
                        activitySnapshot.processName(),
                        activitySnapshot.processGeneration(),
                        activitySnapshot.resultWho(),
                        activitySnapshot.requestCode(),
                        activitySnapshot.launchFlags(),
                        activitySnapshot.noHistory());
                activity.lifecycleState = LifecycleState.INITIALIZED;
                activity.restoredFromCheckpoint = true;
                activity.newIntentCount = activitySnapshot.newIntentCount();
                activity.recreationCount = activitySnapshot.recreationCount() + 1;
                activity.savedState = activitySnapshot.savedState();
                activity.configurationCount = activitySnapshot.configurationCount();
                activity.lastConfigurationToken = activitySnapshot.lastConfigurationToken();
                task.activities.add(activity);
                activitiesByToken.put(token, activity);
                restoredActivities++;
            }
            tasks.put(task.taskId, task);
        }
        java.util.Set<Integer> recentTaskIds = new java.util.LinkedHashSet<>();
        for (TaskQuerySnapshot recent : checkpoint.recentTasks()) {
            if (recent.active()) throw new IllegalArgumentException("checkpoint recent task must be inactive");
            if (!recentTaskIds.add(recent.taskId())) {
                throw new IllegalArgumentException("duplicate restored recent taskId: " + recent.taskId());
            }
            if (taskIds.contains(recent.taskId())) continue;
            recentTasks.put(recent.taskId(), recent);
        }
        trimRecentTasks();
        nextTaskId.set(Math.max(checkpoint.nextTaskId(), maximumTaskId() + 1));
        nextNewIntentSequence.set(checkpoint.nextNewIntentSequence());
        nextConfigurationSequence.set(checkpoint.nextConfigurationSequence());
        nextActivationSequence.set(Math.max(
                checkpoint.nextActivationSequence(),
                maximumActivationSequence() + 1));
        return new ActivityTaskRestoreOutcome(
                tasks.size(),
                restoredActivities,
                recentTasks.size(),
                checkpoint.transportDeliveryCount());
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
        List<MutableActivity> candidates = new ArrayList<>(activitiesByToken.values());
        for (MutableActivity activity : candidates) {
            if (activity.restoredFromCheckpoint
                    && activity.identity.virtualUserId() == virtualUserId
                    && activity.identity.packageName().equals(normalizedPackageName)
                    && activity.processName.equals(normalizedProcessName)
                    && revisionMatches(taskContaining(activity.token).packageRevision,
                            normalizedRevision)) {
                rotateActivityToken(activity, processGeneration, RecreationReason.PROCESS_RESTART);
                activity.restoredFromCheckpoint = false;
                adopted++;
            }
        }
        return adopted;
    }

    public synchronized int taskCount() {
        return tasks.size();
    }

    public synchronized int activityCount() {
        return activitiesByToken.size();
    }

    public synchronized void clearVirtualUser(int virtualUserId) {
        if (virtualUserId < 0) {
            throw new IllegalArgumentException("virtualUserId must be non-negative");
        }
        Iterator<Map.Entry<Integer, MutableTask>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            MutableTask task = iterator.next().getValue();
            if (task.virtualUserId == virtualUserId) {
                for (MutableActivity activity : new ArrayList<>(task.activities)) {
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

    private MutableTask requireOwnedTask(
            int virtualUserId,
            String packageName,
            String packageRevision,
            int taskId) {
        if (virtualUserId < 0 || taskId < 1) throw new IllegalArgumentException("invalid task identity");
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedRevision = normalizeOptional(packageRevision);
        MutableTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown taskId: " + taskId);
        if (task.virtualUserId != virtualUserId || !task.packageName.equals(normalizedPackageName)) {
            throw new SecurityException("TASK_OWNER_MISMATCH");
        }
        if (!revisionMatches(task.packageRevision, normalizedRevision)) {
            throw new SecurityException("TASK_REVISION_MISMATCH");
        }
        return task;
    }

    private TaskQuerySnapshot querySnapshot(MutableTask task, boolean active) {
        MutableActivity base = task.activities.isEmpty() ? null : task.activities.get(0);
        MutableActivity top = top(task);
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
                task.moveToFrontCount);
    }

    private void archiveTask(MutableTask task) {
        if (task.excludedFromRecents) return;
        if (task.documentTask && !task.retainInRecents) {
            recentTasks.remove(task.taskId);
            return;
        }
        MutableActivity base = task.activities.isEmpty() ? null : task.activities.get(0);
        MutableActivity top = top(task);
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
                task.moveToFrontCount);
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
        for (MutableTask task : tasks.values()) maximum = Math.max(maximum, task.lastActiveSequence);
        for (TaskQuerySnapshot task : recentTasks.values()) {
            maximum = Math.max(maximum, task.lastActiveSequence());
        }
        return maximum;
    }

    private void validateCallerTask(LaunchRequest request) {
        if (request.callerTaskId() == null) {
            return;
        }
        MutableTask caller = tasks.get(request.callerTaskId());
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
        MutableTask callerTask = tasks.get(request.callerTaskId());
        MutableActivity caller = top(callerTask);
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
        MutableActivity caller = requireActivity(callerActivityToken);
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

    private MutableTask selectTargetTask(LaunchRequest request) {
        boolean forceNew = request.launchMode() == LaunchMode.SINGLE_INSTANCE
                || LaunchFlags.has(request.flags(), LaunchFlags.MULTIPLE_TASK)
                || LaunchFlags.has(request.flags(), LaunchFlags.NEW_DOCUMENT)
                || request.documentLaunchMode() == DocumentLaunchMode.ALWAYS;
        if (forceNew) {
            return null;
        }

        MutableTask candidate;
        if (LaunchFlags.has(request.flags(), LaunchFlags.NEW_TASK)) {
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

    private MutableTask createTask(LaunchRequest request) {
        int id = nextTaskId.getAndIncrement();
        boolean documentTask = request.documentRequested()
                && request.documentLaunchMode() != DocumentLaunchMode.NEVER;
        DocumentLaunchMode documentMode = documentTask
                ? effectiveDocumentMode(request) : DocumentLaunchMode.NONE;
        MutableTask task = new MutableTask(
                id,
                request.identity().virtualUserId(),
                request.identity().packageName(),
                request.packageRevision(),
                request.taskAffinity(),
                documentTask,
                documentMode,
                documentTask ? request.documentKey() : "",
                request.flags(),
                LaunchFlags.has(request.flags(), LaunchFlags.EXCLUDE_FROM_RECENTS),
                LaunchFlags.has(request.flags(), LaunchFlags.RETAIN_IN_RECENTS),
                nextActivationSequence.getAndIncrement());
        tasks.put(id, task);
        return task;
    }

    private MutableActivity createActivity(LaunchRequest request) {
        String token = UUID.randomUUID().toString();
        MutableActivity activity = new MutableActivity(
                request.identity(),
                token,
                request.launchMode(),
                request.processName(),
                request.processGeneration(),
                request.resultWho(),
                request.requestCode(),
                request.flags(),
                LaunchFlags.has(request.flags(), LaunchFlags.NO_HISTORY));
        activitiesByToken.put(token, activity);
        return activity;
    }

    private MutableTask findByAffinity(
            int virtualUserId,
            String packageName,
            String packageRevision,
            String affinity) {
        MutableTask found = null;
        for (MutableTask task : tasks.values()) {
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(packageName)
                    && task.packageRevision.equals(packageRevision)
                    && task.affinity.equals(affinity)
                    && !task.documentTask) {
                found = task;
            }
        }
        return found;
    }

    private Match findDocumentTask(LaunchRequest request) {
        Match found = null;
        for (MutableTask task : tasks.values()) {
            if (!task.documentTask
                    || task.virtualUserId != request.identity().virtualUserId()
                    || !task.packageName.equals(request.identity().packageName())
                    || !task.packageRevision.equals(request.packageRevision())
                    || !task.documentKey.equals(request.documentKey())
                    || task.activities.isEmpty()) {
                continue;
            }
            MutableActivity root = task.activities.get(0);
            if (root.identity.equals(request.identity())) found = new Match(task, 0);
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

    private MutableTask findFrontTaskForPackage(
            int virtualUserId,
            String packageName,
            String packageRevision) {
        MutableTask found = null;
        for (MutableTask task : tasks.values()) {
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(packageName)
                    && task.packageRevision.equals(packageRevision)) {
                found = task;
            }
        }
        return found;
    }

    private Match findAcrossTasks(ActivityIdentity identity, String packageRevision) {
        Match found = null;
        for (MutableTask task : tasks.values()) {
            if (task.virtualUserId != identity.virtualUserId()
                    || !task.packageName.equals(identity.packageName())
                    || !task.packageRevision.equals(packageRevision)) {
                continue;
            }
            int index = findIndex(task, identity);
            if (index >= 0) {
                found = new Match(task, index);
            }
        }
        return found;
    }

    private static int findIndex(MutableTask task, ActivityIdentity identity) {
        for (int index = task.activities.size() - 1; index >= 0; index--) {
            if (task.activities.get(index).identity.equals(identity)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean canAcceptActivity(MutableTask task, ActivityIdentity identity) {
        for (MutableActivity activity : task.activities) {
            if (activity.launchMode == LaunchMode.SINGLE_INSTANCE
                    && !activity.identity.equals(identity)) {
                return false;
            }
        }
        return true;
    }

    private int retainOnly(MutableTask task, MutableActivity retained) {
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

    private int clearAbove(MutableTask task, int index) {
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

    private void clearTaskForReuse(MutableTask task) {
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
            MutableTask task,
            int index,
            boolean removeEmptyTask,
            int resultCode,
            String dataToken) {
        boolean archiveWhenEmpty = removeEmptyTask && task.activities.size() == 1;
        if (archiveWhenEmpty) archiveTask(task);
        MutableActivity removed = task.activities.remove(index);
        finalizeActivity(removed, resultCode, dataToken);
        if (removeEmptyTask && task.activities.isEmpty()) {
            tasks.remove(task.taskId);
        }
    }

    private boolean removeActivityByToken(String token, int resultCode, String dataToken) {
        MutableActivity activity = activitiesByToken.get(Objects.requireNonNull(token, "token"));
        if (activity == null) {
            return false;
        }
        for (MutableTask task : new ArrayList<>(tasks.values())) {
            for (int index = 0; index < task.activities.size(); index++) {
                if (task.activities.get(index).token.equals(token)) {
                    removeActivityAt(task, index, true, resultCode, dataToken);
                    return true;
                }
            }
        }
        finalizeActivity(activity, resultCode, dataToken);
        return false;
    }

    private void finalizeActivity(MutableActivity activity, int resultCode, String dataToken) {
        deliverPendingResults(activity, resultCode, dataToken);
        activity.lifecycleState = LifecycleState.DESTROYED;
        activitiesByToken.remove(activity.token);
        resultDeliveriesByCaller.remove(activity.token);
        for (MutableActivity other : activitiesByToken.values()) {
            other.pendingResultLinks.removeIf(link -> link.callerActivityToken.equals(activity.token));
        }
        activity.pendingNewIntents.clear();
    }

    private void deliverPendingResults(MutableActivity callee, int resultCode, String dataToken) {
        for (PendingResultLink link : callee.pendingResultLinks) {
            if (!activitiesByToken.containsKey(link.callerActivityToken)) {
                continue;
            }
            ActivityResultDelivery delivery = new ActivityResultDelivery(
                    link.callerActivityToken,
                    callee.token,
                    link.resultWho,
                    link.requestCode,
                    resultCode,
                    dataToken);
            resultDeliveriesByCaller
                    .computeIfAbsent(link.callerActivityToken, ignored -> new ArrayList<>())
                    .add(delivery);
        }
        callee.pendingResultLinks.clear();
    }

    private void registerResultLink(
            MutableActivity callee,
            String callerActivityToken,
            String launchCallerActivityToken,
            LaunchRequest request) {
        if (LaunchFlags.has(request.flags(), LaunchFlags.FORWARD_RESULT)) {
            MutableActivity forwardingCaller = requireActivity(launchCallerActivityToken);
            callee.pendingResultLinks.addAll(forwardingCaller.pendingResultLinks);
            forwardingCaller.pendingResultLinks.clear();
            return;
        }
        if (callerActivityToken == null || request.requestCode() < 0) return;
        callee.pendingResultLinks.add(new PendingResultLink(
                callerActivityToken,
                request.resultWho(),
                request.requestCode()));
    }

    private void enqueueNewIntent(MutableActivity activity, LaunchRequest request) {
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

    private ActivityRecreation rotateActivityToken(
            MutableActivity activity,
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

    private static void rewriteNewIntentTargets(MutableActivity activity, String currentToken) {
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
        for (MutableActivity activity : activitiesByToken.values()) {
            for (int index = 0; index < activity.pendingResultLinks.size(); index++) {
                PendingResultLink link = activity.pendingResultLinks.get(index);
                if (link.callerActivityToken.equals(previousToken)) {
                    activity.pendingResultLinks.set(index, new PendingResultLink(
                            currentToken,
                            link.resultWho,
                            link.requestCode));
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
                delivery.requestCode(),
                delivery.resultCode(),
                delivery.dataToken());
    }

    private MutableActivity requireActivity(String token) {
        MutableActivity activity = activitiesByToken.get(Objects.requireNonNull(token, "token"));
        if (activity == null) {
            throw new IllegalArgumentException("Unknown activity token: " + token);
        }
        return activity;
    }

    private MutableTask taskContaining(String activityToken) {
        for (MutableTask task : tasks.values()) {
            if (indexOfToken(task, activityToken) >= 0) return task;
        }
        throw new IllegalArgumentException("Activity token has no live task: " + activityToken);
    }

    private static int indexOfToken(MutableTask task, String activityToken) {
        for (int index = 0; index < task.activities.size(); index++) {
            if (task.activities.get(index).token.equals(activityToken)) return index;
        }
        return -1;
    }

    private void ensureTaskRegistered(MutableTask task) {
        tasks.putIfAbsent(task.taskId, task);
    }

    private void moveTaskToFrontInternal(int taskId) {
        MutableTask previousFront = tasks.isEmpty() ? null
                : new ArrayList<>(tasks.values()).get(tasks.size() - 1);
        MutableTask task = tasks.remove(taskId);
        if (task != null) {
            if (previousFront != null && previousFront.taskId != taskId) {
                removeNoHistoryTop(previousFront);
            }
            task.lastActiveSequence = nextActivationSequence.getAndIncrement();
            task.moveToFrontCount++;
            recentTasks.remove(taskId);
            tasks.put(taskId, task);
        }
    }

    private void removeNoHistoryTop(MutableTask task) {
        MutableActivity top = top(task);
        if (top == null || !top.noHistory) return;
        removeActivityAt(task, task.activities.size() - 1, true, RESULT_CANCELED, "");
    }

    private void retireNoHistoryCaller(String callerActivityToken, String selectedActivityToken) {
        if (callerActivityToken == null || callerActivityToken.equals(selectedActivityToken)) return;
        MutableActivity caller = activitiesByToken.get(callerActivityToken);
        if (caller == null || !caller.noHistory) return;
        removeActivityByToken(callerActivityToken, RESULT_CANCELED, "");
    }

    private static MutableActivity top(MutableTask task) {
        return task == null || task.activities.isEmpty()
                ? null
                : task.activities.get(task.activities.size() - 1);
    }

    private LaunchDecision completeDecision(
            LaunchAction action,
            MutableTask task,
            MutableActivity activity,
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
                createdNewTask);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean revisionMatches(String taskRevision, String requestedRevision) {
        return requestedRevision.isEmpty() || taskRevision.equals(requestedRevision);
    }

    private record Match(MutableTask task, int index) {
    }

    private record PendingResultLink(
            String callerActivityToken,
            String resultWho,
            int requestCode) {
    }

    private static final class MutableTask {
        private final int taskId;
        private final int virtualUserId;
        private final String packageName;
        private final String packageRevision;
        private final String affinity;
        private final boolean documentTask;
        private final DocumentLaunchMode documentLaunchMode;
        private final String documentKey;
        private final int rootIntentFlags;
        private final boolean excludedFromRecents;
        private final boolean retainInRecents;
        private long lastActiveSequence;
        private long moveToFrontCount;
        private final List<MutableActivity> activities = new ArrayList<>();

        private MutableTask(
                int taskId,
                int virtualUserId,
                String packageName,
                String packageRevision,
                String affinity,
                boolean documentTask,
                DocumentLaunchMode documentLaunchMode,
                String documentKey,
                int rootIntentFlags,
                boolean excludedFromRecents,
                boolean retainInRecents,
                long lastActiveSequence) {
            this.taskId = taskId;
            this.virtualUserId = virtualUserId;
            this.packageName = packageName;
            this.packageRevision = packageRevision;
            this.affinity = affinity;
            this.documentTask = documentTask;
            this.documentLaunchMode = documentLaunchMode;
            this.documentKey = documentKey;
            this.rootIntentFlags = rootIntentFlags;
            this.excludedFromRecents = excludedFromRecents;
            this.retainInRecents = retainInRecents;
            this.lastActiveSequence = lastActiveSequence;
        }

        private TaskSnapshot snapshot() {
            return new TaskSnapshot(
                    taskId,
                    virtualUserId,
                    packageName,
                    affinity,
                    documentTask,
                    activities.stream().map(MutableActivity::snapshot).toList());
        }
    }

    private static final class MutableActivity {
        private final ActivityIdentity identity;
        private String token;
        private final LaunchMode launchMode;
        private final String processName;
        private long processGeneration;
        private final String resultWho;
        private final int requestCode;
        private final int launchFlags;
        private final boolean noHistory;
        private boolean restoredFromCheckpoint;
        private LifecycleState lifecycleState = LifecycleState.INITIALIZED;
        private long newIntentCount;
        private final List<NewIntentDelivery> pendingNewIntents = new ArrayList<>();
        private final List<PendingResultLink> pendingResultLinks = new ArrayList<>();
        private long recreationCount;
        private SavedActivityState savedState;
        private long configurationCount;
        private String lastConfigurationToken = "";

        private MutableActivity(
                ActivityIdentity identity,
                String token,
                LaunchMode launchMode,
                String processName,
                long processGeneration,
                String resultWho,
                int requestCode,
                int launchFlags,
                boolean noHistory) {
            this.identity = identity;
            this.token = token;
            this.launchMode = launchMode;
            this.processName = processName;
            this.processGeneration = processGeneration;
            this.resultWho = resultWho;
            this.requestCode = requestCode;
            this.launchFlags = launchFlags;
            this.noHistory = noHistory;
        }

        private ActivitySnapshot snapshot() {
            return new ActivitySnapshot(
                    identity,
                    token,
                    launchMode,
                    processName,
                    processGeneration,
                    lifecycleState,
                    resultWho,
                    requestCode,
                    newIntentCount,
                    pendingNewIntents.size(),
                    pendingResultLinks.size(),
                    recreationCount,
                    savedState == null ? 0 : savedState.version(),
                    configurationCount,
                    lastConfigurationToken);
        }
    }
}
