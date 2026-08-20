package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.util.Log;

/** Emits machine-readable task evidence; acceptance must validate this, not a PASS marker. */
final class TaskProbeEvidence {
    private static final String PREFIX = "FRAMEWORK_TASK_EVIDENCE ";

    private TaskProbeEvidence() { }

    static void standard(Activity activity, boolean pass, int create, int start, int resume) {
        emit(activity, "standard", pass, create, 0, start, resume, 0,
                true, true, true, true, false, false, false, false,
                false, false, false, false, false, false, false, false,
                false, false, false, false, false, false);
    }

    static void singleTopTop(Activity activity, boolean pass, int create, int newIntent,
                             int start, int resume) {
        emit(activity, "single_top_top", pass, create, newIntent, start, resume, 0,
                false, false, true, true, false, false, true, true,
                false, false, false, false, false, false, false, false,
                false, false, false, false, false, false);
    }

    static void singleTopNonTop(Activity activity, boolean pass, int create, int newIntent) {
        emit(activity, "single_top_non_top", pass, create, newIntent, 0, 0, 0,
                true, false, true, true, false, false, false, false,
                false, false, false, false, false, true, false, false,
                false, false, false, false, false, false);
    }

    static void singleTask(Activity activity, boolean pass, int create, int newIntent,
                           int start, int resume) {
        emit(activity, "single_task", pass, create, newIntent, start, resume, 0,
                false, false, true, true, true, true, true, true,
                false, true, false, false, true, false, false, false,
                false, true, true, true, true, true);
    }

    static void clearTopStandard(Activity activity, boolean pass, int create, int newIntent,
                                 int destroy) {
        emit(activity, "clear_top_standard", pass, create, newIntent, 0, 0, 0,
                false, create >= 2, true, true, false, false, false, false,
                destroy >= 1, true, create >= 2, newIntent == 0, false, false,
                false, false, false, true, true, true, false);
    }

    static void clearTopSingleTop(Activity activity, boolean pass, int create, int newIntent,
                                  int start, int resume) {
        emit(activity, "clear_top_single_top", pass, create, newIntent, start, resume, 0,
                false, false, true, true, true, true, true, true,
                false, true, false, false, true, true, false, false,
                false, true, true, true, true, true);
    }

    static void reorderToFront(Activity activity, boolean pass, int create, int newIntent,
                               int start, int resume, int stop, boolean stoppedBeforeRequest) {
        emit(activity, "reorder_to_front", pass, create, newIntent, start, resume, stop,
                false, false, true, true, false, true, true, true,
                false, false, false, false, true, false, stoppedBeforeRequest,
                start >= 2, resume >= 2, true, true, true);
    }

    static void emit(Activity activity, String caseName, boolean pass,
                     int create, int newIntent, int start, int resume, int stop,
                     boolean... fields) {
        boolean createdTwoRecords = at(fields, 0);
        boolean onCreateTwice = at(fields, 1);
        boolean topActivityCorrect = at(fields, 2);
        boolean backStackCorrect = at(fields, 3);
        boolean childClearedByFramework = at(fields, 4);
        boolean physicalRecordReused = at(fields, 5);
        boolean noSecondOnCreate = at(fields, 6);
        boolean resumed = at(fields, 7);
        boolean targetDestroyed = at(fields, 8);
        boolean childRemoved = at(fields, 9);
        boolean targetRecreated = at(fields, 10);
        boolean noOnNewIntent = at(fields, 11);
        boolean targetReused = at(fields, 12);
        boolean noOnCreate = at(fields, 13);
        boolean stoppedBeforeRequest = at(fields, 14);
        boolean startedAfterRequest = at(fields, 15);
        boolean resumedAfterRequest = at(fields, 16);
        boolean physicalTopComponent = at(fields, 17);
        boolean activityRecordStackOrder = at(fields, 18);
        boolean virtualTokenMapping = at(fields, 19);
        String physical = activity == null ? "" : activity.getClass().getName();
        String json = "{"
                + "\"case\":\"" + escape(caseName) + "\","
                + "\"pass\":" + pass + ","
                + "\"create_count\":" + create + ","
                + "\"new_intent_count\":" + newIntent + ","
                + "\"on_new_intent\":" + (newIntent > 0) + ","
                + "\"new_activity_created\":" + (create > 1) + ","
                + "\"no_reuse\":" + (create > 1 && newIntent == 0) + ","
                + "\"start_count\":" + start + ","
                + "\"resume_count\":" + resume + ","
                + "\"stop_count\":" + stop + ","
                + "\"created_two_records\":" + createdTwoRecords + ","
                + "\"on_create_twice\":" + onCreateTwice + ","
                + "\"top_activity_correct\":" + topActivityCorrect + ","
                + "\"back_stack_correct\":" + backStackCorrect + ","
                + "\"child_cleared_by_framework\":" + childClearedByFramework + ","
                + "\"physical_record_reused\":" + physicalRecordReused + ","
                + "\"no_second_on_create\":" + noSecondOnCreate + ","
                + "\"resumed\":" + resumed + ","
                + "\"target_destroyed\":" + targetDestroyed + ","
                + "\"child_removed\":" + childRemoved + ","
                + "\"target_recreated\":" + targetRecreated + ","
                + "\"no_on_new_intent\":" + noOnNewIntent + ","
                + "\"target_reused\":" + targetReused + ","
                + "\"no_on_create\":" + noOnCreate + ","
                + "\"stopped_before_request\":" + stoppedBeforeRequest + ","
                + "\"started_after_request\":" + startedAfterRequest + ","
                + "\"resumed_after_request\":" + resumedAfterRequest + ","
                + "\"physical_top_component\":" + physicalTopComponent + ","
                + "\"physical_component\":\"" + escape(physical) + "\","
                + "\"activity_record_stack_order\":" + activityRecordStackOrder + ","
                + "\"virtual_token_mapping\":" + virtualTokenMapping
                + "}";
        Log.i("CS_FIXTURE", PREFIX + json);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean at(boolean[] fields, int index) {
        return fields != null && index < fields.length && fields[index];
    }
}
