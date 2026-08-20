package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Emits only fixture observations; system semantic assertions belong to the runner. */
final class TaskProbeEvidence {
    private static final String PREFIX = "FRAMEWORK_TASK_EVIDENCE ";
    private static final String EVENT_PREFIX = "FRAMEWORK_TASK_EVENT ";

    private TaskProbeEvidence() { }

    static void standard(Activity activity, int create, int newIntent, int start, int resume,
                         int stop, int destroy) {
        emit(activity, "standard", create, newIntent, start, resume, stop, destroy);
    }

    static void singleTopTop(Activity activity, int create, int newIntent, int start, int resume,
                             int stop, int destroy) {
        emit(activity, "single_top_top", create, newIntent, start, resume, stop, destroy);
    }

    static void singleTopNonTop(Activity activity, int create, int newIntent, int start, int resume,
                                int stop, int destroy) {
        emit(activity, "single_top_non_top", create, newIntent, start, resume, stop, destroy);
    }

    static void singleTask(Activity activity, int create, int newIntent, int start, int resume,
                           int stop, int destroy) {
        emit(activity, "single_task", create, newIntent, start, resume, stop, destroy);
    }

    static void clearTopStandard(Activity activity, int create, int newIntent, int start,
                                 int resume, int stop, int destroy) {
        emit(activity, "clear_top_standard", create, newIntent, start, resume, stop, destroy);
    }

    static void clearTopSingleTop(Activity activity, int create, int newIntent, int start,
                                  int resume, int stop, int destroy) {
        emit(activity, "clear_top_single_top", create, newIntent, start, resume, stop, destroy);
    }

    static void reorderToFront(Activity activity, int create, int newIntent, int start, int resume,
                               int stop, int destroy) {
        emit(activity, "reorder_to_front", create, newIntent, start, resume, stop, destroy);
    }

    /** Emit a request/transition event; the runner correlates it with dumpsys snapshots. */
    static void event(Activity activity, String caseName, String name) {
        String routeToken = token(activity, "routeToken");
        String activityToken = token(activity, "activityToken");
        String json = "{\"case\":\"" + escape(caseName) + "\","
                + "\"event\":\"" + escape(name) + "\","
                + "\"route_token\":\"" + escape(routeToken) + "\","
                + "\"activity_token\":\"" + escape(activityToken) + "\"}";
        Log.i("CS_FIXTURE", EVENT_PREFIX + json);
    }

    /** Execute a real framework Back operation after the transition has settled. */
    static void requestBackAfterEvidence(Activity activity, String caseName, Runnable afterBack) {
        event(activity, caseName, "BACK_REQUEST");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            activity.onBackPressed();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                event(activity, caseName, "BACK_COMPLETE");
                if (afterBack != null) afterBack.run();
            }, 450L);
        }, 300L);
    }

    private static void emit(Activity activity, String caseName, int create, int newIntent,
                             int start, int resume, int stop, int destroy) {
        String json = "{"
                + "\"case\":\"" + escape(caseName) + "\","
                + "\"lifecycle\":{"
                + "\"onCreate\":" + create + ","
                + "\"onNewIntent\":" + newIntent + ","
                + "\"onStart\":" + start + ","
                + "\"onResume\":" + resume + ","
                + "\"onStop\":" + stop + ","
                + "\"onDestroy\":" + destroy
                + "},"
                + "\"request_timing\":\"post_framework_transition\","
                + "\"route_token\":\"" + escape(token(activity, "routeToken")) + "\","
                + "\"activity_token\":\"" + escape(token(activity, "activityToken")) + "\""
                + "}";
        Log.i("CS_FIXTURE", PREFIX + json);
    }

    private static String token(Activity activity, String key) {
        if (activity == null || activity.getIntent() == null) return "";
        String value = activity.getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
