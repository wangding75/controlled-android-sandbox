package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

/** A short-lived visible Activity used only to witness a real, request-bound first frame. */
public final class P109VisualProbeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView content = new TextView(this);
        content.setText("P1-09 visible frame probe");
        setContentView(content);
        Log.i("CS_FIXTURE", "P1_09_VISUAL_READY");
        // The delay is only fixture teardown coordination.  The launch gate independently
        // observes the first draw and does not treat this marker as frame evidence.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i("CS_FIXTURE", "P1_09_VISUAL_FINISHED");
            finish();
        }, 700L);
    }
}
