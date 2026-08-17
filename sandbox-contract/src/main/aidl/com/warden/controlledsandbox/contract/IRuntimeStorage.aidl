package com.warden.controlledsandbox.contract;
import android.os.Bundle;

/** Generation-scoped storage capability for isolated Guest processes. */
interface IRuntimeStorage {
    Bundle execute(String operation, String sessionId, long generation,
                   String packageName, int virtualUserId, String name,
                   boolean deviceProtected, in byte[] data);
    Bundle move(String sessionId, long generation, String packageName, int virtualUserId,
                String sourceName, boolean sourceDeviceProtected,
                String targetName, boolean targetDeviceProtected);
}
