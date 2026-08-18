package com.warden.controlledsandbox.fixture.lifecycle;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class LifecycleActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("lifecycle-v1");
        setContentView(view);
    }
}
