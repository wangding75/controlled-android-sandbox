package com.warden.controlledsandbox.runtime.diagnostics;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import android.util.Log;

public final class RuntimeEventLog {
    static final String TAG = "CS_RUNTIME";
    private RuntimeEventLog() { }

    public static void event(String name, Bundle data) {
        Bundle normalized = data == null ? new Bundle() : new Bundle(data);
        if (!normalized.containsKey("traceDomain")) {
            normalized.putString("traceDomain", domain(name));
        }
        if (!normalized.containsKey("physicalPid")) {
            normalized.putInt("physicalPid", android.os.Process.myPid());
        }
        if (!normalized.containsKey("threadTid")) {
            normalized.putInt("threadTid", threadTid());
        }
        StringBuilder line = new StringBuilder(name);
        {
            append(line, "status", normalized.getString(RuntimeKeys.STATUS, ""));
            append(line, "package", normalized.getString(RuntimeKeys.PACKAGE_NAME, ""));
            append(line, "session", normalized.getString(RuntimeKeys.SESSION_ID, ""));
            append(line, "traceDomain", normalized.getString("traceDomain", ""));
            append(line, "launchId", normalized.getString("launchId", ""));
            append(line, "binderToken", normalized.getString("binderToken", ""));
            append(line, "processName", normalized.getString(RuntimeKeys.PROCESS_NAME, ""));
            if (normalized.containsKey(RuntimeKeys.VIRTUAL_USER_ID)) {
                line.append(" virtualUserId=").append(normalized.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1));
            }
            line.append(" physicalPid=").append(normalized.getInt("physicalPid", -1));
            line.append(" threadTid=").append(normalized.getInt("threadTid", -1));
            append(line, "component", normalized.getString(RuntimeKeys.COMPONENT_CLASS, ""));
            append(line, "activityToken", normalized.getString(RuntimeKeys.ACTIVITY_TOKEN, ""));
            if (normalized.containsKey(RuntimeKeys.TASK_ID)) {
                line.append(" taskId=").append(normalized.getInt(RuntimeKeys.TASK_ID, 0));
            }
            append(line, "windowIdentity", normalized.getString("windowIdentity", ""));
            append(line, "windowToken", normalized.getString("windowToken", ""));
            append(line, "windowLayoutToken", normalized.getString("windowLayoutToken", ""));
            append(line, "frameworkTask", normalized.getString("frameworkTask", ""));
            append(line, "frameworkActivityToken", normalized.getString("frameworkActivityToken", ""));
            append(line, "activityClientRecord", normalized.getString("activityClientRecord", ""));
            append(line, "windowStage", normalized.getString("windowStage", ""));
            if (normalized.containsKey("windowAttached")) {
                line.append(" windowAttached=").append(normalized.getBoolean("windowAttached", false));
            }
            if (normalized.containsKey("windowRegistered")) {
                line.append(" windowRegistered=").append(normalized.getBoolean("windowRegistered", false));
            }
            if (normalized.containsKey("windowAddedMarker")) {
                line.append(" windowAddedMarker=").append(normalized.getBoolean("windowAddedMarker", false));
            }
            if (normalized.containsKey("ownerEpoch")) {
                line.append(" ownerEpoch=").append(normalized.getLong("ownerEpoch", 0));
            }
            if (normalized.containsKey("windowRootCount")) {
                line.append(" windowRootCount=").append(normalized.getInt("windowRootCount", -1));
            }
            append(line, "error", normalized.getString(RuntimeKeys.ERROR_TYPE, ""));
            append(line, "message", normalized.getString(RuntimeKeys.ERROR_MESSAGE, ""));
            line.append(" generation=").append(normalized.getLong(RuntimeKeys.GENERATION, 0));
            line.append(" slot=").append(normalized.getInt(RuntimeKeys.PROCESS_SLOT, -1));
        }
        Log.i(TAG, line.toString());
        RuntimeDiagnostics.record(name, normalized, line.toString());
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
        Bundle fields = new Bundle();
        fields.putString("traceDomain", "CRASH");
        fields.putInt("physicalPid", android.os.Process.myPid());
        fields.putInt("threadTid", threadTid());
        fields.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getName());
        fields.putString(RuntimeKeys.ERROR_MESSAGE, String.valueOf(error.getMessage()));
        RuntimeDiagnostics.record(name, fields, detail.toString());
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
        Bundle fields = new Bundle();
        fields.putString("traceDomain", "FRAMEWORK");
        fields.putInt("physicalPid", android.os.Process.myPid());
        fields.putInt("threadTid", threadTid());
        fields.putString("auditAction", safeAction);
        fields.putString("auditOutcome", safeOutcome);
        RuntimeDiagnostics.record(eventName, fields, line);
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.trim().replace(' ', '_').replace('\n', '_').replace('\r', '_');
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static void append(StringBuilder line, String key, String value) {
        if (value != null && !value.isEmpty()) line.append(' ').append(key).append('=').append(value.replace(' ', '_'));
    }

    private static int threadTid() {
        try {
            Object value = android.os.Process.class.getMethod("myTid").invoke(null);
            return value instanceof Integer ? (Integer) value : (int) Thread.currentThread().getId();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return (int) Thread.currentThread().getId();
        }
    }

    private static String domain(String name) {
        String value = name == null ? "" : name;
        if (value.startsWith("GUEST_") || value.startsWith("VIRTUAL_")) return "GUEST";
        if (value.startsWith("ISOLATED_") || value.startsWith("BROKER_")) return "BROKER";
        if (value.startsWith("NATIVE_")) return "NATIVE";
        if (value.startsWith("ANR_")) return "ANR";
        return "FRAMEWORK";
    }
}
