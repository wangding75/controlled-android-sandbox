package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/**
 * A package-neutral configuration boundary probe.  The runner performs the physical rotation;
 * this Activity only records the real recreation or configuration callback that Android delivers.
 */
public final class P109RotationProbeActivity extends Activity {
    private static final String TAG = "CS_FIXTURE";
    private static final String SAVED_PENDING = "p1_09_rotation_pending";
    private boolean terminalReported;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView content = new TextView(this);
        content.setText("P1-09 rotation probe\norientation=" + orientation());
        setContentView(content);
        if (state != null && state.getBoolean(SAVED_PENDING, false)) {
            reportPass("RECREATED");
        } else {
            Log.i(TAG, "P1_09_ROTATION_READY orientation=" + orientation());
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(SAVED_PENDING, true);
        Log.i(TAG, "P1_09_ROTATION_STATE_SAVED orientation=" + orientation());
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        reportPass("CONFIGURATION_CHANGED");
    }

    private void reportPass(String path) {
        if (terminalReported) return;
        terminalReported = true;
        Log.i(TAG, "P1_09_ROTATION_PASS path=" + path + " orientation=" + orientation());
        finish();
    }

    private String orientation() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? "landscape" : "portrait";
    }
}
