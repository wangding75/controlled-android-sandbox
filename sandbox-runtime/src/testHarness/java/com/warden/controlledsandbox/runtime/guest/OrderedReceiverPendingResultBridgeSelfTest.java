package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IOrderedReceiverCompletion;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.framework.core.FrameworkCallInterceptor;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Method;

public final class OrderedReceiverPendingResultBridgeSelfTest {
    private OrderedReceiverPendingResultBridgeSelfTest() { }

    public static void main(String[] args) throws Throwable {
        synchronousResultCapture();
        clearAbortCapture();
        asynchronousFinishInterception();
        localTimeoutConsumesLateFinish();
        System.out.println("PASS ordered Receiver PendingResult bridge self-test");
    }

    private static void synchronousResultCapture() throws Exception {
        FakeClock clock = new FakeClock(100);
        CapturingCompletion completion = new CapturingCompletion();
        OrderedReceiverFinishInterceptor interceptor = new OrderedReceiverFinishInterceptor(clock);
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    setResultCode(42);
                    setResultData("sync");
                    Bundle extras = new Bundle();
                    extras.putString("owner", "sync");
                    setResultExtras(extras);
                    abortBroadcast();
                }
            };
            OrderedReceiverPendingResultBridge bridge = OrderedReceiverPendingResultBridge.install(
                    receiver, request(completion, "sync-token", 1_000), interceptor);
            receiver.onReceive(new Context(), new Intent());
            Bundle result = bridge.afterOnReceive();
            require("BROADCAST_PENDING_RESULT_COMPLETED".equals(result.getString(RuntimeKeys.STATUS, "")),
                    "synchronous bridge status");
            require(completion.calls == 1 && completion.last.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0) == 42
                            && "sync".equals(completion.last.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""))
                            && completion.last.getBoolean(RuntimeKeys.BROADCAST_ABORT, false),
                    "synchronous result was not captured");
            require(interceptor.pendingCount() == 0, "synchronous bridge leaked finish token");
        } finally {
            interceptor.close();
        }
    }

    private static void clearAbortCapture() throws Exception {
        FakeClock clock = new FakeClock(100);
        CapturingCompletion completion = new CapturingCompletion();
        OrderedReceiverFinishInterceptor interceptor = new OrderedReceiverFinishInterceptor(clock);
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    abortBroadcast();
                    clearAbortBroadcast();
                }
            };
            OrderedReceiverPendingResultBridge bridge = OrderedReceiverPendingResultBridge.install(
                    receiver, request(completion, "clear-abort-token", 1_000), interceptor);
            receiver.onReceive(new Context(), new Intent());
            bridge.afterOnReceive();
            require(completion.calls == 1
                            && !completion.last.getBoolean(RuntimeKeys.BROADCAST_ABORT, true),
                    "clearAbortBroadcast final state was not captured");
        } finally {
            interceptor.close();
        }
    }

    private static void asynchronousFinishInterception() throws Throwable {
        FakeClock clock = new FakeClock(200);
        CapturingCompletion completion = new CapturingCompletion();
        OrderedReceiverFinishInterceptor interceptor = new OrderedReceiverFinishInterceptor(clock);
        try {
            final BroadcastReceiver.PendingResult[] async = new BroadcastReceiver.PendingResult[1];
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    async[0] = goAsync();
                }
            };
            OrderedReceiverPendingResultBridge bridge = OrderedReceiverPendingResultBridge.install(
                    receiver, request(completion, "async-token", 1_200), interceptor);
            receiver.onReceive(new Context(), new Intent());
            Bundle pending = bridge.afterOnReceive();
            require(pending.getBoolean(RuntimeKeys.BROADCAST_PENDING_ASYNC, false)
                            && interceptor.pendingCount() == 1 && completion.calls == 0,
                    "goAsync did not keep token pending");
            IBinder token = finishToken(async[0]);
            Bundle extras = new Bundle();
            extras.putString("owner", "async");
            Method finishReceiver = finishReceiverMethod();
            FrameworkCallInterceptor.Interception handled = interceptor.intercept(
                    "activity-manager", finishReceiver,
                    new Object[]{token, 77, "async", extras, true, 0});
            require(handled.handled() && completion.calls == 1
                            && completion.last.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0) == 77
                            && "async".equals(completion.last.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""))
                            && interceptor.pendingCount() == 0,
                    "async finishReceiver was not bridged");
            require(interceptor.intercept("activity-manager", finishReceiver,
                            new Object[]{token, 78, "replay", extras, false, 0}).handled()
                            && completion.calls == 1,
                    "async replay was not swallowed as a sandbox token");
        } finally {
            interceptor.close();
        }
    }

    private static void localTimeoutConsumesLateFinish() throws Throwable {
        FakeClock clock = new FakeClock(500);
        CapturingCompletion completion = new CapturingCompletion();
        OrderedReceiverFinishInterceptor interceptor = new OrderedReceiverFinishInterceptor(clock);
        try {
            final BroadcastReceiver.PendingResult[] async = new BroadcastReceiver.PendingResult[1];
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    async[0] = goAsync();
                }
            };
            OrderedReceiverPendingResultBridge bridge = OrderedReceiverPendingResultBridge.install(
                    receiver, request(completion, "timeout-token", 550), interceptor);
            receiver.onReceive(new Context(), new Intent());
            bridge.afterOnReceive();
            IBinder token = finishToken(async[0]);
            clock.now = 550;
            require(interceptor.purgeExpired() == 1 && interceptor.pendingCount() == 0,
                    "Guest timeout did not clean pending bridge");
            FrameworkCallInterceptor.Interception late = interceptor.intercept(
                    "activity-manager", finishReceiverMethod(),
                    new Object[]{token, 9, "late", new Bundle(), false, 0});
            require(late.handled() && completion.calls == 0,
                    "late custom finish token reached the host ActivityManager");
        } finally {
            interceptor.close();
        }
    }

    private static Bundle request(CapturingCompletion completion, String token, long deadlineMs) {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, token);
        request.putLong(RuntimeKeys.ORDERED_RECEIVER_DEADLINE_MS, deadlineMs);
        request.putBinder(RuntimeKeys.ORDERED_RECEIVER_COMPLETION_BINDER, completion.asBinder());
        request.putString(RuntimeKeys.PACKAGE_NAME, "com.example");
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, 2);
        request.putString(RuntimeKeys.SESSION_ID, "session");
        request.putLong(RuntimeKeys.GENERATION, 3);
        request.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.Receiver");
        request.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, 1);
        request.putString(RuntimeKeys.BROADCAST_RESULT_DATA, "initial");
        request.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS, new Bundle());
        return request;
    }

    private static IBinder finishToken(BroadcastReceiver.PendingResult pendingResult) throws Exception {
        Method tokenMethod = pendingResult.getClass().getMethod("tokenForTest");
        return (IBinder) tokenMethod.invoke(pendingResult);
    }

    private static Method finishReceiverMethod() throws Exception {
        return FakeActivityManager.class.getMethod("finishReceiver",
                IBinder.class, int.class, String.class, Bundle.class, boolean.class, int.class);
    }

    public interface FakeActivityManager {
        void finishReceiver(IBinder token, int resultCode, String resultData,
                            Bundle resultExtras, boolean abort, int flags);
    }

    private static final class CapturingCompletion extends IOrderedReceiverCompletion.Stub {
        int calls;
        Bundle last;
        @Override public Bundle complete(Bundle result) {
            calls++;
            last = new Bundle(result);
            Bundle ack = new Bundle();
            ack.putBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, true);
            ack.putString(RuntimeKeys.STATUS, "ORDERED_RECEIVER_COMPLETED");
            return ack;
        }
    }

    private static final class FakeClock implements Clock {
        long now;
        FakeClock(long now) { this.now = now; }
        @Override public long nowMillis() { return now; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
