package com.warden.controlledsandbox.runtime.diagnostics;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Single trace entry point for cross-process Guest runtime correlation evidence. */
public final class GuestRuntimeTrace {
    public enum Domain { FRAMEWORK, BROKER, GUEST, BINDER, PROCESS, NATIVE, CRASH, ANR }

    private GuestRuntimeTrace() { }

    public static void event(Domain domain, String name, Bundle fields) {
        Bundle data = fields == null ? new Bundle() : new Bundle(fields);
        data.putString("traceDomain", domain == null ? Domain.GUEST.name() : domain.name());
        if (!data.containsKey("threadTid")) data.putInt("threadTid", threadTid());
        if (!data.containsKey("physicalPid")) data.putInt("physicalPid", android.os.Process.myPid());
        RuntimeEventLog.event(name, data);
    }

    public static void failure(Domain domain, String name, Throwable error, Bundle fields) {
        Bundle data = fields == null ? new Bundle() : new Bundle(fields);
        data.putString("traceDomain", domain == null ? Domain.CRASH.name() : domain.name());
        data.putInt("threadTid", threadTid());
        data.putInt("physicalPid", android.os.Process.myPid());
        data.putString(RuntimeKeys.ERROR_TYPE, error == null ? "" : error.getClass().getName());
        data.putString(RuntimeKeys.ERROR_MESSAGE, error == null ? "" : String.valueOf(error.getMessage()));
        RuntimeEventLog.event(name, data);
    }

    private static int threadTid() {
        try {
            Object value = android.os.Process.class.getMethod("myTid").invoke(null);
            return value instanceof Integer ? (Integer) value : (int) Thread.currentThread().getId();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return (int) Thread.currentThread().getId();
        }
    }
}
