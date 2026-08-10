package com.warden.controlledsandbox.framework.activity;

import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger.RollbackState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Owns checkpoint, restore and mutation rollback for the Activity/Task ledger. */
final class ActivityTaskCheckpointCoordinator {
    private final AtomicInteger nextTaskId;
    private final AtomicLong nextNewIntentSequence;
    private final AtomicLong nextConfigurationSequence;
    private final AtomicLong nextActivationSequence;
    private final LinkedHashMap<Integer, ActivityTaskMutableTask> tasks;
    private final LinkedHashMap<Integer, TaskQuerySnapshot> recentTasks;
    private final Map<String, ActivityTaskMutableActivity> activitiesByToken;
    private final Map<String, List<ActivityResultDelivery>> resultDeliveriesByCaller;
    private final int maxRecentTasks;

    ActivityTaskCheckpointCoordinator(
            AtomicInteger nextTaskId,
            AtomicLong nextNewIntentSequence,
            AtomicLong nextConfigurationSequence,
            AtomicLong nextActivationSequence,
            LinkedHashMap<Integer, ActivityTaskMutableTask> tasks,
            LinkedHashMap<Integer, TaskQuerySnapshot> recentTasks,
            Map<String, ActivityTaskMutableActivity> activitiesByToken,
            Map<String, List<ActivityResultDelivery>> resultDeliveriesByCaller,
            int maxRecentTasks) {
        this.nextTaskId = Objects.requireNonNull(nextTaskId, "nextTaskId");
        this.nextNewIntentSequence = Objects.requireNonNull(
                nextNewIntentSequence, "nextNewIntentSequence");
        this.nextConfigurationSequence = Objects.requireNonNull(
                nextConfigurationSequence, "nextConfigurationSequence");
        this.nextActivationSequence = Objects.requireNonNull(
                nextActivationSequence, "nextActivationSequence");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.recentTasks = Objects.requireNonNull(recentTasks, "recentTasks");
        this.activitiesByToken = Objects.requireNonNull(activitiesByToken, "activitiesByToken");
        this.resultDeliveriesByCaller = Objects.requireNonNull(
                resultDeliveriesByCaller, "resultDeliveriesByCaller");
        this.maxRecentTasks = maxRecentTasks;
    }

    RollbackState captureRollbackState() {
        LinkedHashMap<Integer, ActivityTaskMutableTask> taskCopies = new LinkedHashMap<>();
        LinkedHashMap<String, ActivityTaskMutableActivity> activityCopies = new LinkedHashMap<>();
        for (ActivityTaskMutableTask task : tasks.values()) {
            ActivityTaskMutableTask taskCopy = copyTask(task, activityCopies);
            taskCopies.put(taskCopy.taskId, taskCopy);
        }
        LinkedHashMap<String, List<ActivityResultDelivery>> deliveryCopies = new LinkedHashMap<>();
        for (Map.Entry<String, List<ActivityResultDelivery>> entry : resultDeliveriesByCaller.entrySet()) {
            deliveryCopies.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return new RollbackState(
                nextTaskId.get(),
                nextNewIntentSequence.get(),
                nextConfigurationSequence.get(),
                nextActivationSequence.get(),
                taskCopies,
                new LinkedHashMap<>(recentTasks),
                activityCopies,
                deliveryCopies);
    }

    void restoreRollbackState(RollbackState state) {
        Objects.requireNonNull(state, "state");
        tasks.clear();
        recentTasks.clear();
        activitiesByToken.clear();
        resultDeliveriesByCaller.clear();
        nextTaskId.set(state.nextTaskId);
        nextNewIntentSequence.set(state.nextNewIntentSequence);
        nextConfigurationSequence.set(state.nextConfigurationSequence);
        nextActivationSequence.set(state.nextActivationSequence);
        tasks.putAll(state.tasks);
        recentTasks.putAll(state.recentTasks);
        activitiesByToken.putAll(state.activitiesByToken);
        resultDeliveriesByCaller.putAll(state.resultDeliveriesByCaller);
    }

    ActivityTaskCheckpoint checkpoint() {
        List<TaskRestoreSnapshot> taskSnapshots = new ArrayList<>();
        int transportDeliveries = resultDeliveriesByCaller.values().stream()
                .mapToInt(List::size)
                .sum();
        for (ActivityTaskMutableTask task : tasks.values()) {
            List<ActivityRestoreSnapshot> activities = new ArrayList<>();
            for (ActivityTaskMutableActivity activity : task.activities) {
                transportDeliveries += activity.pendingNewIntents.size();
                List<ActivityResultRegistration> registrations = activity.resultRegistrations.entrySet().stream()
                        .map(entry -> new ActivityResultRegistration(entry.getKey(), entry.getValue()))
                        .collect(java.util.stream.Collectors.toList());
                List<PendingActivityResultSnapshot> pendingLinks = activity.pendingResultLinks.stream()
                        .map(link -> new PendingActivityResultSnapshot(
                                requireActivity(link.callerActivityToken()).stableId,
                                link.resultWho(),
                                link.registryKey(),
                                link.requestCode(),
                                link.intentSenderToken()))
                        .collect(java.util.stream.Collectors.toList());
                activities.add(new ActivityRestoreSnapshot(
                        activity.identity,
                        activity.stableId,
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
                        activity.lastConfigurationToken,
                        registrations,
                        pendingLinks));
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

    ActivityTaskRestoreOutcome restore(ActivityTaskCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!tasks.isEmpty() || !activitiesByToken.isEmpty() || !recentTasks.isEmpty()
                || !resultDeliveriesByCaller.isEmpty()) {
            throw new IllegalStateException("Activity task ledger must be empty before restore");
        }
        java.util.Set<Integer> taskIds = new java.util.LinkedHashSet<>();
        Map<String, ActivityTaskMutableActivity> restoredByStableId = new LinkedHashMap<>();
        List<Runnable> pendingLinkRestorations = new ArrayList<>();
        int restoredActivities = 0;
        for (TaskRestoreSnapshot snapshot : checkpoint.tasks()) {
            if (!taskIds.add(snapshot.taskId())) {
                throw new IllegalArgumentException("duplicate restored taskId: " + snapshot.taskId());
            }
            ActivityTaskMutableTask task = new ActivityTaskMutableTask(
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
                String stableId = activitySnapshot.stableId().isEmpty()
                        ? UUID.randomUUID().toString() : activitySnapshot.stableId();
                if (restoredByStableId.containsKey(stableId)) {
                    throw new IllegalArgumentException("duplicate restored Activity stableId: " + stableId);
                }
                ActivityTaskMutableActivity activity = new ActivityTaskMutableActivity(
                        activitySnapshot.identity(),
                        stableId,
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
                for (ActivityResultRegistration registration : activitySnapshot.resultRegistrations()) {
                    if (activity.resultRegistrations.putIfAbsent(
                            registration.key(), registration.requestCode()) != null) {
                        throw new IllegalArgumentException("duplicate restored Activity result key");
                    }
                }
                task.activities.add(activity);
                activitiesByToken.put(token, activity);
                restoredByStableId.put(stableId, activity);
                pendingLinkRestorations.add(() -> {
                    for (PendingActivityResultSnapshot link : activitySnapshot.pendingResultLinks()) {
                        ActivityTaskMutableActivity caller = restoredByStableId.get(link.callerStableId());
                        if (caller == null) {
                            throw new IllegalArgumentException("restored result caller is missing");
                        }
                        activity.pendingResultLinks.add(new ActivityTaskPendingResultLink(
                                caller.token, link.resultWho(), link.registryKey(),
                                link.requestCode(), link.intentSenderToken()));
                    }
                });
                restoredActivities++;
            }
            tasks.put(task.taskId, task);
        }
        for (Runnable restoration : pendingLinkRestorations) restoration.run();
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

    private static ActivityTaskMutableTask copyTask(
            ActivityTaskMutableTask source,
            Map<String, ActivityTaskMutableActivity> activityCopies) {
        ActivityTaskMutableTask copy = new ActivityTaskMutableTask(
                source.taskId,
                source.virtualUserId,
                source.packageName,
                source.packageRevision,
                source.affinity,
                source.documentTask,
                source.documentLaunchMode,
                source.documentKey,
                source.rootIntentFlags,
                source.excludedFromRecents,
                source.retainInRecents,
                source.lastActiveSequence);
        copy.moveToFrontCount = source.moveToFrontCount;
        for (ActivityTaskMutableActivity activity : source.activities) {
            ActivityTaskMutableActivity activityCopy = copyActivity(activity);
            copy.activities.add(activityCopy);
            activityCopies.put(activityCopy.token, activityCopy);
        }
        return copy;
    }

    private static ActivityTaskMutableActivity copyActivity(ActivityTaskMutableActivity source) {
        ActivityTaskMutableActivity copy = new ActivityTaskMutableActivity(
                source.identity,
                source.stableId,
                source.token,
                source.launchMode,
                source.processName,
                source.processGeneration,
                source.resultWho,
                source.requestCode,
                source.launchFlags,
                source.noHistory);
        copy.restoredFromCheckpoint = source.restoredFromCheckpoint;
        copy.lifecycleState = source.lifecycleState;
        copy.newIntentCount = source.newIntentCount;
        copy.pendingNewIntents.addAll(source.pendingNewIntents);
        copy.pendingResultLinks.addAll(source.pendingResultLinks);
        copy.resultRegistrations.putAll(source.resultRegistrations);
        copy.recreationCount = source.recreationCount;
        copy.savedState = source.savedState;
        copy.configurationCount = source.configurationCount;
        copy.lastConfigurationToken = source.lastConfigurationToken;
        return copy;
    }

    private ActivityTaskMutableActivity requireActivity(String token) {
        ActivityTaskMutableActivity activity = activitiesByToken.get(token);
        if (activity == null) throw new IllegalArgumentException("Unknown Activity token: " + token);
        return activity;
    }

    private void trimRecentTasks() {
        while (recentTasks.size() > maxRecentTasks) {
            Integer first = recentTasks.keySet().iterator().next();
            recentTasks.remove(first);
        }
    }

    private int maximumTaskId() {
        int maximum = 0;
        for (int taskId : tasks.keySet()) maximum = Math.max(maximum, taskId);
        for (int taskId : recentTasks.keySet()) maximum = Math.max(maximum, taskId);
        return maximum;
    }

    private long maximumActivationSequence() {
        long maximum = 0L;
        for (ActivityTaskMutableTask task : tasks.values()) {
            maximum = Math.max(maximum, task.lastActiveSequence);
        }
        for (TaskQuerySnapshot task : recentTasks.values()) {
            maximum = Math.max(maximum, task.lastActiveSequence());
        }
        return maximum;
    }
}
