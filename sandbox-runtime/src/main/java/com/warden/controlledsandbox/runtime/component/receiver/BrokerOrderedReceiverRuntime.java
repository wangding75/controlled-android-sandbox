package com.warden.controlledsandbox.runtime.component.receiver;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.domain.port.Clock;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android Bundle adapter around the pure-Java ordered Receiver token authority. */
public final class BrokerOrderedReceiverRuntime {
    private final OrderedReceiverTokenRegistry registry;

    public BrokerOrderedReceiverRuntime(Clock clock, TokenGenerator tokens) {
        registry = new OrderedReceiverTokenRegistry(clock, tokens);
    }

    public OrderedReceiverTokenRegistry.Lease issue(GuestSession target, String receiverClass,
                                                     long timeoutMs) {
        return registry.issue(target, receiverClass, timeoutMs);
    }

    public Bundle complete(Bundle result) {
        if (result == null) return response(false, "ORDERED_RECEIVER_RESULT_REQUIRED");
        String token = "";
        OrderedReceiverTokenRegistry.Identity identity = null;
        try {
            token = required(result, RuntimeKeys.ORDERED_RECEIVER_TOKEN);
            identity = new OrderedReceiverTokenRegistry.Identity(
                    required(result, RuntimeKeys.PACKAGE_NAME),
                    result.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1),
                    required(result, RuntimeKeys.SESSION_ID),
                    result.getLong(RuntimeKeys.GENERATION, -1),
                    required(result, RuntimeKeys.COMPONENT_CLASS));
            OrderedReceiverTokenRegistry.CompletionDecision decision = registry.complete(
                    token, identity, resultUpdate(result));
            return response(decision, token);
        } catch (Throwable error) {
            if (!token.isEmpty() && identity != null) {
                OrderedReceiverTokenRegistry.CompletionDecision rejected = registry.reject(
                        token, identity, "ORDERED_RECEIVER_RESULT_INVALID:" + error.getClass().getSimpleName());
                Bundle out = response(rejected, token);
                out.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getSimpleName());
                out.putString(RuntimeKeys.ERROR_MESSAGE, boundedMessage(error));
                return out;
            }
            return response(false, error.getClass().getSimpleName() + ":" + boundedMessage(error));
        }
    }

    private static Bundle response(OrderedReceiverTokenRegistry.CompletionDecision decision, String token) {
        Bundle out = response(decision.accepted(), decision.status());
        out.putString(RuntimeKeys.ORDERED_RECEIVER_TOKEN, token);
        if (decision.terminalState() != null) {
            out.putString(RuntimeKeys.ORDERED_RECEIVER_STATE, decision.terminalState().name());
        }
        return out;
    }

    private static String boundedMessage(Throwable error) {
        String message = String.valueOf(error.getMessage());
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    public OrderedReceiverTokenRegistry.AwaitResult await(OrderedReceiverTokenRegistry.Lease lease)
            throws InterruptedException {
        return registry.await(lease);
    }

    public boolean cancel(OrderedReceiverTokenRegistry.Lease lease, String reason) {
        return registry.cancel(lease, reason);
    }

    public int cancelSession(GuestSession session, String reason) {
        return registry.cancelSession(session, reason);
    }

    public int cancelInstance(String packageName, int userId, String reason) {
        return registry.cancelInstance(packageName, userId, reason);
    }

    public int cancelAll(String reason) { return registry.cancelAll(reason); }
    public int purgeExpired() { return registry.purgeExpired(); }
    public int pendingCount() { return registry.pendingCount(); }

    private static OrderedBroadcastState.ResultUpdate resultUpdate(Bundle result) {
        OrderedBroadcastState.ResultUpdate update = new OrderedBroadcastState.ResultUpdate();
        if (result.keySet().contains(RuntimeKeys.BROADCAST_RESULT_CODE)) {
            update.resultCode(result.getInt(RuntimeKeys.BROADCAST_RESULT_CODE, 0));
        }
        if (result.keySet().contains(RuntimeKeys.BROADCAST_RESULT_DATA)) {
            update.resultData(result.getString(RuntimeKeys.BROADCAST_RESULT_DATA, ""));
        }
        if (result.keySet().contains(RuntimeKeys.BROADCAST_RESULT_EXTRAS)) {
            update.resultExtras(stringMap(result.getBundle(RuntimeKeys.BROADCAST_RESULT_EXTRAS)));
        }
        if (result.getBoolean(RuntimeKeys.BROADCAST_ABORT, false)) update.abort();
        if (result.getBoolean(RuntimeKeys.BROADCAST_CLEAR_ABORT, false)) update.clearAbort();
        return update;
    }

    private static Map<String, String> stringMap(Bundle bundle) {
        if (bundle == null || bundle.keySet().isEmpty()) return java.util.Collections.emptyMap();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("BROADCAST_RESULT_EXTRAS_STRING_ONLY");
            }
            result.put(key, (String) value);
        }
        return result;
    }

    private static Bundle response(boolean accepted, String status) {
        Bundle out = new Bundle();
        out.putBoolean(RuntimeKeys.ORDERED_RECEIVER_ACCEPTED, accepted);
        out.putString(RuntimeKeys.STATUS, status == null ? "" : status);
        return out;
    }

    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }
}
