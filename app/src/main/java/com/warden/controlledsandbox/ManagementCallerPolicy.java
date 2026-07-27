package com.warden.controlledsandbox;

import java.util.List;

/** Pure policy for authorizing the one host main process allowed to mint management capabilities. */
final class ManagementCallerPolicy {
    private ManagementCallerPolicy() { }

    static boolean isAuthorized(int callingUid, int callingPid, int hostUid,
                                String expectedProcessName, List<ProcessIdentity> processes) {
        if (callingUid != hostUid || callingPid <= 0 || expectedProcessName == null
                || expectedProcessName.trim().isEmpty() || processes == null) return false;
        for (ProcessIdentity process : processes) {
            if (process != null && process.pid == callingPid && process.uid == callingUid
                    && expectedProcessName.equals(process.processName)) return true;
        }
        return false;
    }

    static final class ProcessIdentity {
        final int pid;
        final int uid;
        final String processName;
        ProcessIdentity(int pid, int uid, String processName) {
            this.pid = pid;
            this.uid = uid;
            this.processName = processName == null ? "" : processName;
        }
    }
}
