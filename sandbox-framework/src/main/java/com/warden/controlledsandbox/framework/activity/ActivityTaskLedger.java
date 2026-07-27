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
    private final AtomicLong nextConfigurationSequence = new AtomicLong(1);
    private final LinkedHashMap<Integer, MutableTask> tasks = new LinkedHashMap<>();
    private final Map<String, MutableActivity> activitiesByToken = new LinkedHashMap<>();
    private final Map<String, List<ActivityResultDelivery>> resultDeliveriesByCaller =
            new LinkedHashMap<>();

    public synchronized LaunchDecision launch(LaunchRequest request) {
        Objects.requireNonNull(request, "request");
        validateCallerTask(request);
        String resultCallerToken = resolveResultCallerToken(request);

        boolean clearTask = LaunchFlags.has(request.flags(), LaunchFlags.CLEAR_TASK);
        if (clearTask && !LaunchFlags.has(request.flags(), LaunchFlags.NEW_TASK)) {
            throw new IllegalArgumentException("CLEAR_TASK requires NEW_TASK");
        }

        if (!clearTask && request.launchMode() == LaunchMode.SINGLE_INSTANCE) {
            Match global = findAcrossTasks(request.identity());
            if (global != null) {
                MutableActivity activity = global.task.activities.get(global.index);
                int removed = retainOnly(global.task, activity);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, request);
                moveTaskToFront(global.task.taskId);
                return decision(
                        LaunchAction.DELIVERED_NEW_INTENT,
                        global.task,
                        activity,
                        removed,
                        false,
                        request.routeToken());
            }
        }

        if (!clearTask && request.launchMode() == LaunchMode.SINGLE_TASK) {
            Match global = findAcrossTasks(request.identity());
            if (global != null) {
                int removed = clearAbove(global.task, global.index);
                MutableActivity activity = global.task.activities.get(global.index);
                enqueueNewIntent(activity, request);
                registerResultLink(activity, resultCallerToken, request);
                moveTaskToFront(global.task.taskId);
                return decision(
                        LaunchAction.CLEARED_TOP,
                        global.task,
                        activity,
                        removed,
                        false,
                        request.routeToken());
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
                registerResultLink(activity, resultCallerToken, request);
                moveTaskToFront(target.taskId);
                return decision(
                        LaunchAction.DELIVERED_NEW_INTENT,
                        target,
                        activity,
                        removed,
                        createdTask,
                        request.routeToken());
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
                registerResultLink(activity, resultCallerToken, request);
                moveTaskToFront(target.taskId);
                return decision(
                        LaunchAction.REORDERED_TO_FRONT,
                        target,
                        activity,
                        0,
                        createdTask,
                        request.routeToken());
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
                    registerResultLink(activity, resultCallerToken, request);
                    moveTaskToFront(target.taskId);
                    return decision(
                            LaunchAction.CLEARED_TOP,
                            target,
                            activity,
                            removed,
                            createdTask,
                            request.routeToken());
                }
                removeActivityAt(target, existingIndex, false, RESULT_CANCELED, "");
                removed++;
                MutableActivity replacement = createActivity(request);
                target.activities.add(replacement);
                registerResultLink(replacement, resultCallerToken, request);
                ensureTaskRegistered(target);
                moveTaskToFront(target.taskId);
                return decision(
                        LaunchAction.CLEARED_TOP,
                        target,
                        replacement,
                        removed,
                        createdTask,
                        request.routeToken());
            }
        }

        MutableActivity top = top(target);
        boolean singleTop = request.launchMode() == LaunchMode.SINGLE_TOP
                || LaunchFlags.has(request.flags(), LaunchFlags.SINGLE_TOP);
        if (singleTop && top != null && top.identity.equals(request.identity())) {
            enqueueNewIntent(top, request);
            registerResultLink(top, resultCallerToken, request);
            moveTaskToFront(target.taskId);
            return decision(
                    LaunchAction.DELIVERED_NEW_INTENT,
                    target,
                    top,
                    0,
                    createdTask,
                    request.routeToken());
        }

        if (request.launchMode() == LaunchMode.SINGLE_INSTANCE && !target.activities.isEmpty()) {
            target = createTask(request);
            createdTask = true;
        }

        MutableActivity created = createActivity(request);
        target.activities.add(created);
        registerResultLink(created, resultCallerToken, request);
        ensureTaskRegistered(target);
        moveTaskToFront(target.taskId);
        return decision(
                createdTask ? LaunchAction.CREATED_TASK : LaunchAction.CREATED_ACTIVITY,
                target,
                created,
                0,
                createdTask,
                request.routeToken());
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
    }

    private String resolveResultCallerToken(LaunchRequest request) {
        if (request.requestCode() < 0) {
            return null;
        }
        if (request.callerTaskId() == null) {
            throw new IllegalArgumentException(
                    "requestCode requires a callerTaskId with a live top Activity");
        }
        MutableTask callerTask = tasks.get(request.callerTaskId());
        MutableActivity caller = top(callerTask);
        if (caller == null) {
            throw new IllegalArgumentException("callerTaskId has no live Activity");
        }
        return caller.token;
    }

    private MutableTask selectTargetTask(LaunchRequest request) {
        boolean forceNew = request.launchMode() == LaunchMode.SINGLE_INSTANCE
                || LaunchFlags.has(request.flags(), LaunchFlags.MULTIPLE_TASK)
                || LaunchFlags.has(request.flags(), LaunchFlags.NEW_DOCUMENT);
        if (forceNew) {
            return null;
        }

        MutableTask candidate;
        if (LaunchFlags.has(request.flags(), LaunchFlags.NEW_TASK)) {
            candidate = findByAffinity(
                    request.identity().virtualUserId(),
                    request.identity().packageName(),
                    request.taskAffinity());
        } else if (request.callerTaskId() != null) {
            candidate = tasks.get(request.callerTaskId());
        } else {
            candidate = findFrontTaskForPackage(
                    request.identity().virtualUserId(),
                    request.identity().packageName());
        }

        if (candidate != null && !canAcceptActivity(candidate, request.identity())) {
            return null;
        }
        return candidate;
    }

    private MutableTask createTask(LaunchRequest request) {
        int id = nextTaskId.getAndIncrement();
        MutableTask task = new MutableTask(
                id,
                request.identity().virtualUserId(),
                request.identity().packageName(),
                request.taskAffinity(),
                LaunchFlags.has(request.flags(), LaunchFlags.NEW_DOCUMENT));
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
                request.requestCode());
        activitiesByToken.put(token, activity);
        return activity;
    }

    private MutableTask findByAffinity(int virtualUserId, String packageName, String affinity) {
        MutableTask found = null;
        for (MutableTask task : tasks.values()) {
            if (task.virtualUserId == virtualUserId
                    && task.packageName.equals(packageName)
                    && task.affinity.equals(affinity)
                    && !task.documentTask) {
                found = task;
            }
        }
        return found;
    }

    private MutableTask findFrontTaskForPackage(int virtualUserId, String packageName) {
        MutableTask found = null;
        for (MutableTask task : tasks.values()) {
            if (task.virtualUserId == virtualUserId && task.packageName.equals(packageName)) {
                found = task;
            }
        }
        return found;
    }

    private Match findAcrossTasks(ActivityIdentity identity) {
        Match found = null;
        for (MutableTask task : tasks.values()) {
            if (task.virtualUserId != identity.virtualUserId()
                    || !task.packageName.equals(identity.packageName())) {
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
            LaunchRequest request) {
        if (callerActivityToken == null || !activitiesByToken.containsKey(callerActivityToken)) {
            return;
        }
        if (callerActivityToken.equals(callee.token)) {
            resultDeliveriesByCaller
                    .computeIfAbsent(callerActivityToken, ignored -> new ArrayList<>())
                    .add(new ActivityResultDelivery(
                            callerActivityToken,
                            callee.token,
                            request.resultWho(),
                            request.requestCode(),
                            RESULT_CANCELED,
                            ""));
            return;
        }
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

    private void ensureTaskRegistered(MutableTask task) {
        tasks.putIfAbsent(task.taskId, task);
    }

    private void moveTaskToFront(int taskId) {
        MutableTask task = tasks.remove(taskId);
        if (task != null) {
            tasks.put(taskId, task);
        }
    }

    private static MutableActivity top(MutableTask task) {
        return task == null || task.activities.isEmpty()
                ? null
                : task.activities.get(task.activities.size() - 1);
    }

    private static LaunchDecision decision(
            LaunchAction action,
            MutableTask task,
            MutableActivity activity,
            int removedActivityCount,
            boolean createdNewTask,
            String routeToken) {
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
        private final String affinity;
        private final boolean documentTask;
        private final List<MutableActivity> activities = new ArrayList<>();

        private MutableTask(
                int taskId,
                int virtualUserId,
                String packageName,
                String affinity,
                boolean documentTask) {
            this.taskId = taskId;
            this.virtualUserId = virtualUserId;
            this.packageName = packageName;
            this.affinity = affinity;
            this.documentTask = documentTask;
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
                int requestCode) {
            this.identity = identity;
            this.token = token;
            this.launchMode = launchMode;
            this.processName = processName;
            this.processGeneration = processGeneration;
            this.resultWho = resultWho;
            this.requestCode = requestCode;
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
