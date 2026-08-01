package com.warden.controlledsandbox;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

/** Regression for close ordering and best-effort observer dispatch after durable commit. */
public final class VirtualSystemServiceStoreCommitConsistencySelfTest {
    private VirtualSystemServiceStoreCommitConsistencySelfTest() { }

    public static void main(String[] args) throws Exception {
        testCommittedMutationSurvivesRejectedObserverDispatch();
        testClosedStoreRejectsBeforeMutation();
        testWaitingMutationLosesToCloseWithoutPersisting();
        System.out.println("PASS virtual system-service Store commit consistency self-test");
    }

    private static void testCommittedMutationSurvivesRejectedObserverDispatch() throws Exception {
        File root = Files.createTempDirectory("store-rejected-observer-dispatch").toFile();
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("commit.pkg", 0);
        RejectingExecuteScheduler scheduler = new RejectingExecuteScheduler();
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root, scheduler);
        byte[] committed = new byte[]{1, 2, 3, 4};
        store.setClipboard(scope, committed);
        require(store.maintenanceWarning().startsWith(
                        "VIRTUAL_OBSERVER_DISPATCH_FAILED:CLIPBOARD:RejectedExecutionException"),
                "observer rejection was not recorded as best-effort maintenance evidence");
        store.close();

        try (VirtualSystemServiceStore reopened = new VirtualSystemServiceStore(root)) {
            require(Arrays.equals(committed, reopened.clipboard(scope)),
                    "persisted mutation was lost after observer scheduling rejection");
        }
    }

    private static void testClosedStoreRejectsBeforeMutation() throws Exception {
        File root = Files.createTempDirectory("store-close-before-mutation").toFile();
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("closed.pkg", 1);
        byte[] original = new byte[]{5, 6, 7};
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        store.setClipboard(scope, original);
        store.close();
        expectClosed(() -> store.setClipboard(scope, new byte[]{5, 6, 7, 8}),
                "closed Store accepted and persisted a mutation");

        try (VirtualSystemServiceStore reopened = new VirtualSystemServiceStore(root)) {
            require(Arrays.equals(original, reopened.clipboard(scope)),
                    "closed Store changed durable state before reporting failure");
        }
    }

    private static void testWaitingMutationLosesToCloseWithoutPersisting() throws Exception {
        File root = Files.createTempDirectory("store-close-race").toFile();
        VirtualSystemServiceStore.Scope scope = new VirtualSystemServiceStore.Scope("race.pkg", 2);
        byte[] original = new byte[]{9, 10};
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        store.setClipboard(scope, original);
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread mutation = new Thread(() -> {
            attempted.countDown();
            try { store.setClipboard(scope, new byte[]{9, 10, 11}); }
            catch (Throwable error) { failure.set(error); }
        }, "store-mutation-waiter");

        synchronized (store) {
            mutation.start();
            attempted.await();
            store.close();
        }
        mutation.join(5_000L);
        require(!mutation.isAlive(), "waiting mutation did not finish after Store close");
        require(isClosedFailure(failure.get()),
                "mutation waiting behind close did not fail with the closed-state contract");

        try (VirtualSystemServiceStore reopened = new VirtualSystemServiceStore(root)) {
            require(Arrays.equals(original, reopened.clipboard(scope)),
                    "close/mutation race persisted state after close won the lock");
        }
    }

    private static final class RejectingExecuteScheduler extends ScheduledThreadPoolExecutor {
        RejectingExecuteScheduler() { super(1); }
        @Override public void execute(Runnable command) {
            throw new RejectedExecutionException("deterministic observer dispatch rejection");
        }
    }

    private static void expectClosed(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
            require(isClosedFailure(expected), "unexpected closed Store failure: " + expected);
        }
    }

    private static boolean isClosedFailure(Throwable error) {
        return error instanceof IllegalStateException
                && "VIRTUAL_SYSTEM_SERVICE_STORE_CLOSED".equals(error.getMessage());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
