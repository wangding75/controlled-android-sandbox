package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.framework.activity.ActivityIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityTaskCheckpoint;
import com.warden.controlledsandbox.framework.activity.ActivityTaskLedger;
import com.warden.controlledsandbox.framework.activity.LaunchFlags;
import com.warden.controlledsandbox.framework.activity.LaunchMode;
import com.warden.controlledsandbox.framework.activity.LaunchRequest;
import com.warden.controlledsandbox.framework.activity.SavedActivityState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ActivityTaskCheckpointStoreSelfTest {
    private ActivityTaskCheckpointStoreSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("activity-task-checkpoint-");
        Path file = directory.resolve("tasks.bin");
        ActivityTaskLedger ledger = new ActivityTaskLedger();
        var launch = ledger.launch(new LaunchRequest(
                new ActivityIdentity(0, "com.example", "com.example.MainActivity"),
                "com.example",
                LaunchMode.STANDARD,
                LaunchFlags.NEW_TASK,
                null,
                "com.example",
                3,
                "route-1",
                "",
                -1));
        ledger.saveInstanceState(launch.activityToken(),
                new SavedActivityState(2, Map.of("screen", "home")));
        ActivityTaskCheckpoint expected = ledger.checkpoint();

        ActivityTaskCheckpointStore store = new ActivityTaskCheckpointStore(file);
        store.save(expected);
        ActivityTaskCheckpoint loaded = store.load().orElseThrow();
        check(loaded.equals(expected), "checkpoint codec round trip changed state");

        byte[] corrupt = Files.readAllBytes(file);
        corrupt[corrupt.length / 2] ^= 0x01;
        Files.write(file, corrupt);
        try {
            store.load();
            throw new AssertionError("corrupt checkpoint should fail closed");
        } catch (IllegalStateException expectedFailure) {
            check(expectedFailure.getMessage().contains("CRC"),
                    "corruption should be rejected by checksum");
        }
        Path quarantine = store.quarantineCorrupt();
        check(Files.isRegularFile(quarantine), "corrupt checkpoint should be quarantined");
        check(!Files.exists(file), "active checkpoint path should be cleared after quarantine");
        System.out.println("PASS Activity task checkpoint persistence self-test");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
