package com.warden.controlledsandbox.fixture.scale;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public final class ScaleResultCallerActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText(getClass().getName());
        setContentView(view);
        if (state == null) {
            Intent request = new Intent(this, ScaleActivity127.class);
            request.setAction(ScaleActivityBase.ACTION_RETURN_RESULT);
            startActivityForResult(request, 127);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        TextView view = new TextView(this);
        view.setText("result=" + resultCode + " code=" + requestCode
                + " from=" + (data == null ? "" : data.getStringExtra("scaleActivity")));
        setContentView(view);
    }
}
