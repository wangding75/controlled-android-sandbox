package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

public final class MainActivity extends Activity {
    private WebView webView;
    private DynamicFixtureReceiver dynamicReceiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int count = getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("activityCreates", 0) + 1;
        getSharedPreferences("fixture", Context.MODE_PRIVATE).edit().putInt("activityCreates", count).commit();
        dynamicReceiver = new DynamicFixtureReceiver();
        IntentFilter filter = new IntentFilter("com.warden.controlledsandbox.fixture.DYNAMIC_PING");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dynamicReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(dynamicReceiver, filter);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadDataWithBaseURL("https://fixture.invalid/",
                "<html><body><h1>Controlled Sandbox Fixture</h1><p id='state'>Activity create #" + count + "</p></body></html>",
                "text/html", "UTF-8", null);
        setContentView(webView);
        Log.i("CS_FIXTURE", "ACTIVITY_CREATE count=" + count + " files=" + getFilesDir());
        Log.i("CS_FIXTURE", "NATIVE_PROBE " + FixtureNative.probe());
    }

    @Override protected void onResume() { super.onResume(); Log.i("CS_FIXTURE", "ACTIVITY_RESUME"); }
    @Override protected void onPause() { Log.i("CS_FIXTURE", "ACTIVITY_PAUSE"); super.onPause(); }
    @Override protected void onDestroy() {
        if (dynamicReceiver != null) unregisterReceiver(dynamicReceiver);
        if (webView != null) webView.destroy();
        Log.i("CS_FIXTURE", "ACTIVITY_DESTROY");
        super.onDestroy();
    }
}
