package android.app;

import android.content.ComponentName;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;

/** Host-side fixture for public process lookup and hidden ActivityManager singleton shape. */
public final class ActivityManager {
    private static final Singleton IActivityManagerSingleton = new Singleton();
    private static List<RunningAppProcessInfo> runningProcesses = defaultProcesses();

    public ActivityManager() { }

    public void moveTaskToFront(int taskId, int flags) { }

    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        return new ArrayList<>(runningProcesses);
    }

    public static void setRunningProcessesForTest(List<RunningAppProcessInfo> processes) {
        runningProcesses = new ArrayList<>(processes == null ? List.of() : processes);
    }

    public static void setServiceForTest(Object service) {
        IActivityManagerSingleton.mInstance = service;
    }

    public static Object getServiceForTest() {
        return IActivityManagerSingleton.mInstance;
    }

    private static List<RunningAppProcessInfo> defaultProcesses() {
        RunningAppProcessInfo process = new RunningAppProcessInfo();
        process.pid = 1;
        process.uid = 0;
        process.processName = "com.warden.controlledsandbox";
        return List.of(process);
    }


    public static class TaskInfo {
        public int taskId;
        public int id;
        public int persistentId;
        public int userId;
        public int numActivities;
        public long lastActiveTime;
        public boolean isRunning;
        public boolean isExcluded;
        public ComponentName baseActivity;
        public ComponentName topActivity;
        public ComponentName origActivity;
        public ComponentName realActivity;
        public Intent baseIntent;
    }

    public static final class RunningTaskInfo extends TaskInfo {
        public RunningTaskInfo() { }
    }

    public static final class RecentTaskInfo extends TaskInfo {
        public RecentTaskInfo() { }
    }

    public static final class AppTask {
        private final IAppTask task;

        public AppTask(IAppTask task) { this.task = task; }
        public RecentTaskInfo getTaskInfo() { return task.getTaskInfo(); }
        public void moveToFront() { task.moveToFront(); }
        public void finishAndRemoveTask() { task.finishAndRemoveTask(); }
    }

    public static final class RunningAppProcessInfo {
        public int pid;
        public int uid;
        public String processName;

        public RunningAppProcessInfo() { }

        public RunningAppProcessInfo(String processName, int pid, String[] packages) {
            this.processName = processName;
            this.pid = pid;
        }
    }

    private static final class Singleton {
        private Object mInstance;

        private Object get() {
            return mInstance;
        }
    }
}
