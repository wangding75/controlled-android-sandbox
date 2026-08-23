package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Validates that the virtual PMS contract is the same contract consumed by ActivityThread. */
public final class PersistableProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        verifyFrameworkContract();
    }

    private void verifyFrameworkContract() {
        try {
            ComponentName component = new ComponentName(this, PersistableProbeActivity.class);
            Class<?> packageManager = Class.forName("android.content.pm.PackageManager");
            Method getActivityInfo = packageManager.getMethod("getActivityInfo",
                    ComponentName.class, int.class);
            Object info = getActivityInfo.invoke(getPackageManager(), component, 0);
            Class<?> infoType = info.getClass();
            int launchMode = intField(infoType, info, "launchMode");
            int documentLaunchMode = intField(infoType, info, "documentLaunchMode");
            int persistableMode = intField(infoType, info, "persistableMode");
            String taskAffinity = (String) infoType.getField("taskAffinity").get(info);
            int singleTask = staticInt(infoType, "LAUNCH_SINGLE_TASK");
            int documentAlways = staticInt(infoType, "DOCUMENT_LAUNCH_ALWAYS");
            int persistAcrossReboots = staticInt(infoType, "PERSIST_ACROSS_REBOOTS");
            if (launchMode != singleTask || documentLaunchMode != documentAlways
                    || persistableMode != persistAcrossReboots || taskAffinity == null
                    || !taskAffinity.endsWith(".persistable")) {
                throw new AssertionError("ACTIVITY_INFO_CONTRACT_MISMATCH launch=" + launchMode
                        + " document=" + documentLaunchMode + " persist=" + persistableMode
                        + " affinity=" + taskAffinity);
            }
            Log.i(TAG, "FRAMEWORK_PROBE_ACTIVITY_CONTRACT_PASS launch=" + launchMode
                    + " document=" + documentLaunchMode + " persist=" + persistableMode
                    + " affinity=" + taskAffinity);
            // Serialize this probe with the task-semantics probe. Starting both from the parent
            // in the same main-thread turn lets the second physical stub launch supersede the
            // first before ActivityThread instantiates it on slower/device-busy runs.
            startActivity(new Intent(this, TaskSemanticsProbeActivity.class)
                    .setAction(getPackageName() + ".TASK_SEMANTICS_PROBE"));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new AssertionError("ACTIVITY_INFO_CONTRACT_QUERY_FAILED", error);
        } finally {
            finish();
        }
    }

    private static int intField(Class<?> type, Object target, String name) throws Exception {
        return type.getField(name).getInt(target);
    }

    private static int staticInt(Class<?> type, String name) throws Exception {
        return type.getField(name).getInt(null);
    }
}
