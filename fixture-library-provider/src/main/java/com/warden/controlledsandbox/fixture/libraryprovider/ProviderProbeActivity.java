package com.warden.controlledsandbox.fixture.libraryprovider;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Direct-install witness that the provider package itself is launchable. */
public final class ProviderProbeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i("CS_P1_02_FIXTURE", "PROVIDER_PACKAGE_LAUNCH_PASS class="
                + ProviderLibraryMarker.value());
        finish();
    }
}
