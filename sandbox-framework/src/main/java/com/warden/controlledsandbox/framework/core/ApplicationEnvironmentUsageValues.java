package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Usage-event parsing and summary projection separated from service dispatch. */
final class ApplicationEnvironmentUsageValues {
    private ApplicationEnvironmentUsageValues() { }

    static Object usageSummary(Class<?> returnType, List<VirtualUsageEventSnapshot> events) {
        Map<String, long[]> summary = new LinkedHashMap<>();
        for (VirtualUsageEventSnapshot event : events) {
            long[] values = summary.computeIfAbsent(
                    event.packageName(), ignored -> new long[]{0L, 0L, 0L});
            values[0] = values[0] == 0L
                    ? event.timestampMs() : Math.min(values[0], event.timestampMs());
            values[1] = Math.max(values[1], event.timestampMs());
            values[2]++;
        }
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, long[]> item : summary.entrySet()) {
            values.add(new UsageSummary(
                    item.getKey(), item.getValue()[0], item.getValue()[1], item.getValue()[2]));
        }
        return FrameworkApplicationEnvironmentObjectFactory.collectionResult(
                returnType, values, (type, value) -> value);
    }

    static VirtualUsageEventSnapshot usageEvent(Object[] arguments, GuestIdentity identity) {
        long timestamp = System.currentTimeMillis();
        int type = 0;
        String packageName = identity.packageName();
        String className = "";
        String shortcutId = "";
        if (arguments != null) {
            for (Object value : arguments) {
                if (value instanceof Long number && number >= 0L) {
                    timestamp = number;
                } else if (value instanceof Integer number && number >= 0) {
                    type = number;
                } else if (value instanceof String text) {
                    if (text.equals(identity.packageName())
                            || text.equals(identity.hostPackageName())) {
                        packageName = identity.packageName();
                    } else if (text.contains(".") && className.isEmpty()) {
                        className = text;
                    } else if (shortcutId.isEmpty()) {
                        shortcutId = text;
                    }
                }
            }
        }
        return new VirtualUsageEventSnapshot(
                timestamp, type, packageName, className, identity.packageName(), "", shortcutId, 0);
    }

    record UsageSummary(
            String packageName, long firstTimeStamp, long lastTimeStamp, long eventCount) { }
}
