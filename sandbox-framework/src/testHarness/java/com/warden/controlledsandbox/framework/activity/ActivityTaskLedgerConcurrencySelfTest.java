package com.warden.controlledsandbox.framework.activity;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ActivityTaskLedgerConcurrencySelfTest {
    private static final int THREADS = 8;
    private static final int ITERATIONS_PER_THREAD = 200;

    private ActivityTaskLedgerConcurrencySelfTest() {
    }

    public static void main(String[] args) throws InterruptedException {
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            for (int threadIndex = 0; threadIndex < THREADS; threadIndex++) {
                int virtualUserId = threadIndex;
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int iteration = 0; iteration < ITERATIONS_PER_THREAD; iteration++) {
                            LaunchDecision launch = ledger.launch(new LaunchRequest(
                                    new ActivityIdentity(
                                            virtualUserId,
                                            "guest.concurrent",
                                            "Activity" + iteration),
                                    "guest.concurrent.user." + virtualUserId,
                                    LaunchMode.STANDARD,
                                    LaunchFlags.NEW_TASK | LaunchFlags.MULTIPLE_TASK,
                                    null,
                                    "guest.concurrent:worker" + virtualUserId,
                                    1,
                                    "route-" + virtualUserId + "-" + iteration,
                                    "",
                                    -1));
                            if (!ledger.finish(launch.activityToken())) {
                                throw new AssertionError("concurrent finish lost Activity record");
                            }
                        }
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    } finally {
                        done.countDown();
                    }
                });
            }
            check(ready.await(10, TimeUnit.SECONDS), "workers did not become ready");
            start.countDown();
            check(done.await(30, TimeUnit.SECONDS), "workers did not finish");
            Throwable throwable = failure.get();
            if (throwable != null) {
                throw new AssertionError("concurrent ledger operation failed", throwable);
            }
            check(ledger.activityCount() == 0, "concurrent run leaked Activity records");
            check(ledger.taskCount() == 0, "concurrent run leaked Task records");
            assertSnapshotConsistent(ledger.snapshot());
        } finally {
            executor.shutdownNow();
            check(executor.awaitTermination(10, TimeUnit.SECONDS), "executor did not terminate");
        }
        System.out.println("PASS ActivityTaskLedgerConcurrencySelfTest");
    }

    private static void assertSnapshotConsistent(List<TaskSnapshot> tasks) {
        List<Integer> taskIds = new ArrayList<>();
        List<String> activityTokens = new ArrayList<>();
        for (TaskSnapshot task : tasks) {
            check(!task.activities().isEmpty(), "empty task must not be retained");
            check(!taskIds.contains(task.taskId()), "duplicate task id");
            taskIds.add(task.taskId());
            for (ActivitySnapshot activity : task.activities()) {
                check(activity.identity().virtualUserId() == task.virtualUserId(),
                        "Activity virtual user differs from task");
                check(activity.identity().packageName().equals(task.packageName()),
                        "Activity package differs from task");
                check(!activityTokens.contains(activity.token()), "duplicate Activity token");
                activityTokens.add(activity.token());
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
