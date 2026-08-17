package com.warden.controlledsandbox;

import android.app.job.JobParameters;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import com.warden.controlledsandbox.contract.VirtualJobParametersSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobWorkItemSnapshot;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
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

    /** API 31+ exposes the same successful terminal transition through a hidden internal code. */
    static boolean isSuccessfulFinish(JobParameters value) {
        if (value == null) return false;
        int internal = internalStopReason(value);
        String debug = debugStopReason(value);
        // JobParameters.INTERNAL_STOP_REASON_SUCCESSFUL_FINISH is hidden but stable since API 31.
        return internal == 10 || "last work dequeued".equalsIgnoreCase(debug.trim());
    }

    /**
     * Projects a host-dequeued JobWorkItem into a bounded, framework-neutral payload.
     * The host JobWorkItem itself never crosses the Package/Guest boundary.
     */
    static VirtualJobWorkItemSnapshot workItem(Object value) {
        return workItem(value, workId(value));
    }

    /** Uses a Broker-owned projection ID when the platform hides JobWorkItem.mWorkId. */
    static VirtualJobWorkItemSnapshot workItem(Object value, int projectedWorkId) {
        if (value == null) return null;
        Object intent = invoke(value, "getIntent");
        if (!(intent instanceof Parcelable)) {
            throw new IllegalArgumentException("JOB_WORK_ITEM_INTENT_UNAVAILABLE");
        }
        int workId = projectedWorkId >= 0 ? projectedWorkId : workId(value);
        if (workId < 0) throw new IllegalArgumentException("JOB_WORK_ITEM_ID_UNAVAILABLE");
        return new VirtualJobWorkItemSnapshot(workId,
                integer(value, 0, "getDeliveryCount"), marshal(intent),
                marshal(invoke(value, "getExtras")),
                longValue(value, -1L, "getEstimatedNetworkDownloadBytes"),
                longValue(value, -1L, "getEstimatedNetworkUploadBytes"),
                longValue(value, -1L, "getMinimumNetworkChunkBytes"));
    }

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
    private static long longValue(Object target, long fallback, String... names) {
        Object value = invoke(target, names); return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
    private static int workId(Object target) {
        Object value = invoke(target, "getWorkId");
        if (value instanceof Number) return ((Number) value).intValue();
        for (String name : new String[]{"mWorkId", "workId"}) {
            for (Class<?> cursor = target.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    Object result = field.get(target);
                    if (result instanceof Number) return ((Number) result).intValue();
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
                }
            }
        }
        return -1;
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
