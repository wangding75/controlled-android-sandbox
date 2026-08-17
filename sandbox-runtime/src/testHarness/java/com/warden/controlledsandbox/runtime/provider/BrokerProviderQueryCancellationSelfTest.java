package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.contract.IProviderQueryCancellation;
import java.util.concurrent.atomic.AtomicLong;

/** Regression coverage for pre-return Provider cancellation and identity fencing. */
public final class BrokerProviderQueryCancellationSelfTest {
    public static void main(String[] args) throws Exception {
        BrokerProviderQueryCancellation runtime = new BrokerProviderQueryCancellation();
        BrokerProviderQueryCancellation.Handle handle = runtime.open(
                "query-1", "u0:caller", "caller-session", 3L,
                "u0:target", "target-session", 7L);
        RecordingEndpoint endpoint = new RecordingEndpoint();
        IProviderQueryCancellation.Stub.asInterface(handle.channelBinder()).attach(endpoint);
        require("PROVIDER_QUERY_CANCEL_REQUESTED".equals(runtime.cancel(
                "query-1", "caller-session", 3L, "target-session", 7L)),
                "in-flight query cancellation accepted");
        require(endpoint.cancelled, "target endpoint receives cancellation");
        require("PROVIDER_QUERY_CANCEL_ALREADY_REQUESTED".equals(runtime.cancel(
                "query-1", "caller-session", 3L, "target-session", 7L)),
                "duplicate cancellation is idempotent");
        runtime.close("query-1");
        require("PROVIDER_QUERY_ALREADY_TERMINAL".equals(runtime.cancel(
                "query-1", "caller-session", 3L, "target-session", 7L)),
                "late cancellation cannot resurrect a query");

        require("PROVIDER_QUERY_CANCEL_PENDING".equals(runtime.cancel(
                "query-2", "caller-session", 3L, "target-session", 7L)),
                "pre-registration cancellation is recorded");
        BrokerProviderQueryCancellation.Handle preCancelled = runtime.open(
                "query-2", "u0:caller", "caller-session", 3L,
                "u0:target", "target-session", 7L);
        RecordingEndpoint preEndpoint = new RecordingEndpoint();
        IProviderQueryCancellation.Stub.asInterface(preCancelled.channelBinder()).attach(preEndpoint);
        require(preEndpoint.cancelled, "pre-cancel reaches endpoint when it attaches");

        boolean fenced = false;
        try {
            runtime.cancel("query-2", "other-session", 3L, "target-session", 7L);
        } catch (SecurityException expected) {
            fenced = true;
        }
        require(fenced, "caller generation/session fence enforced");

        AtomicLong now = new AtomicLong(10_000L);
        BrokerProviderQueryCancellation expiring = new BrokerProviderQueryCancellation(now::get);
        expiring.open("query-expiring", "u0:caller", "caller-session", 3L,
                "u0:target", "target-session", 7L);
        now.addAndGet(120_001L);
        require(expiring.purgeExpired() == 1, "expired in-flight query is reported and removed");
        require(expiring.size() == 0, "expired query does not remain active");
        System.out.println("PASS Broker Provider query cancellation self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingEndpoint extends IProviderQueryCancellation.Stub {
        private boolean cancelled;
        @Override public void attach(IProviderQueryCancellation ignored) { }
        @Override public void cancel() { cancelled = true; }
        @Override public void detach() { }
    }
}
