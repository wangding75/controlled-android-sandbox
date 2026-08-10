package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.ActivityTaskSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityProcessIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityTaskRestoreOutcome;
import com.warden.controlledsandbox.framework.activity.TaskQuerySnapshot;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Narrow dispatcher for typed Guest task queries and mutations. */
final class ActivityTaskOperationDispatcher {
    private final ActivityTaskLedger ledger;
    private final Runnable persistCheckpoint;
    private final Supplier<String> checkpointStatus;
    private final Supplier<ActivityTaskRestoreOutcome> restoreOutcome;

    ActivityTaskOperationDispatcher(
            ActivityTaskLedger ledger,
            Runnable persistCheckpoint,
            Supplier<String> checkpointStatus,
            Supplier<ActivityTaskRestoreOutcome> restoreOutcome) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.persistCheckpoint = Objects.requireNonNull(persistCheckpoint, "persistCheckpoint");
        this.checkpointStatus = Objects.requireNonNull(checkpointStatus, "checkpointStatus");
        this.restoreOutcome = Objects.requireNonNull(restoreOutcome, "restoreOutcome");
    }

    ActivityTaskResult dispatch(GuestSession session, ActivityTaskRequest request) {
        Objects.requireNonNull(request, "request");
        verifySession(request, session);
        boolean changed = false;
        List<ActivityTaskSnapshot> tasks = List.of();
        switch (request.operation()) {
            case ActivityTaskRequest.QUERY_RUNNING -> tasks = projectTasks(ledger.runningTasks(
                    session.virtualUserId(), session.packageName(), session.packageRevision(),
                    request.maxCount()));
            case ActivityTaskRequest.QUERY_RECENT -> tasks = projectTasks(ledger.recentTasks(
                    session.virtualUserId(), session.packageName(), session.packageRevision(),
                    request.maxCount()));
            case ActivityTaskRequest.MOVE_TO_FRONT -> {
                changed = mutate(() -> ledger.moveTaskToFront(
                        session.virtualUserId(), session.packageName(), session.packageRevision(),
                        request.taskId()));
            }
            case ActivityTaskRequest.MOVE_TO_BACK -> {
                changed = mutate(() -> ledger.moveTaskToBack(
                        session.virtualUserId(), session.packageName(), session.packageRevision(),
                        request.taskId()));
            }
            case ActivityTaskRequest.REMOVE_TASK -> {
                changed = mutate(() -> ledger.removeTask(
                        session.virtualUserId(), session.packageName(), session.packageRevision(),
                        request.taskId()));
            }
            case ActivityTaskRequest.FINISH_AFFINITY -> {
                verifyOwner(request.activityToken(), session);
                changed = mutate(() -> ledger.finishAffinity(request.activityToken()) > 0);
            }
            case ActivityTaskRequest.FINISH_AND_REMOVE_TASK -> {
                verifyOwner(request.activityToken(), session);
                changed = mutate(() -> ledger.finishAndRemoveTask(request.activityToken()));
            }
            case ActivityTaskRequest.CHECKPOINT_STATUS -> { }
            default -> throw new IllegalArgumentException(
                    "Unknown Activity task operation: " + request.operation());
        }
        ActivityTaskRestoreOutcome restored = restoreOutcome.get();
        return ActivityTaskResult.success(
                RuntimeProtocol.CURRENT,
                request.requestId(),
                request.operation(),
                changed,
                checkpointStatus.get(),
                ledger.taskCount(),
                ledger.activityCount(),
                restored.restoredTaskCount(),
                restored.restoredActivityCount(),
                restored.droppedTransportDeliveryCount(),
                tasks);
    }

    private boolean mutate(BooleanSupplier operation) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            boolean changed = operation.getAsBoolean();
            if (changed) persistCheckpoint.run();
            return changed;
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    private void verifyOwner(String activityToken, GuestSession session) {
        ActivityProcessIdentity identity = ledger.processIdentity(activityToken);
        if (identity.virtualUserId() != session.virtualUserId()
                || !identity.packageName().equals(session.packageName())
                || !identity.processName().equals(session.processName())
                || identity.processGeneration() != session.generation()) {
            throw new SecurityException("ACTIVITY_OWNER_MISMATCH");
        }
    }

    private static List<ActivityTaskSnapshot> projectTasks(List<TaskQuerySnapshot> snapshots) {
        return snapshots.stream().map(snapshot -> new ActivityTaskSnapshot(
                snapshot.taskId(),
                snapshot.virtualUserId(),
                snapshot.packageName(),
                snapshot.packageRevision(),
                snapshot.affinity(),
                snapshot.documentTask(),
                snapshot.documentLaunchMode().name(),
                snapshot.documentKey(),
                snapshot.active(),
                snapshot.excludedFromRecents(),
                snapshot.retainInRecents(),
                snapshot.activityCount(),
                snapshot.baseComponentName(),
                snapshot.topComponentName(),
                snapshot.lastActiveSequence(),
                snapshot.moveToFrontCount())).collect(java.util.stream.Collectors.toList());
    }

    private static void verifySession(ActivityTaskRequest request, GuestSession session) {
        if (!RuntimeProtocol.isCompatible(request.protocolVersion())) {
            throw new IllegalArgumentException("ACTIVITY_TASK_PROTOCOL_MISMATCH");
        }
        if (!session.sessionId().equals(request.sessionId())
                || session.generation() != request.generation()
                || session.virtualUserId() != request.virtualUserId()
                || !session.packageName().equals(request.packageName())) {
            throw new SecurityException("ACTIVITY_TASK_SESSION_MISMATCH");
        }
    }
}
