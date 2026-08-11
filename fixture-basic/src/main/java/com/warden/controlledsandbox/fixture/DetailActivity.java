package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class DetailActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int count = getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("detailCreates", 0) + 1;
        getSharedPreferences("fixture", Context.MODE_PRIVATE).edit().putInt("detailCreates", count).commit();
        TextView content = new TextView(this);
        content.setText("Detail Activity create #" + count);
        setContentView(content);
        Log.i("CS_FIXTURE", "DETAIL_CREATE count=" + count + " process=" + getApplicationInfo().processName);
    }

    @Override protected void onResume() { super.onResume(); Log.i("CS_FIXTURE", "DETAIL_RESUME"); }
    @Override protected void onPause() { Log.i("CS_FIXTURE", "DETAIL_PAUSE"); super.onPause(); }
    @Override protected void onDestroy() { Log.i("CS_FIXTURE", "DETAIL_DESTROY"); super.onDestroy(); }
}
