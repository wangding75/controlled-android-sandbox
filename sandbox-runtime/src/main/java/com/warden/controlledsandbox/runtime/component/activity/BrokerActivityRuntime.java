package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityTaskRestoreOutcome;
import com.warden.controlledsandbox.framework.routing.OneTimeRouteStore;
import com.warden.controlledsandbox.runtime.broker.BrokerStateStore;
import java.nio.file.Path;
import java.util.Objects;

/** High-level broker orchestration for Activity routes, lifecycle and task/result APIs. */
public final class BrokerActivityRuntime {
    private final ActivityTaskLedger ledger;
    private final ActivityRuntimeCheckpointCoordinator checkpoints;
    private final ActivityRuntimeRouteCoordinator routes;
    private final ActivityRuntimeLifecycleCoordinator lifecycle;
    private final ActivityTaskOperationDispatcher taskOperations;
    private final ActivityResultOperationDispatcher resultOperations;

    public BrokerActivityRuntime(BrokerStateStore transport) {
        this(new ActivityTaskLedger(), new OneTimeRouteStore(), transport);
    }

    public BrokerActivityRuntime(ActivityTaskLedger ledger, OneTimeRouteStore routeStore,
            BrokerStateStore transport) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(routeStore, "routeStore");
        Objects.requireNonNull(transport, "transport");
        this.checkpoints = new ActivityRuntimeCheckpointCoordinator(ledger);
        this.routes = new ActivityRuntimeRouteCoordinator(
                ledger, routeStore, transport, checkpoints.transactions(), this::persistCheckpoint);
        this.lifecycle = new ActivityRuntimeLifecycleCoordinator(
                ledger, routes, checkpoints.transactions(), this::persistCheckpoint);
        this.taskOperations = new ActivityTaskOperationDispatcher(
                ledger, this::persistCheckpoint, checkpoints::status, checkpoints::restoreOutcome);
        this.resultOperations = new ActivityResultOperationDispatcher(ledger, this::persistCheckpoint);
    }

    public synchronized ActivityTaskRestoreOutcome configureCheckpointStore(Path file) {
        if (routes.pendingRouteCount() != 0) {
            throw new IllegalStateException("Activity runtime must be idle before checkpoint restore");
        }
        return checkpoints.configure(file);
    }

    public synchronized String checkpointStatus() { return checkpoints.status(); }
    public synchronized ActivityTaskRestoreOutcome restoreOutcome() { return checkpoints.restoreOutcome(); }
    public synchronized Bundle launch(GuestSession session, String component, Bundle prepared, Bundle request) {
        return routes.launch(session, component, prepared, request);
    }
    public synchronized ActivityTaskLedger.LauncherTaskReuse launcherTaskReuse(
            int virtualUserId,
            String packageName,
            String packageRevision,
            String launcherComponent,
            String taskAffinity) {
        return routes.launcherTaskReuse(
                virtualUserId, packageName, packageRevision, launcherComponent, taskAffinity);
    }
    public synchronized Bundle consume(String token, GuestSession session) {
        return routes.consume(token, session);
    }
    public synchronized void launchFailed(String token) { routes.launchFailed(token); }
    public synchronized Bundle event(GuestSession session, Bundle request) {
        return lifecycle.event(session, request);
    }
    public synchronized void recreate(GuestSession stale, GuestSession current) {
        lifecycle.recreate(stale, current);
    }
    public synchronized void processDisconnected(GuestSession stale) {
        lifecycle.processDisconnected(stale);
    }
    public synchronized void invalidate(GuestSession stale) { lifecycle.invalidate(stale); }
    public synchronized ActivityTaskResult taskOperation(GuestSession session, ActivityTaskRequest request) {
        return taskOperations.dispatch(session, request);
    }
    public synchronized ActivityResultResult resultOperation(GuestSession session, ActivityResultRequest request) {
        return resultOperations.dispatch(session, request);
    }
    public synchronized int pendingRouteCount() { return routes.pendingRouteCount(); }
    public synchronized int taskCount() { return ledger.taskCount(); }
    public synchronized int activityCount() { return ledger.activityCount(); }
    public synchronized int clearMismatchedRevision(int virtualUserId, String packageName,
            String retainedRevision) {
        return checkpoints.transactions().mutate(
                () -> ledger.clearPackageRevision(virtualUserId, packageName, retainedRevision));
    }
    public synchronized int clearPackageInstance(int virtualUserId, String packageName) {
        return checkpoints.transactions().mutate(
                () -> ledger.clearPackageInstance(virtualUserId, packageName));
    }
    /** Returns the bounded route envelope used by broker recovery to rebuild a Guest. */
    public synchronized Bundle routeForPreparation(String token) {
        return routes.routeForPreparation(token);
    }

    /** The route coordinator preserves the typed ActivityLaunchSpecFactory.create boundary. */
    private void persistCheckpoint() { checkpoints.persist(); }
}
