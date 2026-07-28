package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Applies one ledger mutation atomically with its durable checkpoint. */
final class ActivityCheckpointTransaction {
    private final ActivityTaskLedger ledger;
    private final Runnable persistCheckpoint;

    ActivityCheckpointTransaction(ActivityTaskLedger ledger, Runnable persistCheckpoint) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.persistCheckpoint = Objects.requireNonNull(persistCheckpoint, "persistCheckpoint");
    }

    void mutate(Runnable mutation) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            mutation.run();
            persistCheckpoint.run();
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    boolean mutate(BooleanSupplier mutation) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            boolean changed = mutation.getAsBoolean();
            if (changed) persistCheckpoint.run();
            return changed;
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }

    int mutate(IntSupplier mutation) {
        ActivityTaskLedger.RollbackState before = ledger.captureRollbackState();
        try {
            int changed = mutation.getAsInt();
            if (changed > 0) persistCheckpoint.run();
            return changed;
        } catch (RuntimeException failure) {
            ledger.restoreRollbackState(before);
            throw failure;
        }
    }
}
