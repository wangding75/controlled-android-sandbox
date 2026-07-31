package com.warden.controlledsandbox;

import android.app.Instrumentation;
import android.os.Bundle;

/** Device-stage smoke entry. It is packaged only by the locked Android build and is not Host evidence. */
public final class SourceBaselineInstrumentation extends Instrumentation {
    private static final int RESULT_OK = -1;
    private static final int RESULT_FAILED = 0;

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String packageName = getTargetContext().getPackageName();
            if (packageName == null || !packageName.startsWith("com.warden.controlledsandbox")) {
                throw new IllegalStateException("UNEXPECTED_TARGET_PACKAGE:" + packageName);
            }
            if (getTargetContext().getFilesDir() == null) {
                throw new IllegalStateException("TARGET_FILES_DIR_UNAVAILABLE");
            }
            result.putString("status", "SOURCE_BASELINE_INSTRUMENTATION_READY");
            result.putString("targetPackage", packageName);
            finish(RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("status", "SOURCE_BASELINE_INSTRUMENTATION_FAILED");
            result.putString("error", error.getClass().getName() + ":" + error.getMessage());
            finish(RESULT_FAILED, result);
        }
    }
}
