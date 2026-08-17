package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Starts a sticky framework-owned foreground Service and leaves no Activity task behind. */
public final class RecoveryServiceKickoffActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent(this, FixtureService.class)
                .setAction("com.warden.controlledsandbox.fixture.FOREGROUND_SERVICE");
        ComponentName started = startForegroundService(intent);
        if (started == null) throw new AssertionError("RECOVERY_SERVICE_START_NULL");
        Log.i("CS_FIXTURE", "RECOVERY_KICKOFF_SERVICE_REQUESTED component="
                + started.flattenToShortString());
        finish();
    }
}
