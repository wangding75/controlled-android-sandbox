package com.warden.controlledsandbox.fixture.scale;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public abstract class ScaleActivityBase extends Activity {
    public static final String ACTION_RETURN_RESULT =
            "com.warden.controlledsandbox.fixture.scale.RETURN_RESULT";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText(getClass().getName());
        view.setPadding(32, 32, 32, 32);
        setContentView(view);
        if (ACTION_RETURN_RESULT.equals(getIntent() == null ? "" : getIntent().getAction())) {
            Intent result = new Intent();
            result.putExtra("scaleActivity", getClass().getName());
            setResult(RESULT_OK, result);
            finish();
        }
    }
}
