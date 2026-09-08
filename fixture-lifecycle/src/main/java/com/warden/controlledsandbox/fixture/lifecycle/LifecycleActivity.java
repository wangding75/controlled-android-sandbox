package com.warden.controlledsandbox.fixture.lifecycle;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class LifecycleActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LifecycleProbe.record("activity.onCreate");
        TextView view = new TextView(this);
        String marker = LifecycleProbe.bootstrapMarker();
        android.util.Log.i("CS_P1_04_FIXTURE", marker);
        view.setText("lifecycle-v1\n" + marker);
        setContentView(view);
    }
}
