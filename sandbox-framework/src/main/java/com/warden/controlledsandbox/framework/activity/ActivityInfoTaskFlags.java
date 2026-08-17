package com.warden.controlledsandbox.framework.activity;

/**
 * Task-reset bits from {@code android.content.pm.ActivityInfo}.
 *
 * <p>The framework module deliberately does not depend on the device's hidden Android SDK
 * surface. These values are stable platform contract bits and are kept in one place so the
 * broker ledger and runtime manifest projection use the same semantics.</p>
 */
public final class ActivityInfoTaskFlags {
    /** Activity is finished when its task is reset. */
    public static final int FINISH_ON_TASK_LAUNCH = 0x00000002;
    /** Activities above the task root are cleared when the task is reset. */
    public static final int CLEAR_TASK_ON_LAUNCH = 0x00000004;
    /** The task root opts out of ordinary task-reset pruning. */
    public static final int ALWAYS_RETAIN_TASK_STATE = 0x00000008;
    /** Activity may move to a task with a matching affinity during reset. */
    public static final int ALLOW_TASK_REPARENTING = 0x00000040;

    public static boolean has(int flags, int flag) {
        return (flags & flag) == flag;
    }

    private ActivityInfoTaskFlags() { }
}
