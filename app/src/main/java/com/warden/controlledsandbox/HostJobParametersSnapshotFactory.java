package com.warden.controlledsandbox;

import android.app.job.JobParameters;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Extracts a bounded public-data snapshot without forwarding the host JobScheduler callback. */
final class HostJobParametersSnapshotFactory {
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private HostJobParametersSnapshotFactory() { }

    static VirtualJobParametersSnapshot from(JobParameters value) {
        if (value == null) throw new IllegalArgumentException("JobParameters required");
        return new VirtualJobParametersSnapshot(value.getJobId(), -1,
                string(value, "getJobNamespace", "getNamespace"),
                marshal(invoke(value, "getExtras")), marshal(invoke(value, "getTransientExtras")),
                marshal(invoke(value, "getClipData")), integer(value, 0, "getClipGrantFlags"),
                bool(value, "isOverrideDeadlineExpired"), bool(value, "isExpeditedJob", "isExpedited"),
                bool(value, "isUserInitiatedJob"), uris(invoke(value, "getTriggeredContentUris")),
                strings(invoke(value, "getTriggeredContentAuthorities")), marshal(invoke(value, "getNetwork")),
                integer(value, 0, "getStopReason"), integer(value, -1, "getInternalStopReason"),
                string(value, "getDebugStopReason"), 0L);
    }

    static int stopReason(JobParameters value) { return integer(value, 0, "getStopReason"); }
    static int internalStopReason(JobParameters value) {
        return integer(value, -1, "getInternalStopReason");
    }
    static String debugStopReason(JobParameters value) { return string(value, "getDebugStopReason"); }

    private static byte[] marshal(Object value) {
        if (value == null) return new byte[0];
        if (!(value instanceof Parcelable parcelable)) return new byte[0];
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(parcelable, 0);
            byte[] payload = parcel.marshall();
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("JobParameters payload too large");
            }
            return payload;
        } finally { parcel.recycle(); }
    }
    private static Object invoke(Object target, String... names) {
        for (String name : names) {
            try { Method method = target.getClass().getMethod(name); return method.invoke(target); }
            catch (Throwable ignored) { }
        }
        return null;
    }
    private static String string(Object target, String... names) {
        Object value = invoke(target, names); return value == null ? "" : String.valueOf(value);
    }
    private static boolean bool(Object target, String... names) {
        Object value = invoke(target, names); return value instanceof Boolean && (Boolean) value;
    }
    private static int integer(Object target, int fallback, String... names) {
        Object value = invoke(target, names); return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
    private static List<String> uris(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Uri[] values) for (Uri item : values) if (item != null) out.add(item.toString());
        return out;
    }
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof String[] values) for (String item : values) if (item != null) out.add(item);
        return out;
    }
}
