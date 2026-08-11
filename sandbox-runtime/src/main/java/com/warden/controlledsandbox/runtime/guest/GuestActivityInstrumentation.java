package com.warden.controlledsandbox.runtime.guest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/** Routes Activity.startActivity calls through the virtual component broker. */
public final class GuestActivityInstrumentation extends Instrumentation {
    private final GuestContext context;
    private final int callerTaskId;

    public GuestActivityInstrumentation(GuestContext context, int callerTaskId) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        if (callerTaskId < 1) throw new IllegalArgumentException("callerTaskId must be positive");
        this.callerTaskId = callerTaskId;
    }

    // Hidden in the compile SDK stubs; the runtime framework dispatches this exact signature.
    public ActivityResult execStartActivity(Context who, IBinder contextThread,
            IBinder token, Activity target, Intent intent, int requestCode, Bundle options) {
        context.startActivityFromActivity(intent, options, callerTaskId);
        return null;
    }
}
