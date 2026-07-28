package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.framework.activity.ActivityIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityLaunchSpec;
import com.warden.controlledsandbox.framework.activity.DocumentLaunchMode;
import com.warden.controlledsandbox.framework.activity.LaunchFlags;
import com.warden.controlledsandbox.framework.activity.LaunchMode;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Centralized version-tolerant mapping from runtime envelopes to typed Activity launch input. */
final class ActivityLaunchSpecFactory {
    private ActivityLaunchSpecFactory() { }

    static ActivityLaunchSpec create(GuestSession session, String component, Bundle request) {
        Bundle input = request == null ? new Bundle() : request;
        DocumentLaunchMode documentMode = parseDocumentMode(
                input.getString(RuntimeKeys.DOCUMENT_LAUNCH_MODE, "NONE"));
        int flags = normalizedFlags(
                input.getInt(RuntimeKeys.ACTIVITY_FLAGS, LaunchFlags.NEW_TASK), documentMode);
        Integer callerTaskId = input.getInt(RuntimeKeys.CALLER_TASK_ID, 0) > 0
                ? input.getInt(RuntimeKeys.CALLER_TASK_ID, 0) : null;
        return new ActivityLaunchSpec(
                new ActivityIdentity(session.virtualUserId(), session.packageName(), component),
                input.getString(RuntimeKeys.TASK_AFFINITY, session.packageName()),
                parseLaunchMode(input.getString(RuntimeKeys.ACTIVITY_LAUNCH_MODE, "STANDARD")),
                flags,
                callerTaskId,
                session.processName(),
                session.generation(),
                input.getString(RuntimeKeys.RESULT_WHO, ""),
                input.getInt(RuntimeKeys.REQUEST_CODE, -1),
                session.packageRevision(),
                documentMode,
                input.getString(RuntimeKeys.DOCUMENT_KEY, ""),
                input.getString(RuntimeKeys.ACTIVITY_RESULT_KEY, ""),
                input.getString(RuntimeKeys.INTENT_SENDER_TOKEN, ""));
    }

    private static LaunchMode parseLaunchMode(String value) {
        try {
            return LaunchMode.valueOf(
                    value == null ? "STANDARD" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown launch mode: " + value, error);
        }
    }

    private static DocumentLaunchMode parseDocumentMode(String value) {
        try {
            return DocumentLaunchMode.valueOf(
                    value == null ? "NONE" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown document launch mode: " + value, error);
        }
    }

    private static int normalizedFlags(int flags, DocumentLaunchMode documentMode) {
        if (documentMode == DocumentLaunchMode.ALWAYS) {
            return flags | LaunchFlags.NEW_TASK | LaunchFlags.NEW_DOCUMENT
                    | LaunchFlags.MULTIPLE_TASK;
        }
        if (documentMode == DocumentLaunchMode.INTO_EXISTING) {
            return flags | LaunchFlags.NEW_TASK | LaunchFlags.NEW_DOCUMENT;
        }
        return flags;
    }
}
