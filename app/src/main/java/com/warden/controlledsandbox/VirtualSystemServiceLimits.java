package com.warden.controlledsandbox;

import java.util.concurrent.TimeUnit;

/** Centralized transaction, collection, and scheduling bounds for virtual system services. */
final class VirtualSystemServiceLimits {
    static final int SCHEMA = 6;
    static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    static final int MAX_ACCOUNTS_PER_SCOPE = 64;
    static final int MAX_TOKENS_PER_ACCOUNT = 32;
    static final int MAX_PENDING_INTENTS_PER_SCOPE = 512;
    static final int MAX_ALARMS_PER_SCOPE = 256;
    static final int MAX_NAMESPACE_MAPPINGS = 4096;
    static final int MAX_NOTIFICATIONS_PER_SCOPE = 1024;
    static final int MAX_NOTIFICATION_CHANNELS_PER_SCOPE = 512;
    static final int MAX_JOBS_PER_SCOPE = 512;
    static final int MAX_KEY_CHARS = 512;
    static final int MAX_SECRET_CHARS = 16 * 1024;
    static final int MAX_SCOPES = 256;
    static final int MAX_NAMESPACES_PER_SCOPE = 32;
    static final long RETRY_WITHOUT_CLIENT_MS = 30_000L;
    static final long JOB_EXECUTION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

    private VirtualSystemServiceLimits() { }
}
