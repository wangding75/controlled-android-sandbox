package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Bundle;
import com.warden.controlledsandbox.framework.activity.ResultIntentSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded compatibility codec at the Android Bundle lifecycle boundary. */
public final class ActivityResultBundleCodec {
    private ActivityResultBundleCodec() { }

    public static ResultIntentSnapshot decode(Bundle source) {
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

    /** Encodes the real Activity result fields for the Broker lifecycle transaction. */
    public static Bundle encode(int resultCode, android.content.Intent intent) {
        com.warden.controlledsandbox.contract.ActivityResultIntentSnapshot snapshot =
                com.warden.controlledsandbox.runtime.guest.GuestActivityResultBridge.snapshot(intent);
        Bundle out = new Bundle();
        out.putInt(RuntimeKeys.RESULT_CODE, resultCode);
        out.putString(RuntimeKeys.RESULT_INTENT_ACTION, snapshot.action());
        out.putString(RuntimeKeys.RESULT_INTENT_DATA, snapshot.dataUri());
        out.putString(RuntimeKeys.RESULT_INTENT_TYPE, snapshot.mimeType());
        out.putString(RuntimeKeys.RESULT_INTENT_COMPONENT, snapshot.componentName());
        out.putInt(RuntimeKeys.RESULT_INTENT_FLAGS, snapshot.flags());
        out.putString(RuntimeKeys.RESULT_INTENT_CLIP, snapshot.clipDescription());
        for (Map.Entry<String, String> entry : snapshot.extras().entrySet()) {
            out.putString(RuntimeKeys.RESULT_INTENT_EXTRA_PREFIX + entry.getKey(), entry.getValue());
        }
        return out;
    }
}
