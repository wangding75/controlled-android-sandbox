package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.framework.activity.ActivityTaskCheckpoint;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.ActivityTaskRestoreOutcome;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Objects;

/** Owns durable Activity/Task checkpoint configuration and persistence state. */
final class ActivityRuntimeCheckpointCoordinator {
    private final ActivityTaskLedger ledger;
    private final ActivityCheckpointTransaction transactions;
    private ActivityTaskCheckpointStore store;
    private String status = "DISABLED";
    private ActivityTaskRestoreOutcome restoreOutcome = new ActivityTaskRestoreOutcome(0, 0, 0, 0);

    ActivityRuntimeCheckpointCoordinator(ActivityTaskLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.transactions = new ActivityCheckpointTransaction(ledger, this::persist);
    }

    ActivityTaskRestoreOutcome configure(Path file) {
        if (store != null) throw new IllegalStateException("Activity task checkpoint store already configured");
        if (ledger.taskCount() != 0 || ledger.activityCount() != 0) {
            throw new IllegalStateException("Activity runtime must be idle before checkpoint restore");
        }
        store = new ActivityTaskCheckpointStore(file);
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            Optional<ActivityTaskCheckpoint> checkpoint = store.load();
            if (checkpoint.isEmpty()) {
                status = "EMPTY";
                return restoreOutcome;
            }
            restoreOutcome = ledger.restore(checkpoint.get());
            status = "RESTORED";
            return restoreOutcome;
        } catch (RuntimeException corruption) {
            ledger.restoreRollbackState(before);
            store.quarantineCorrupt();
            status = "QUARANTINED:" + corruption.getMessage();
            restoreOutcome = new ActivityTaskRestoreOutcome(0, 0, 0, 0);
            return restoreOutcome;
        }
    }

    String status() { return status; }

    ActivityTaskRestoreOutcome restoreOutcome() { return restoreOutcome; }

    ActivityCheckpointTransaction transactions() { return transactions; }

    void persist() {
        if (store == null) return;
        store.save(ledger.checkpoint());
        status = "PERSISTED";
    }
}
