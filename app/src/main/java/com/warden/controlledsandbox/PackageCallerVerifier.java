package com.warden.controlledsandbox;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Process;
import java.util.ArrayList;
import java.util.List;

/** Uses Android's process registry rather than caller-provided data to authorize package capabilities. */
final class PackageCallerVerifier {
    private final Context context;

    PackageCallerVerifier(Context context) { this.context = context.getApplicationContext(); }

    void requireMainProcessCaller() {
        requireProcess(context.getPackageName(), "PACKAGE_MANAGEMENT_CALLER_NOT_HOST_MAIN_PROCESS");
    }

    void requireRuntimeBrokerCaller() {
        requireProcess(context.getPackageName() + ":sandbox_server",
                "RUNTIME_PERMISSION_CALLER_NOT_BROKER_PROCESS");
    }

    private void requireProcess(String expectedProcessName, String errorCode) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        if (!ManagementCallerPolicy.isAuthorized(uid, pid, Process.myUid(),
                expectedProcessName, runningProcesses())) {
            throw new SecurityException(errorCode);
        }
    }

    private List<ManagementCallerPolicy.ProcessIdentity> runningProcesses() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ManagementCallerPolicy.ProcessIdentity> identities = new ArrayList<>();
        if (manager != null) {
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    identities.add(new ManagementCallerPolicy.ProcessIdentity(
                            process.pid, process.uid, process.processName));
                }
            }
        }
        return identities;
    }
}
