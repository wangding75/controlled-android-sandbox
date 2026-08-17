package com.warden.controlledsandbox.fixture;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Test-only adversarial Native campaign. Not production runtime. */
public final class NativeAdversarialProbe {
    private static final String TAG = "CS_NATIVE_ADV";
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

    private NativeAdversarialProbe() { }

    public static String run(File filesDir, String context) {
        String executionContext = context == null || context.trim().isEmpty()
                ? "DIRECT_FIXTURE" : context.trim();
        if (!AVAILABLE) {
            return "{\"schema\":\"t57-r03-p0a-01-native-adv\",\"status\":\"ERROR\","
                    + "\"detail\":\"JNI_UNAVAILABLE:" + ERROR.replace("\"", "'") + "\"}";
        }
        File directory = filesDir == null ? new File("/data/local/tmp") : filesDir;
        String result = nativeRunCampaign(directory.getAbsolutePath(), executionContext);
        persist(directory, result);
        Log.i(TAG, "RESULT_BEGIN");
        Log.i(TAG, result);
        Log.i(TAG, "RESULT_END");
        return result;
    }

    private static void persist(File directory, String result) {
        try (FileOutputStream output = new FileOutputStream(
                new File(directory, "native-adv-results.json"))) {
            output.write(result.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "cannot persist native adv results", error);
        }
    }

    private static native String nativeRunCampaign(String filesDir, String context);
}
