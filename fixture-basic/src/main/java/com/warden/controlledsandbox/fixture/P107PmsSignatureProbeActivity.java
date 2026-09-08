package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * Small P1-07 fixture deliberately limited to absence semantics.  It avoids P1-05's remote
 * lifecycle callback and reaches both raw-PMS owner-classification paths through public Context
 * and ContentResolver APIs after the CAS Guest hooks are installed.
 */
public final class P107PmsSignatureProbeActivity extends Activity {
    private static final String TAG = "CS_P1_07_FIXTURE";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean serviceAbsent = false;
        boolean providerAbsent = false;
        try {
            Intent missingService = new Intent("com.warden.controlledsandbox.fixture.P1_07_MISSING")
                    .setComponent(new ComponentName(getPackageName(),
                            getPackageName() + ".P107MissingService"));
            serviceAbsent = !bindService(missingService, new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    throw new AssertionError("P1_07_MISSING_SERVICE_CONNECTED");
                }

                @Override public void onServiceDisconnected(ComponentName name) { }
            }, 0);
            String type = getContentResolver().getType(Uri.parse("content://" + getPackageName()
                    + ".p1_07_missing/row"));
            providerAbsent = type == null;
            // This is a signature/owner probe only. The system media authority is expected to
            // remain a Host-system provider; its data is neither queried nor accepted. A
            // platform permission/argument rejection is a valid outcome after raw PMS resolves
            // the ProviderInfo and CAS permits the narrow system-owner route.
            try {
                getContentResolver().getType(Uri.parse("content://media/external/file"));
            } catch (SecurityException | IllegalArgumentException expected) {
                Log.i(TAG, "P1_07_MEDIA_OWNER_PROBE_REJECTED="
                        + expected.getClass().getSimpleName());
            }
            if (!serviceAbsent || !providerAbsent) {
                throw new AssertionError("P1_07_ABSENCE_CONTRACT service=" + serviceAbsent
                        + " provider=" + providerAbsent + " type=" + type);
            }
            Log.i(TAG, "P1_07_PMS_SIGNATURE_PASS serviceAbsent=" + serviceAbsent
                    + " providerAbsent=" + providerAbsent);
        } catch (Throwable error) {
            Log.e(TAG, "P1_07_PMS_SIGNATURE_FAIL serviceAbsent=" + serviceAbsent
                    + " providerAbsent=" + providerAbsent, error);
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException("P1_07_PMS_SIGNATURE_EXCEPTION", error);
        } finally {
            finish();
        }
    }
}
