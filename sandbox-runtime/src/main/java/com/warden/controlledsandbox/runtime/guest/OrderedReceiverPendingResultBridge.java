package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.warden.controlledsandbox.contract.IOrderedReceiverCompletion;
import com.warden.controlledsandbox.contract.internal.DeathRegistrationHelper;
import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs a controlled platform PendingResult and reports its terminal state to the Broker. */
final class OrderedReceiverPendingResultBridge {
    private final BroadcastReceiver receiver;
    private final OrderedReceiverFinishInterceptor interceptor;
    private final IOrderedReceiverCompletion completion;
    private final IBinder finishToken = new OrderedReceiverFinishToken();
    private final DeathRegistrationHelper completionDeath;
    private final String receiverToken;
    private final String packageName;
    private final int virtualUserId;
    private final String sessionId;
    private final long generation;
    private final String receiverClass;
    private final long deadlineMs;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private Object pendingResult;

    static OrderedReceiverPendingResultBridge install(
            BroadcastReceiver receiver, Bundle request,
            OrderedReceiverFinishInterceptor interceptor) throws Exception {
        if (receiver == null || request == null || interceptor == null) {
            throw new IllegalArgumentException("receiver, request and interceptor are required");
        }
        IBinder callbackBinder = request.getBinder(RuntimeKeys.ORDERED_RECEIVER_COMPLETION_BINDER);
        IOrderedReceiverCompletion completion = IOrderedReceiverCompletion.Stub.asInterface(callbackBinder);
        if (completion == null || callbackBinder == null) {
            throw new IllegalStateException("ORDERED_RECEIVER_COMPLETION_MISSING");
        }
        OrderedReceiverPendingResultBridge bridge = new OrderedReceiverPendingResultBridge(
                receiver, request, interceptor, completion, callbackBinder);
        try {
            bridge.installPendingResult(request);
            interceptor.register(bridge.finishToken, bridge, bridge.deadlineMs);
            if (!bridge.linkCompletionDeathAfterReservation()) {
                throw new IllegalStateException("ORDERED_RECEIVER_COMPLETION_DEAD_DURING_LINK");
            }
            return bridge;
        } catch (Throwable error) {
            bridge.cancelLocal();
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException(error);
        }
    }

    private OrderedReceiverPendingResultBridge(
            BroadcastReceiver receiver, Bundle request,
            OrderedReceiverFinishInterceptor interceptor,
            IOrderedReceiverCompletion completion, IBinder completionBinder) {
        this.receiver = receiver;
        this.interceptor = interceptor;
        this.completion = completion;
        this.completionDeath = new DeathRegistrationHelper(
                completionBinder, this::completionBinderDied);
        this.receiverToken = required(request, RuntimeKeys.ORDERED_RECEIVER_TOKEN);
        this.packageName = required(request, RuntimeKeys.PACKAGE_NAME);
        this.virtualUserId = request.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        this.sessionId = required(request, RuntimeKeys.SESSION_ID);
        this.generation = request.getLong(RuntimeKeys.GENERATION, -1);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.receiverClass = required(request, RuntimeKeys.COMPONENT_CLASS);
        this.deadlineMs = request.getLong(RuntimeKeys.ORDERED_RECEIVER_DEADLINE_MS, -1L);
        if (deadlineMs < 1) throw new IllegalArgumentException("ordered Receiver deadline is required");
    }

    Bundle afterOnReceive() throws Exception {
        Object current = invokeReceiver("getPendingResult");
        if (current == null) {
            Bundle out = status("BROADCAST_PENDING_RESULT_ASYNC");
            out.putBoolean(RuntimeKeys.BROADCAST_PENDING_ASYNC, true);
            return out;
        }
        interceptor.unregister(finishToken, this);
        Bundle result = resultBundle(
                intMethod(current, "getResultCode"),
                stringMethod(current, "getResultData"),
                bundleMethod(current, "getResultExtras", false),
                booleanMethod(current, "getAbortBroadcast"));
        invokeReceiverWithPendingResult(null);
        Bundle acknowledgement = completeOnce(result);
        boolean accepted = acknowledgement.getBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, false);
        Bundle out = new Bundle();
        out.putAll(result);
        out.putAll(acknowledgement);
        out.putString(RuntimeKeys.STATUS, accepted
                ? "BROADCAST_PENDING_RESULT_COMPLETED" : "BROADCAST_PENDING_RESULT_REJECTED");
        out.putBoolean(RuntimeKeys.BROADCAST_PENDING_ASYNC, false);
        return out;
    }

    void completeFromFramework(Object[] arguments) throws Exception {
        int code = number(arguments, 1).intValue();
        String data = arguments[2] == null ? "" : String.valueOf(arguments[2]);
        Bundle extras = arguments[3] instanceof Bundle ? new Bundle((Bundle) arguments[3]) : new Bundle();
        boolean aborted = arguments[4] instanceof Boolean && (Boolean) arguments[4];
        completeOnce(resultBundle(code, data, extras, aborted));
    }

    void cancelLocal() {
        terminal.compareAndSet(false, true);
        interceptor.unregister(finishToken, this);
        unlinkCompletionDeath();
    }

    private Bundle completeOnce(Bundle result) throws Exception {
        if (!terminal.compareAndSet(false, true)) {
            Bundle duplicate = status("ORDERED_RECEIVER_LOCAL_REPLAY");
            duplicate.putBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, false);
            return duplicate;
        }
        interceptor.unregister(finishToken, this);
        unlinkCompletionDeath();
        Bundle acknowledgement = completion.complete(result);
        if (acknowledgement == null) {
            Bundle failed = status("ORDERED_RECEIVER_COMPLETION_NULL_ACK");
            failed.putBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, false);
            return failed;
        }
        return acknowledgement;
    }

    private boolean linkCompletionDeathAfterReservation() throws RemoteException {
        boolean linked = completionDeath.linkAfterReservation();
        if (!linked || !completionDeath.linkedAndAlive()) {
            interceptor.unregister(finishToken, this);
            return false;
        }
        return true;
    }

    private void unlinkCompletionDeath() {
        completionDeath.close();
    }

    private void completionBinderDied() {
        if (terminal.compareAndSet(false, true)) interceptor.unregister(finishToken, this);
    }

    private void installPendingResult(Bundle request) throws Exception {
        Class<?> pendingType = Class.forName("android.content.BroadcastReceiver$PendingResult");
        Bundle extras = request.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS);
        Object value = createPendingResult(pendingType,
                request.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0),
                request.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""),
                extras == null ? new Bundle() : new Bundle(extras));
        Method set = BroadcastReceiver.class.getMethod("setPendingResult", pendingType);
        set.invoke(receiver, value);
        pendingResult = value;
    }

    private Object createPendingResult(Class<?> pendingType, int code, String data, Bundle extras)
            throws Exception {
        validateResultPayload(data, extras);
        for (Constructor<?> constructor : pendingType.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            constructor.setAccessible(true);
            if (types.length == 9) {
                return constructor.newInstance(code, data, extras, 0, true, false,
                        finishToken, virtualUserId, 0);
            }
            if (types.length == 12) {
                return constructor.newInstance(code, data, extras, 0, true, false, false,
                        finishToken, virtualUserId, 0, -1, packageName);
            }
        }
        throw new IllegalStateException("ORDERED_PENDING_RESULT_CONSTRUCTOR_UNAVAILABLE");
    }

    private Bundle resultBundle(int code, String data, Bundle extras, boolean aborted) {
        validateResultPayload(data, extras);
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, receiverToken);
        result.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        result.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        result.putString(RuntimeKeys.SESSION_ID, sessionId);
        result.putLong(RuntimeKeys.GENERATION, generation);
        result.putString(RuntimeKeys.COMPONENT_CLASS, receiverClass);
        result.putInt(RuntimeKeys.BROADCAST_RESULT_CODE, code);
        result.putString(RuntimeKeys.BROADCAST_RESULT_DATA, data == null ? "" : data);
        result.putBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS,
                extras == null ? new Bundle() : new Bundle(extras));
        result.putBoolean(RuntimeKeys.BROADCAST_ABORT, aborted);
        return result;
    }

    private static void validateResultPayload(String data, Bundle extras) {
        String normalized = data == null ? "" : data;
        if (normalized.length() > OrderedBroadcastState.MAX_RESULT_DATA_CHARS) {
            throw new IllegalArgumentException("BROADCAST_RESULT_DATA_TOO_LARGE");
        }
        if (extras == null) return;
        if (extras.keySet().size() > OrderedBroadcastState.MAX_EXTRA_ENTRIES) {
            throw new IllegalArgumentException("BROADCAST_RESULT_EXTRAS_TOO_MANY");
        }
        for (String key : extras.keySet()) {
            if (key == null || key.length() > OrderedBroadcastState.MAX_EXTRA_KEY_CHARS) {
                throw new IllegalArgumentException("BROADCAST_RESULT_EXTRA_KEY_INVALID");
            }
            Object value = extras.get(key);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("BROADCAST_RESULT_EXTRAS_STRING_ONLY");
            }
            if (((String) value).length() > OrderedBroadcastState.MAX_EXTRA_VALUE_CHARS) {
                throw new IllegalArgumentException("BROADCAST_RESULT_EXTRA_VALUE_TOO_LARGE");
            }
        }
    }

    private Object invokeReceiver(String method) throws Exception {
        return BroadcastReceiver.class.getMethod(method).invoke(receiver);
    }

    private void invokeReceiverWithPendingResult(Object value) throws Exception {
        Method method = BroadcastReceiver.class.getMethod("setPendingResult", pendingResult.getClass());
        method.invoke(receiver, value);
    }

    private static int intMethod(Object target, String name) throws Exception {
        return ((Number) target.getClass().getMethod(name).invoke(target)).intValue();
    }

    private static String stringMethod(Object target, String name) throws Exception {
        Object value = target.getClass().getMethod(name).invoke(target);
        return value == null ? "" : String.valueOf(value);
    }

    private static Bundle bundleMethod(Object target, String name, boolean value) throws Exception {
        Object result = target.getClass().getMethod(name, boolean.class).invoke(target, value);
        return result instanceof Bundle ? new Bundle((Bundle) result) : new Bundle();
    }

    private static boolean booleanMethod(Object target, String name) throws Exception {
        Object value = target.getClass().getMethod(name).invoke(target);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Number number(Object[] arguments, int index) {
        if (index >= arguments.length || !(arguments[index] instanceof Number)) {
            throw new IllegalArgumentException("finishReceiver argument " + index + " must be numeric");
        }
        return (Number) arguments[index];
    }

    private Bundle status(String value) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, value);
        out.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, receiverToken);
        return out;
    }

    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }
}
