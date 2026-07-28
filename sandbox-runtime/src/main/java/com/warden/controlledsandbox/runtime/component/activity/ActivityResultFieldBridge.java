package com.warden.controlledsandbox.runtime.component.activity;

import android.app.Activity;
import android.content.Intent;
import java.lang.reflect.Field;

/** Audited read-only bridge for Activity.setResult state held by the Guest instance. */
final class ActivityResultFieldBridge {
    record Captured(int resultCode, Intent data, boolean explicit) { }

    private ActivityResultFieldBridge() { }

    static Captured capture(Activity guest) {
        if (guest == null) return new Captured(ActivityTaskResultCodes.CANCELED, null, false);
        try {
            Field code = find(Activity.class, "mResultCode");
            Field data = find(Activity.class, "mResultData");
            code.setAccessible(true);
            data.setAccessible(true);
            int resultCode = code.getInt(guest);
            Intent intent = (Intent) data.get(guest);
            return new Captured(resultCode, intent,
                    resultCode != ActivityTaskResultCodes.CANCELED || intent != null);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return new Captured(ActivityTaskResultCodes.CANCELED, null, false);
        }
    }

    private static Field find(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class ActivityTaskResultCodes {
        private static final int CANCELED = 0;
        private ActivityTaskResultCodes() { }
    }
}
