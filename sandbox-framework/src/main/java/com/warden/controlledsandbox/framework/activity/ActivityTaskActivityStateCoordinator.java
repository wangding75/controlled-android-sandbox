package com.warden.controlledsandbox.framework.activity;

import static com.warden.controlledsandbox.framework.activity.ActivityTaskTextPolicy.normalizeOptional;
import static com.warden.controlledsandbox.framework.activity.ActivityTaskTextPolicy.requireBoundedText;
import static com.warden.controlledsandbox.framework.activity.ActivityTaskTextPolicy.requireText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Owns Activity-local lifecycle, result, recreation and saved-state mutations. */
final class ActivityTaskActivityStateCoordinator {
    private final ActivityTaskLedger owner;
    private final AtomicLong nextConfigurationSequence;
    private final Map<Integer, ActivityTaskMutableTask> tasks;
    private final Map<String, ActivityTaskMutableActivity> activitiesByToken;
    private final Map<String, List<ActivityResultDelivery>> resultDeliveriesByCaller;
    private final int maxResultRegistrations;

    ActivityTaskActivityStateCoordinator(
            ActivityTaskLedger owner,
            AtomicLong nextConfigurationSequence,
            Map<Integer, ActivityTaskMutableTask> tasks,
            Map<String, ActivityTaskMutableActivity> activitiesByToken,
            Map<String, List<ActivityResultDelivery>> resultDeliveriesByCaller,
            int maxResultRegistrations) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.nextConfigurationSequence = Objects.requireNonNull(
                nextConfigurationSequence, "nextConfigurationSequence");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.activitiesByToken = Objects.requireNonNull(activitiesByToken, "activitiesByToken");
        this.resultDeliveriesByCaller = Objects.requireNonNull(
                resultDeliveriesByCaller, "resultDeliveriesByCaller");
        this.maxResultRegistrations = maxResultRegistrations;
    }

    boolean transition(String token, LifecycleState next) {
        ActivityTaskMutableActivity activity = owner.requireActivity(token);
        Objects.requireNonNull(next, "next");
        if (activity.lifecycleState == next) return false;
        if (!activity.lifecycleState.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Invalid lifecycle transition " + activity.lifecycleState + " -> " + next);
        }
        if (next == LifecycleState.DESTROYED) {
            return owner.removeActivityByToken(token, ActivityTaskLedger.RESULT_CANCELED, "");
        }
        activity.lifecycleState = next;
        return true;
    }

    boolean finish(String token) {
        return finishWithResult(token, ActivityTaskLedger.RESULT_CANCELED, "");
    }

    boolean finishWithResult(String token, int resultCode, String dataToken) {
        return owner.removeActivityByToken(token, resultCode,
                dataToken == null ? "" : dataToken, ResultIntentSnapshot.EMPTY);
    }

    boolean finishWithResult(String token, int resultCode, ResultIntentSnapshot resultIntent) {
        return owner.removeActivityByToken(token, resultCode, "",
                Objects.requireNonNull(resultIntent, "resultIntent"));
    }

    ActivityResultRegistration registerActivityResult(String activityToken, String registrationKey) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        String key = requireBoundedText(registrationKey, "registrationKey", 256);
        Integer existing = activity.resultRegistrations.get(key);
        if (existing != null) return new ActivityResultRegistration(key, existing);
        if (activity.resultRegistrations.size() >= maxResultRegistrations) {
            throw new IllegalStateException("ACTIVITY_RESULT_REGISTRATION_LIMIT");
        }
        boolean[] used = new boolean[0x10000];
        for (Integer value : activity.resultRegistrations.values()) used[value] = true;
        int requestCode = 0;
        while (requestCode < used.length && used[requestCode]) requestCode++;
        if (requestCode == used.length) {
            throw new IllegalStateException("ACTIVITY_RESULT_REQUEST_CODE_EXHAUSTED");
        }
        activity.resultRegistrations.put(key, requestCode);
        return new ActivityResultRegistration(key, requestCode);
    }

    boolean unregisterActivityResult(String activityToken, String registrationKey) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        return activity.resultRegistrations.remove(
                requireBoundedText(registrationKey, "registrationKey", 256)) != null;
    }

    Optional<ActivityResultRegistration> activityResultRegistration(
            String activityToken, String registrationKey) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        String key = requireBoundedText(registrationKey, "registrationKey", 256);
        Integer requestCode = activity.resultRegistrations.get(key);
        return requestCode == null ? Optional.empty()
                : Optional.of(new ActivityResultRegistration(key, requestCode));
    }

    boolean deliverActivityResult(String callerActivityToken, String resultWho, int requestCode,
                                  int resultCode, String intentSenderToken,
                                  ResultIntentSnapshot resultIntent) {
        ActivityTaskMutableActivity caller = owner.requireActivity(callerActivityToken);
        if (requestCode < 0 || requestCode > 0xffff) {
            throw new IllegalArgumentException("requestCode must be 0..65535");
        }
        ActivityResultDelivery delivery = new ActivityResultDelivery(caller.token,
                "intent-sender:" + requireBoundedText(intentSenderToken, "intentSenderToken", 512),
                normalizeOptional(resultWho), "", requestCode, resultCode,
                intentSenderToken, "", Objects.requireNonNull(resultIntent, "resultIntent"));
        resultDeliveriesByCaller.computeIfAbsent(caller.token, ignored -> new ArrayList<>())
                .add(delivery);
        return true;
    }

    List<ActivityResultDelivery> drainActivityResults(String callerActivityToken) {
        ActivityTaskMutableActivity caller = owner.requireActivity(callerActivityToken);
        List<ActivityResultDelivery> deliveries = resultDeliveriesByCaller.remove(caller.token);
        return deliveries == null ? List.of() : List.copyOf(deliveries);
    }

    int pendingActivityResultCount(String callerActivityToken) {
        ActivityTaskMutableActivity caller = owner.requireActivity(callerActivityToken);
        List<ActivityResultDelivery> deliveries = resultDeliveriesByCaller.get(caller.token);
        return deliveries == null ? 0 : deliveries.size();
    }

    Optional<NewIntentDelivery> pollNewIntent(String activityToken) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        if (activity.pendingNewIntents.isEmpty()) return Optional.empty();
        return Optional.of(activity.pendingNewIntents.remove(0));
    }

    boolean containsActivity(String activityToken) {
        return activityToken != null && activitiesByToken.containsKey(activityToken);
    }

    boolean acknowledgeNewIntent(String activityToken, String routeToken) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        if (routeToken == null || routeToken.trim().isEmpty()) {
            throw new IllegalArgumentException("routeToken is required");
        }
        for (int index = 0; index < activity.pendingNewIntents.size(); index++) {
            NewIntentDelivery delivery = activity.pendingNewIntents.get(index);
            if (routeToken.equals(delivery.routeToken())) {
                activity.pendingNewIntents.remove(index);
                return true;
            }
        }
        return false;
    }

    List<NewIntentDelivery> drainNewIntents(String activityToken) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        List<NewIntentDelivery> deliveries = List.copyOf(activity.pendingNewIntents);
        activity.pendingNewIntents.clear();
        return deliveries;
    }

    ActivityProcessIdentity processIdentity(String activityToken) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        return new ActivityProcessIdentity(activity.identity.virtualUserId(),
                activity.identity.packageName(), activity.processName, activity.processGeneration);
    }

    boolean saveInstanceState(String activityToken, SavedActivityState state) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
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

    Optional<SavedActivityState> savedInstanceState(String activityToken) {
        return Optional.ofNullable(owner.requireActivity(activityToken).savedState);
    }

    ConfigurationDecision handleConfigurationChange(String activityToken, String configurationToken,
                                                    boolean handledByGuest) {
        ActivityTaskMutableActivity activity = owner.requireActivity(activityToken);
        String normalizedConfigurationToken = requireText(configurationToken, "configurationToken");
        long sequence = nextConfigurationSequence.getAndIncrement();
        activity.configurationCount++;
        activity.lastConfigurationToken = normalizedConfigurationToken;
        if (handledByGuest) {
            return new ConfigurationDecision(ConfigurationAction.DELIVERED_TO_EXISTING,
                    activity.token, activity.token, sequence, normalizedConfigurationToken);
        }
        ActivityRecreation recreation = owner.rotateActivityToken(
                activity, activity.processGeneration, RecreationReason.CONFIGURATION_CHANGE);
        return new ConfigurationDecision(ConfigurationAction.RECREATED,
                recreation.previousActivityToken(), recreation.currentActivityToken(),
                sequence, normalizedConfigurationToken);
    }

    List<ActivityRecreation> recreateProcessGeneration(int virtualUserId, String packageName,
                                                        String processName, long staleGeneration,
                                                        long newGeneration) {
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedProcessName = requireText(processName, "processName");
        if (staleGeneration < 1 || newGeneration <= staleGeneration) {
            throw new IllegalArgumentException("new generation must be greater than stale generation");
        }
        List<ActivityTaskMutableActivity> candidates = new ArrayList<>();
        for (ActivityTaskMutableTask task : tasks.values()) {
            for (ActivityTaskMutableActivity activity : task.activities) {
                if (activity.identity.virtualUserId() == virtualUserId
                        && activity.identity.packageName().equals(normalizedPackageName)
                        && activity.processName.equals(normalizedProcessName)
                        && activity.processGeneration <= staleGeneration) {
                    candidates.add(activity);
                }
            }
        }
        List<ActivityRecreation> recreations = new ArrayList<>();
        for (ActivityTaskMutableActivity activity : candidates) {
            recreations.add(owner.rotateActivityToken(
                    activity, newGeneration, RecreationReason.PROCESS_RESTART));
        }
        return List.copyOf(recreations);
    }

    int invalidateProcessGeneration(int virtualUserId, String packageName, String processName,
                                    long staleGeneration) {
        String normalizedPackageName = requireText(packageName, "packageName");
        String normalizedProcessName = requireText(processName, "processName");
        if (staleGeneration < 1) {
            throw new IllegalArgumentException("staleGeneration must be positive");
        }
        int removed = 0;
        List<String> tokens = new ArrayList<>();
        for (ActivityTaskMutableActivity activity : activitiesByToken.values()) {
            if (activity.identity.virtualUserId() == virtualUserId
                    && activity.identity.packageName().equals(normalizedPackageName)
                    && activity.processName.equals(normalizedProcessName)
                    && activity.processGeneration <= staleGeneration) {
                tokens.add(activity.token);
            }
        }
        for (String token : tokens) {
            if (owner.removeActivityByToken(token, ActivityTaskLedger.RESULT_CANCELED, "")) {
                removed++;
            }
        }
        return removed;
    }
}
