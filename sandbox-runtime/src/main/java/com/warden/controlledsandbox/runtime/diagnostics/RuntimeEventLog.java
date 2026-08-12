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
            append(line, "activityToken", data.getString(RuntimeKeys.ACTIVITY_TOKEN, ""));
            if (data.containsKey(RuntimeKeys.TASK_ID)) {
                line.append(" taskId=").append(data.getInt(RuntimeKeys.TASK_ID, 0));
            }
            append(line, "windowIdentity", data.getString("windowIdentity", ""));
            append(line, "windowToken", data.getString("windowToken", ""));
            append(line, "frameworkTask", data.getString("frameworkTask", ""));
            append(line, "frameworkActivityToken", data.getString("frameworkActivityToken", ""));
            append(line, "activityClientRecord", data.getString("activityClientRecord", ""));
            append(line, "windowStage", data.getString("windowStage", ""));
            if (data.containsKey("windowAttached")) {
                line.append(" windowAttached=").append(data.getBoolean("windowAttached", false));
            }
            if (data.containsKey("windowRegistered")) {
                line.append(" windowRegistered=").append(data.getBoolean("windowRegistered", false));
            }
            if (data.containsKey("ownerEpoch")) {
                line.append(" ownerEpoch=").append(data.getLong("ownerEpoch", 0));
            }
            if (data.containsKey("windowRootCount")) {
                line.append(" windowRootCount=").append(data.getInt("windowRootCount", -1));
            }
            append(line, "error", data.getString(RuntimeKeys.ERROR_TYPE, ""));
            append(line, "message", data.getString(RuntimeKeys.ERROR_MESSAGE, ""));
            line.append(" generation=").append(data.getLong(RuntimeKeys.GENERATION, 0));
            line.append(" slot=").append(data.getInt(RuntimeKeys.PROCESS_SLOT, -1));
        }
        Log.i(TAG, line.toString());
        RuntimeDiagnostics.record(name, data, line.toString());
    }

    public static void failure(String name, Throwable error) {
        StringBuilder detail = new StringBuilder(name)
                .append(" type=").append(error.getClass().getName())
                .append(" message=").append(String.valueOf(error.getMessage()));
        StackTraceElement[] stack = error.getStackTrace();
        for (int index = 0; index < Math.min(stack.length, 12); index++) {
            detail.append(" at=").append(stack[index]);
        }
        Log.e(TAG, detail.toString());
        RuntimeDiagnostics.record(name, null, detail.toString());
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
