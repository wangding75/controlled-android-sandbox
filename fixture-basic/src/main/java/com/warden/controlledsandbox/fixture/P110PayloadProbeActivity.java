package com.warden.controlledsandbox.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.util.zip.CRC32;

/** Verifies a deterministic large Intent payload after the CAS Activity route is consumed. */
public final class P110PayloadProbeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        byte[] payload = extras == null ? null : extras.getByteArray("p110.payload");
        int expectedBytes = extras == null ? -1 : extras.getInt("p110.payloadBytes", -1);
        long expectedChecksum = extras == null ? -1L : extras.getLong("p110.checksum", -1L);
        CRC32 checksum = new CRC32();
        if (payload != null) checksum.update(payload);
        boolean valid = payload != null && payload.length == expectedBytes
                && checksum.getValue() == expectedChecksum;
        Log.i("CS_FIXTURE", valid
                ? "P1_10_PAYLOAD_PASS bytes=" + payload.length + " checksum=" + checksum.getValue()
                : "P1_10_PAYLOAD_FAIL actual=" + (payload == null ? -1 : payload.length)
                + " expected=" + expectedBytes + " checksum=" + checksum.getValue()
                + " expectedChecksum=" + expectedChecksum);
        finish();
    }
}
