package com.warden.controlledsandbox.framework.identity;

import android.os.IBinder;

/**
 * Runtime-owned transport for ContentResolver observer registrations.
 *
 * <p>The Framework module owns the Android callback shape, while the Runtime module owns
 * session/generation identity and the Broker transaction. Keeping this small boundary here
 * avoids making Framework depend on Runtime protocol implementation classes.</p>
 */
public interface ContentObserverBridge {
    Registration register(String targetPackage, int targetVirtualUserId, String targetProcessName,
                          String authority, String componentClass, String uri,
                          boolean notifyForDescendants, boolean deliverSelfNotifications,
                          IBinder callback);

    void unregister(String registrationId);

    void notifyChange(String targetPackage, int targetVirtualUserId, String targetProcessName,
                      String authority, String componentClass, String uri, int flags);

    /** Broker identity returned to the Framework-side registration ledger. */
    final class Registration {
        private final String id;

        public Registration(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("observer registration id is required");
            }
            this.id = id.trim();
        }

        public String id() { return id; }
    }
}
