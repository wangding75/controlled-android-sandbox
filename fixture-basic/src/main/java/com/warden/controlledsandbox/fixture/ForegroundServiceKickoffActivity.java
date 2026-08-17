package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Starts a framework-owned foreground Service so the real ActivityThread/AMS edge is exercised. */
public final class ForegroundServiceKickoffActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent(this, FixtureService.class)
                .setAction("com.warden.controlledsandbox.fixture.FOREGROUND_SERVICE");
        ComponentName started = startForegroundService(intent);
        if (started == null) throw new AssertionError("FOREGROUND_SERVICE_START_NULL");
        Log.i("CS_FIXTURE", "FRAMEWORK_FGS_KICKOFF_REQUESTED component="
                + started.flattenToShortString());
        // Keep the framework Activity window alive long enough for the launch gate to observe
        // resume/window attachment; the Service remains started after this Activity finishes.
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500L);
    }
}
