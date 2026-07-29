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
        int callerUid = Binder.getCallingUid();
        if (callerUid == Process.myUid()) {
            requireProcess(context.getPackageName() + ":sandbox_server",
                    "RUNTIME_PERMISSION_CALLER_NOT_BROKER_PROCESS");
            return;
        }
        if (context.checkCallingPermission(
                com.warden.controlledsandbox.runtime.broker.RuntimePeerPolicy.SIGNATURE_PERMISSION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("RUNTIME_PERMISSION_CALLER_NOT_SIGNED_PEER");
        }
        int callerPid = Binder.getCallingPid();
        for (ManagementCallerPolicy.ProcessIdentity identity : runningProcesses()) {
            if (identity.pid == callerPid && identity.uid == callerUid
                    && (com.warden.controlledsandbox.runtime.broker.RuntimePeerPolicy
                            .companionBrokerProcess(
                                    com.warden.controlledsandbox.runtime.broker.RuntimePeerPolicy
                                            .COMPANION_RELEASE_PACKAGE)
                            .equals(identity.processName)
                        || com.warden.controlledsandbox.runtime.broker.RuntimePeerPolicy
                            .companionBrokerProcess(
                                    com.warden.controlledsandbox.runtime.broker.RuntimePeerPolicy
                                            .COMPANION_DEBUG_PACKAGE)
                            .equals(identity.processName))) {
                return;
            }
        }
        throw new SecurityException("RUNTIME_PERMISSION_CALLER_NOT_COMPANION_BROKER");
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
