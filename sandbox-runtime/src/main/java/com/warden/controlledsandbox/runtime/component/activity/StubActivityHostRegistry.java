package com.warden.controlledsandbox.runtime.component.activity;

import android.content.Intent;
import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local map from virtual Activity token to the live Host trampoline.
 * Token correlation replaces Guest-declaration-index physical classes.
 */
public final class StubActivityHostRegistry {
    private static final ConcurrentMap<String, StubActivityBase> LIVE = new ConcurrentHashMap<>();

    private StubActivityHostRegistry() { }

    static void register(String activityToken, StubActivityBase activity) {
        if (activityToken == null || activityToken.isBlank() || activity == null) return;
        LIVE.put(activityToken, activity);
    }

    static void unregister(String activityToken, StubActivityBase activity) {
        if (activityToken == null || activityToken.isBlank()) return;
        LIVE.remove(activityToken, activity);
    }

    static boolean isLive(String activityToken) {
        return activityToken != null && LIVE.containsKey(activityToken);
    }

    public static Bundle apply(Bundle request) {
        Bundle out = new Bundle();
        if (request == null) {
            out.putString(RuntimeKeys.STATUS, "FAILED");
            out.putString(RuntimeKeys.ERROR_TYPE, "HOST_ACTIVITY_DECISION_MISSING");
            return out;
        }
        List<String> removed = request.getStringArrayList(RuntimeKeys.REMOVED_ACTIVITY_TOKENS);
        int finished = 0;
        if (removed != null) {
            for (String token : removed) {
                if (finish(token)) finished++;
            }
        }
        String target = request.getString(RuntimeKeys.ACTIVITY_TOKEN, "");
        String action = request.getString(RuntimeKeys.ACTIVITY_ACTION, "");
        StubActivityBase live = target.isBlank() ? null : LIVE.get(target);
        if (live == null) {
            out.putString(RuntimeKeys.STATUS, "HOST_ACTIVITY_NOT_LIVE");
            out.putInt(RuntimeKeys.REMOVED_ACTIVITY_COUNT, finished);
            return out;
        }
        if ("REORDERED_TO_FRONT".equals(action)) {
            live.postMoveHostTaskToFront();
        }
        if ("DELIVERED_NEW_INTENT".equals(action) || "CLEARED_TOP".equals(action)
                || "REORDERED_TO_FRONT".equals(action)) {
            live.postRoutedNewIntent(request);
        }
        out.putString(RuntimeKeys.STATUS, "APPLIED");
        out.putInt(RuntimeKeys.REMOVED_ACTIVITY_COUNT, finished);
        out.putString(RuntimeKeys.ACTIVITY_TOKEN, target);
        return out;
    }

    static boolean finish(String activityToken) {
        StubActivityBase activity = activityToken == null ? null : LIVE.get(activityToken);
        if (activity == null) return false;
        activity.postFinishHost();
        return true;
    }

    static List<String> liveTokens() {
        return new ArrayList<>(LIVE.keySet());
    }
}
