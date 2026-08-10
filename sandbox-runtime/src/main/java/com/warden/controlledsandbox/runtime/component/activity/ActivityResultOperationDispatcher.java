package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityResultSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityProcessIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityResultRegistration;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import java.util.List;
import java.util.Objects;

/** Broker-owned typed Activity Result API compatibility dispatcher. */
final class ActivityResultOperationDispatcher {
    private final ActivityTaskLedger ledger;
    private final Runnable persistCheckpoint;

    ActivityResultOperationDispatcher(ActivityTaskLedger ledger, Runnable persistCheckpoint) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.persistCheckpoint = Objects.requireNonNull(persistCheckpoint, "persistCheckpoint");
    }

    ActivityResultResult dispatch(GuestSession session, ActivityResultRequest request) {
        Objects.requireNonNull(request, "request");
        verifySession(request, session);
        verifyOwner(request.activityToken(), session);
        return switch (request.operation()) {
            case ActivityResultRequest.REGISTER -> register(request);
            case ActivityResultRequest.UNREGISTER -> unregister(request);
            case ActivityResultRequest.FINISH -> finish(request);
            case ActivityResultRequest.DRAIN -> drain(request);
            case ActivityResultRequest.SEND -> send(request);
            default -> throw new IllegalArgumentException(
                    "Unknown Activity result operation: " + request.operation());
        };
    }

    private ActivityResultResult register(ActivityResultRequest request) {
        boolean existed = ledger.activityResultRegistration(
                request.activityToken(), request.registryKey()).isPresent();
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            ActivityResultRegistration registration = ledger.registerActivityResult(
                    request.activityToken(), request.registryKey());
            if (!existed) persistCheckpoint.run();
            return ActivityResultResult.success(
                    RuntimeProtocol.CURRENT, request.requestId(), request.operation(), !existed,
                    registration.requestCode(), List.of());
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    private ActivityResultResult unregister(ActivityResultRequest request) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            boolean changed = ledger.unregisterActivityResult(
                    request.activityToken(), request.registryKey());
            if (changed) persistCheckpoint.run();
            return ActivityResultResult.success(
                    RuntimeProtocol.CURRENT, request.requestId(), request.operation(), changed,
                    -1, List.of());
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    private ActivityResultResult finish(ActivityResultRequest request) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            boolean changed = ledger.finishWithResult(
                    request.activityToken(), request.resultCode(),
                    ActivityResultContractMapper.toFramework(request.resultIntent()));
            if (changed) persistCheckpoint.run();
            return ActivityResultResult.success(
                    RuntimeProtocol.CURRENT, request.requestId(), request.operation(), changed,
                    -1, List.of());
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    private ActivityResultResult send(ActivityResultRequest request) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            boolean changed = ledger.deliverActivityResult(request.activityToken(),
                    request.registryKey(), request.requestCode(), request.resultCode(),
                    request.requestId(), ActivityResultContractMapper.toFramework(request.resultIntent()));
            if (changed) persistCheckpoint.run();
            return ActivityResultResult.success(RuntimeProtocol.CURRENT, request.requestId(),
                    request.operation(), changed, request.requestCode(), List.of());
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before); throw failure;
        }
    }

    private ActivityResultResult drain(ActivityResultRequest request) {
        List<ActivityResultSnapshot> results = ledger.drainActivityResults(request.activityToken())
                .stream().map(ActivityResultContractMapper::toContract)
                .collect(java.util.stream.Collectors.toList());
        return ActivityResultResult.success(
                RuntimeProtocol.CURRENT, request.requestId(), request.operation(),
                !results.isEmpty(), -1, results);
    }

    private void verifyOwner(String activityToken, GuestSession session) {
        ActivityProcessIdentity identity = ledger.processIdentity(activityToken);
        if (identity.virtualUserId() != session.virtualUserId()
                || !identity.packageName().equals(session.packageName())
                || !identity.processName().equals(session.processName())
                || identity.processGeneration() != session.generation()) {
            throw new SecurityException("ACTIVITY_RESULT_OWNER_MISMATCH");
        }
    }

    private static void verifySession(ActivityResultRequest request, GuestSession session) {
        if (!RuntimeProtocol.isCompatible(request.protocolVersion())) {
            throw new IllegalArgumentException("ACTIVITY_RESULT_PROTOCOL_MISMATCH");
        }
        if (!session.sessionId().equals(request.sessionId())
                || session.generation() != request.generation()
                || session.virtualUserId() != request.virtualUserId()
                || !session.packageName().equals(request.packageName())) {
            throw new SecurityException("ACTIVITY_RESULT_SESSION_MISMATCH");
        }
    }
}
