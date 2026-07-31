package com.warden.controlledsandbox.runtime.guest;

import android.app.ActivityManager;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.ActivityTaskSnapshot;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.PackageServiceResult;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ActivityTaskFrameworkInterceptorSelfTest {
    public static void main(String[] args) throws Throwable {
        FakeBroker broker = new FakeBroker();
        GuestPackageSpec spec = new GuestPackageSpec(specBundle(broker));
        ActivityTaskFrameworkInterceptor interceptor = new ActivityTaskFrameworkInterceptor(spec);

        Method getTasks = FakeAtm.class.getMethod("getTasks", int.class);
        FrameworkCallInterceptor.Interception running = interceptor.intercept(
                "activity-task-manager", getTasks, new Object[]{10});
        require(running.handled(), "running-task query is intercepted");
        @SuppressWarnings("unchecked")
        List<ActivityManager.RunningTaskInfo> runningTasks =
                (List<ActivityManager.RunningTaskInfo>) running.result();
        require(runningTasks.size() == 1, "one virtual running task projected");
        ActivityManager.RunningTaskInfo runningInfo = runningTasks.get(0);
        require(runningInfo.taskId == 41 && runningInfo.id == 41,
                "virtual task id projected into Android fields");
        require(runningInfo.userId == 2 && runningInfo.numActivities == 2,
                "virtual user and Activity count projected");
        require("guest.pkg.MainActivity".equals(runningInfo.baseActivity.getClassName())
                        && "guest.pkg.DetailActivity".equals(runningInfo.topActivity.getClassName()),
                "virtual base/top components projected");
        assertIdentity(broker.lastRequest, ActivityTaskRequest.QUERY_RUNNING);

        Method getRecent = FakeAtm.class.getMethod("getRecentTasks", int.class, int.class, int.class);
        FrameworkCallInterceptor.Interception recent = interceptor.intercept(
                "activity-task-manager", getRecent, new Object[]{5, 0, 2});
        require(recent.handled() && recent.result() instanceof ParceledListSlice<?>,
                "recent tasks use ParceledListSlice projection");
        ParceledListSlice<?> recentSlice = (ParceledListSlice<?>) recent.result();
        ActivityManager.RecentTaskInfo recentInfo =
                (ActivityManager.RecentTaskInfo) recentSlice.getList().get(0);
        require(recentInfo.persistentId == 41 && recentInfo.isRunning,
                "recent task preserves virtual identity and active state");
        assertIdentity(broker.lastRequest, ActivityTaskRequest.QUERY_RECENT);

        IBinder frameworkToken = new Binder();
        AtomicInteger moveFront = new AtomicInteger();
        AtomicInteger moveBack = new AtomicInteger();
        AtomicInteger finishAffinity = new AtomicInteger();
        AtomicInteger finishRemove = new AtomicInteger();
        interceptor.bindHostActivity(frameworkToken, "activity-41", 41,
                moveFront::incrementAndGet,
                () -> { moveBack.incrementAndGet(); return true; },
                finishAffinity::incrementAndGet,
                finishRemove::incrementAndGet);

        Method getAppTasks = FakeAtm.class.getMethod("getAppTasks", String.class);
        FrameworkCallInterceptor.Interception appTasksResult = interceptor.intercept(
                "activity-task-manager", getAppTasks, new Object[]{"guest.pkg"});
        @SuppressWarnings("unchecked")
        List<IBinder> appTaskBinders = (List<IBinder>) appTasksResult.result();
        require(appTaskBinders.size() == 1, "one virtual IAppTask Binder projected");
        Object appTask = appTaskInterface(appTaskBinders.get(0));
        ActivityManager.RecentTaskInfo appTaskInfo = (ActivityManager.RecentTaskInfo)
                appTask.getClass().getMethod("getTaskInfo").invoke(appTask);
        require(appTaskInfo.taskId == 41, "AppTask Binder exposes only virtual task");
        appTask.getClass().getMethod("moveToFront").invoke(appTask);
        require(ActivityTaskRequest.MOVE_TO_FRONT.equals(broker.lastRequest.operation())
                        && moveFront.get() == 1,
                "AppTask moveToFront returns to Broker and mirrors host task");

        Method taskForActivity = FakeAtm.class.getMethod(
                "getTaskForActivity", IBinder.class, boolean.class);
        require(((Integer) interceptor.intercept("activity-task-manager", taskForActivity,
                new Object[]{frameworkToken, false}).result()) == 41,
                "framework Activity token resolves to virtual task id");

        Method moveTaskToBack = FakeAtm.class.getMethod(
                "moveActivityTaskToBack", IBinder.class, boolean.class);
        FrameworkCallInterceptor.Interception movedBack = interceptor.intercept(
                "activity-task-manager", moveTaskToBack, new Object[]{frameworkToken, true});
        require(Boolean.TRUE.equals(movedBack.result()) && moveBack.get() == 1,
                "moveTaskToBack mutates Broker and mirrors host Stub task");
        assertIdentity(broker.lastRequest, ActivityTaskRequest.MOVE_TO_BACK);

        Method affinity = FakeAtm.class.getMethod("finishActivityAffinity", IBinder.class);
        FrameworkCallInterceptor.Interception affinityResult = interceptor.intercept(
                "activity-task-manager", affinity, new Object[]{frameworkToken});
        require(Boolean.TRUE.equals(affinityResult.result()) && finishAffinity.get() == 1,
                "finishAffinity is Broker-authoritative and mirrors host lifecycle");
        require(interceptor.consumeBrokerFinalized(frameworkToken),
                "host destroy can suppress duplicate Broker finalization");
        assertIdentity(broker.lastRequest, ActivityTaskRequest.FINISH_AFFINITY);

        Method removeTask = FakeAtm.class.getMethod("removeTask", int.class);
        FrameworkCallInterceptor.Interception removed = interceptor.intercept(
                "activity-manager", removeTask, new Object[]{41});
        require(Boolean.TRUE.equals(removed.result()) && finishRemove.get() == 1,
                "removeTask removes virtual state and mirrors host task removal");

        boolean packageRejected = false;
        try {
            interceptor.intercept("activity-task-manager", getAppTasks,
                    new Object[]{"other.pkg"});
        } catch (SecurityException expected) {
            packageRejected = true;
        }
        require(packageRejected, "cross-package AppTask query rejected");

        broker.fail = true;
        boolean failedClosed = false;
        try {
            interceptor.intercept("activity-task-manager", getTasks, new Object[]{10});
        } catch (IllegalStateException expected) {
            failedClosed = expected.getMessage().contains("BROKER_REJECTED");
        }
        require(failedClosed, "Broker query failure throws instead of passing to host delegate");

        interceptor.close();
        System.out.println("PASS virtual Activity/Task framework interception self-test");
    }


    private static Object appTaskInterface(IBinder binder) throws Exception {
        Class<?> stub = Class.forName("android.app.IAppTask$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static void assertIdentity(ActivityTaskRequest request, String operation) {
        require(request != null, "Broker received typed task request");
        require("session-1".equals(request.sessionId()) && request.generation() == 3L,
                "request binds session and generation");
        require(request.virtualUserId() == 2 && "guest.pkg".equals(request.packageName()),
                "request binds virtual user and Guest package");
        require(operation.equals(request.operation()), "request operation matches framework call");
    }

    public interface FakeAtm {
        List<ActivityManager.RunningTaskInfo> getTasks(int maxCount);
        ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(
                int maxCount, int flags, int userId);
        List<IBinder> getAppTasks(String packageName);
        void moveTaskToFront(int taskId);
        boolean removeTask(int taskId);
        boolean moveActivityTaskToBack(IBinder token, boolean nonRoot);
        boolean finishActivityAffinity(IBinder token);
        void finishActivityAndRemoveTask(IBinder token);
        int getTaskForActivity(IBinder token, boolean onlyRoot);
    }

    private static final class FakeBroker extends IRuntimeBroker.Stub {
        private ActivityTaskRequest lastRequest;
        private boolean fail;

        @Override public ActivityTaskResult activityTaskOperation(ActivityTaskRequest request) {
            lastRequest = request;
            if (fail) {
                return ActivityTaskResult.failure(request.protocolVersion(), request.requestId(),
                        new SandboxError("BROKER_REJECTED", "fixture failure", false));
            }
            boolean changed = !ActivityTaskRequest.QUERY_RUNNING.equals(request.operation())
                    && !ActivityTaskRequest.QUERY_RECENT.equals(request.operation());
            List<ActivityTaskSnapshot> tasks =
                    ActivityTaskRequest.QUERY_RUNNING.equals(request.operation())
                            || ActivityTaskRequest.QUERY_RECENT.equals(request.operation())
                            ? List.of(task()) : List.of();
            return ActivityTaskResult.success(request.protocolVersion(), request.requestId(),
                    request.operation(), changed, "PERSISTED", 1, 2, 0, 0, 0, tasks);
        }

        private static ActivityTaskSnapshot task() {
            return new ActivityTaskSnapshot(41, 2, "guest.pkg", "revision-1",
                    "guest.pkg", false, "NONE", "", true, false, true, 2,
                    "guest.pkg.MainActivity", "guest.pkg.DetailActivity", 9L, 3L);
        }

        @Override public RuntimeOperationResult executeV2(RuntimeOperationRequest request) { return null; }
        @Override public ActivityResultResult activityResultOperation(ActivityResultRequest request) { return null; }
        @Override public PackageServiceResult requestRuntimePermission(String sessionId, long generation,
                String permission, int requestCode) { return null; }
        @Override public PackageServiceResult reportRuntimePermissionResult(String sessionId, long generation,
                String permission, int requestCode, boolean hostGranted, String reason) { return null; }
        @Override public RuntimeStatusResult runtimeStatusV2(RuntimeStatusRequest request) { return null; }
        @Override public void stopGuest(String packageName, int virtualUserId) { }
    }

    private static Bundle specBundle(FakeBroker broker) {
        Bundle input = new Bundle();
        input.putInt(RuntimeKeys.PROTOCOL, 3);
        input.putString(RuntimeKeys.SESSION_ID, "session-1");
        input.putLong(RuntimeKeys.GENERATION, 3L);
        input.putString(RuntimeKeys.PACKAGE_NAME, "guest.pkg");
        input.putInt(RuntimeKeys.VIRTUAL_USER_ID, 2);
        input.putInt(RuntimeKeys.VIRTUAL_UID, 12002);
        input.putInt(RuntimeKeys.PROCESS_SLOT, 0);
        input.putString(RuntimeKeys.PROCESS_NAME, "guest.pkg");
        input.putString(RuntimeKeys.APK_PATH, "/tmp/base.apk");
        String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        input.putString(RuntimeKeys.APK_SHA256, sha);
        input.putString(RuntimeKeys.BASE_APK_SHA256, sha);
        input.putLong(RuntimeKeys.APK_VERSION_CODE, 1L);
        input.putString(RuntimeKeys.PACKAGE_REVISION, "v1:" + sha);
        input.putString(RuntimeKeys.DATA_ROOT, "/tmp/guest");
        input.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER, broker.asBinder());
        input.putParcelable(RuntimeKeys.PACKAGE_STATE,
                new VirtualPackageStateSnapshot("guest.pkg", 2, "Guest", "1.0", 1L,
                        sha, sha, "guest.pkg.MainActivity", "", true,
                        List.of(), List.of(), List.of()));
        return input;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
