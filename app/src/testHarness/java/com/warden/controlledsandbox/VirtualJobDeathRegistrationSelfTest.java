package com.warden.controlledsandbox;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.IHostJobCallback;
import com.warden.controlledsandbox.contract.IVirtualJobExecution;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceObserver;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Deterministic regression for a Host Job callback dying inside linkToDeath. */
public final class VirtualJobDeathRegistrationSelfTest {
    private VirtualJobDeathRegistrationSelfTest() { }

    public static void main(String[] args) throws Exception {
        java.io.File root = Files.createTempDirectory("virtual-job-immediate-death").toFile();
        VirtualSystemServiceStore store = new VirtualSystemServiceStore(root);
        try {
            VirtualSystemServiceStore.Scope scope =
                    new VirtualSystemServiceStore.Scope("death.job.pkg", 6);
            TestClient client = new TestClient(scope);
            store.register(client);
            VirtualJobSnapshot job = store.reserveJob(scope, "death.job.pkg", 1L,
                    61, new byte[]{6, 1});
            store.commitJob(scope, 61);

            require(!store.startJob(parameters(job.hostId()), new ImmediateDeathHostCallback(), 0),
                    "job callback that died inside linkToDeath was published");
            VirtualJobSnapshot restored = store.jobs(scope, "death.job.pkg", 1L).stream()
                    .filter(value -> value.guestId() == 61).findFirst().orElseThrow();
            require(VirtualJobSnapshot.SCHEDULED.equals(restored.state()),
                    "immediately dead job callback left execution RUNNING");

            Field active = VirtualSystemServiceStore.class.getDeclaredField("activeJobExecutions");
            active.setAccessible(true);
            require(((Map<?, ?>) active.get(store)).isEmpty(),
                    "immediately dead job callback leaked active execution registry entry");
            store.unregister(client);
        } finally {
            store.close();
        }
        System.out.println("PASS virtual Job immediate-death registration self-test");
    }

    private static final class ImmediateDeathHostCallback extends IHostJobCallback.Stub {
        private boolean alive = true;

        @Override public boolean isBinderAlive() { return alive; }

        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
            alive = false;
            recipient.binderDied();
        }

        @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
            return true;
        }

        @Override public void finishHostJob(int hostJobId, boolean needsReschedule) { }
        @Override public VirtualJobWorkItemSnapshot dequeueHostWork(int hostJobId) { return null; }
        @Override public boolean completeHostWork(int hostJobId, int workId) { return false; }
    }

    private static final class TestClient implements VirtualSystemServiceStore.Client {
        private final VirtualSystemServiceStore.Scope scope;
        private final IVirtualSystemServiceObserver observer = new IVirtualSystemServiceObserver.Stub() {
            @Override public void onClipboardChanged() { }
            @Override public void onDeviceServiceProfileChanged(long policyVersion) { }
            @Override public void onInteractionProfileChanged(long policyVersion) { }
            @Override public void onNetworkServiceProfileChanged(long policyVersion) { }
            @Override public void onApplicationEnvironmentProfileChanged(long policyVersion) { }
            @Override public void onCompatibilityProfileChanged(long policyVersion) { }
            @Override public void onPolicyServicesProfileChanged(long policyVersion) { }
            @Override public void onMediaCommunicationProfileChanged(long policyVersion) { }
            @Override public void onPeripheralServicesProfileChanged(long policyVersion) { }
            @Override public void onPrivilegedServicesProfileChanged(long policyVersion) { }
            @Override public void onApplicationEnvironmentDataChanged(String domain, String key) { }
            @Override public void onAlarm(
                    com.warden.controlledsandbox.contract.VirtualAlarmSnapshot alarm) { }
            @Override public boolean onJobStart(int guestJobId, byte[] payload,
                    VirtualJobParametersSnapshot parameters, IVirtualJobExecution execution) {
                throw new AssertionError("dead Host callback must prevent Guest Job dispatch");
            }
            @Override public boolean onJobStop(int guestJobId,
                    VirtualJobParametersSnapshot parameters) { return true; }
        };

        TestClient(VirtualSystemServiceStore.Scope scope) { this.scope = scope; }
        @Override public VirtualSystemServiceStore.Scope scope() { return scope; }
        @Override public String processName() { return "death.job.pkg"; }
        @Override public long generation() { return 1L; }
        @Override public IVirtualSystemServiceObserver observer() { return observer; }
        @Override public boolean active() { return true; }
    }

    private static VirtualJobParametersSnapshot parameters(int hostJobId) {
        return new VirtualJobParametersSnapshot(hostJobId, -1, "", new byte[0], new byte[0],
                new byte[0], 0, false, false, false, List.of(), List.of(), new byte[0],
                0, -1, "", 0L);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
