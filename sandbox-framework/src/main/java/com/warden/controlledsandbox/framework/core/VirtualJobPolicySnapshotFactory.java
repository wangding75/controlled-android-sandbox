package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Extracts bounded JobInfo scheduling semantics without exposing host identity. */
final class VirtualJobPolicySnapshotFactory {
    private static final long DEFAULT_BACKOFF_MS = 30_000L;
    private static final long MAX_DELAY_MS = 365L * 24L * 60L * 60L * 1000L;

    private VirtualJobPolicySnapshotFactory() { }

    static VirtualSystemServiceAuthority.JobRecord from(Object job, int guestId, GuestIdentity identity) {
        long now = System.currentTimeMillis();
        int network = boundedInt(intValue(job, 0, "getNetworkType", "networkType", "mNetworkType"), 0, 5);
        if (network == 0 && objectValue(job, "getRequiredNetwork", "requiredNetwork", "mRequiredNetwork") != null) {
            network = 1;
        }
        boolean periodic = boolValue(job, false, "isPeriodic", "periodic", "mIsPeriodic");
        long interval = boundedLong(longValue(job, 0L, "getIntervalMillis", "intervalMillis", "mIntervalMillis"));
        long flex = boundedLong(longValue(job, 0L, "getFlexMillis", "flexMillis", "mFlexMillis"));
        if (interval > 0L) periodic = true;
        if (!periodic) { interval = 0L; flex = 0L; }
        else {
            if (interval == 0L) interval = 15L * 60L * 1000L;
            if (flex > interval) flex = interval;
        }
        long latency = boundedLong(longValue(job, 0L, "getMinLatencyMillis", "minimumLatencyMillis", "mMinLatencyMillis"));
        long deadline = boundedLong(longValue(job, 0L, "getMaxExecutionDelayMillis", "overrideDeadlineMillis", "mMaxExecutionDelayMillis"));
        int backoff = boundedInt(intValue(job, 1, "getBackoffPolicy", "backoffPolicy", "mBackoffPolicy"), 0, 1);
        long initialBackoff = boundedLong(longValue(job, DEFAULT_BACKOFF_MS,
                "getInitialBackoffMillis", "initialBackoffMillis", "mInitialBackoffMillis"));
        if (initialBackoff == 0L) initialBackoff = DEFAULT_BACKOFF_MS;
        return new VirtualSystemServiceAuthority.JobRecord(guestId, 0, "RESERVED",
                identity.processName(), identity.generation(), identity.packageRevision(), network,
                boolValue(job, false, "isRequireCharging", "requiresCharging", "mRequiresCharging"),
                boolValue(job, false, "isRequireBatteryNotLow", "requiresBatteryNotLow", "mRequiresBatteryNotLow"),
                boolValue(job, false, "isRequireStorageNotLow", "requiresStorageNotLow", "mRequiresStorageNotLow"),
                boolValue(job, false, "isRequireDeviceIdle", "requiresDeviceIdle", "mRequiresDeviceIdle"),
                periodic, interval, flex, latency, deadline,
                boolValue(job, false, "isExpedited", "expedited", "mExpedited"),
                boolValue(job, false, "isPersisted", "persisted", "mIsPersisted"),
                backoff, initialBackoff, 0, safeAdd(now, latency), 0L, job, now);
    }

    private static boolean boolValue(Object target, boolean fallback, String... names) {
        Object value = objectValue(target, names);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
    private static int intValue(Object target, int fallback, String... names) {
        Object value = objectValue(target, names);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
    private static long longValue(Object target, long fallback, String... names) {
        Object value = objectValue(target, names);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
    private static Object objectValue(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Method method = findMethod(target.getClass(), name);
                if (method != null) { method.setAccessible(true); return method.invoke(target); }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            try {
                Field field = findField(target.getClass(), name);
                if (field != null) { field.setAccessible(true); return field.get(target); }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return null;
    }
    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredMethod(name); }
            catch (NoSuchMethodException ignored) { }
        }
        return null;
    }
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
    private static int boundedInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static long boundedLong(long value) { return Math.max(0L, Math.min(MAX_DELAY_MS, value)); }
    private static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
