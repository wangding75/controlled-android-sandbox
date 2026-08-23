package com.warden.controlledsandbox.fixture;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Test-only C3-T02 native filesystem/proc/network/FD campaign. */
public final class C3T02FileProcNetworkFdProbe {
    private static final String TAG = "CS_C3_T02";
    private static final boolean AVAILABLE;
    private static final String ERROR;

    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary("controlled_sandbox_fixture");
            available = true;
        } catch (Throwable failure) {
            error = failure.getClass().getName() + ":" + String.valueOf(failure.getMessage());
        }
        AVAILABLE = available;
        ERROR = error;
    }

    private C3T02FileProcNetworkFdProbe() { }

    public static String run(File filesDir, String context) {
        String executionContext = context == null || context.trim().isEmpty()
                ? "DIRECT_FIXTURE" : context.trim();
        File directory = filesDir == null ? new File("/data/local/tmp") : filesDir;
        String result;
        if (!AVAILABLE) {
            result = "{\"schema\":\"cas-c3-t02-file-proc-network-fd\","
                    + "\"status\":\"ERROR\",\"detail\":\"JNI_UNAVAILABLE:"
                    + ERROR.replace("\"", "'") + "\"}";
        } else {
            result = nativeRunCampaign(directory.getAbsolutePath(), executionContext);
        }
        persist(directory, result);
        Log.i(TAG, "RESULT_BEGIN");
        Log.i(TAG, result);
        Log.i(TAG, "RESULT_END");
        return result;
    }

    private static void persist(File directory, String result) {
        try (FileOutputStream output = new FileOutputStream(
                new File(directory, "c3t02-results.json"))) {
            output.write(result.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "cannot persist C3-T02 results", error);
        }
    }

    private static native String nativeRunCampaign(String filesDir, String context);
}
