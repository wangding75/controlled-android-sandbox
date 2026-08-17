package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Main-thread stall long enough to be an ANR. Do not shorten to avoid ANR. */
public final class FaultAnrActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("CS_FAULT", "ANR_ACTIVITY_BEGIN");
        try {
            Thread.sleep(25_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Log.i("CS_FAULT", "ANR_ACTIVITY_END");
    }
}
