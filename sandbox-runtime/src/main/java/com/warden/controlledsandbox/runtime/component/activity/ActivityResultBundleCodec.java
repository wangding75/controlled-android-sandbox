package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Bundle;
import com.warden.controlledsandbox.framework.activity.ResultIntentSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded compatibility codec at the Android Bundle lifecycle boundary. */
final class ActivityResultBundleCodec {
    private ActivityResultBundleCodec() { }

    static ResultIntentSnapshot decode(Bundle source) {
        Map<String, String> extras = new LinkedHashMap<>();
        for (String key : source.keySet()) {
            if (!key.startsWith(RuntimeKeys.RESULT_INTENT_EXTRA_PREFIX)) continue;
            if (extras.size() >= ResultIntentSnapshot.MAX_EXTRAS) {
                throw new IllegalArgumentException("too many Activity result Intent extras");
            }
            Object value = source.get(key);
            if (value != null) {
                extras.put(key.substring(RuntimeKeys.RESULT_INTENT_EXTRA_PREFIX.length()),
                        String.valueOf(value));
            }
        }
        return new ResultIntentSnapshot(
                source.getString(RuntimeKeys.RESULT_INTENT_ACTION, ""),
                source.getString(RuntimeKeys.RESULT_INTENT_DATA, ""),
                source.getString(RuntimeKeys.RESULT_INTENT_TYPE, ""),
                source.getString(RuntimeKeys.RESULT_INTENT_COMPONENT, ""),
                source.getInt(RuntimeKeys.RESULT_INTENT_FLAGS, 0),
                source.getString(RuntimeKeys.RESULT_INTENT_CLIP, ""),
                extras);
    }
}
