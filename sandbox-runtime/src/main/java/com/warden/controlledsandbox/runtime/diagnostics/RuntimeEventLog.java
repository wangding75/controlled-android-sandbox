package com.warden.controlledsandbox.runtime.diagnostics;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import android.util.Log;

public final class RuntimeEventLog {
    static final String TAG = "CS_RUNTIME";
    private RuntimeEventLog() { }

    public static void event(String name, Bundle data) {
        StringBuilder line = new StringBuilder(name);
        if (data != null) {
            append(line, "status", data.getString(RuntimeKeys.STATUS, ""));
            append(line, "package", data.getString(RuntimeKeys.PACKAGE_NAME, ""));
            append(line, "session", data.getString(RuntimeKeys.SESSION_ID, ""));
            append(line, "component", data.getString(RuntimeKeys.COMPONENT_CLASS, ""));
            line.append(" generation=").append(data.getLong(RuntimeKeys.GENERATION, 0));
            line.append(" slot=").append(data.getInt(RuntimeKeys.PROCESS_SLOT, -1));
        }
        Log.i(TAG, line.toString());
        RuntimeDiagnostics.record(name, data, line.toString());
    }

    static void failure(String name, Throwable error) {
        String detail = name + " type=" + error.getClass().getName() + " message=" + String.valueOf(error.getMessage());
        Log.e(TAG, detail);
        RuntimeDiagnostics.record(name, null, detail);
    }

    public static void audit(String category, String action, String outcome, String detail) {
        String safeCategory = safe(category, 48);
        String safeAction = safe(action, 48);
        String safeOutcome = safe(outcome, 32);
        String safeDetail = safe(detail, 256);
        String eventName = "AUDIT_" + safeCategory.toUpperCase(java.util.Locale.ROOT);
        String line = eventName + " action=" + safeAction + " outcome=" + safeOutcome
                + (safeDetail.isEmpty() ? "" : " detail=" + safeDetail);
        Log.i(TAG, line);
        RuntimeDiagnostics.record(eventName, null, line);
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.trim().replace(' ', '_').replace('\n', '_').replace('\r', '_');
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static void append(StringBuilder line, String key, String value) {
        if (value != null && !value.isEmpty()) line.append(' ').append(key).append('=').append(value.replace(' ', '_'));
    }
}
