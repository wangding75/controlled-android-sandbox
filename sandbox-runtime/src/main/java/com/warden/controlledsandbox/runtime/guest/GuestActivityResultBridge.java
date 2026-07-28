package com.warden.controlledsandbox.runtime.guest;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityResultIntentSnapshot;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityResultSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Production compatibility entry for legacy and registry-backed Activity results. */
public final class GuestActivityResultBridge {
    public interface RegistrationCallback { void complete(ActivityResultResult result); }
    public interface ResultDelivery { void deliver(ActivityResultSnapshot result); }

    private final Activity host;
    private final GuestRuntimeEnvironment.Session session;
    private String activityToken;
    private final int taskId;

    public GuestActivityResultBridge(
            Activity host,
            GuestRuntimeEnvironment.Session session,
            String activityToken,
            int taskId) {
        this.host = java.util.Objects.requireNonNull(host, "host");
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.activityToken = required(activityToken, "activityToken");
        if (taskId < 1) throw new IllegalArgumentException("taskId must be positive");
        this.taskId = taskId;
    }

    public synchronized void updateActivityToken(String currentToken) {
        activityToken = required(currentToken, "currentToken");
    }

    public void register(String key, RegistrationCallback callback) {
        call(ActivityResultRequest.REGISTER, key, 0, ActivityResultIntentSnapshot.empty(), callback);
    }

    public void unregister(String key, RegistrationCallback callback) {
        call(ActivityResultRequest.UNREGISTER, key, 0, ActivityResultIntentSnapshot.empty(), callback);
    }

    public void finish(int resultCode, Intent data, RegistrationCallback callback) {
        call(ActivityResultRequest.FINISH, "", resultCode, snapshot(data), callback);
    }

    public void drain(ResultDelivery delivery) {
        call(ActivityResultRequest.DRAIN, "", 0, ActivityResultIntentSnapshot.empty(), result -> {
            if (!result.successful()) return;
            for (ActivityResultSnapshot snapshot : result.results()) delivery.deliver(snapshot);
        });
    }

    public void launchForResult(
            Bundle launchRequest,
            String componentClass,
            String registryKey,
            String intentSenderToken,
            RouteBrokerClient.Callback callback) {
        register(registryKey, registration -> {
            if (!registration.successful()) return;
            Bundle request = launchRequest == null ? new Bundle() : new Bundle(launchRequest);
            request.putString(RuntimeKeys.SESSION_ID, session.spec.sessionId);
            request.putLong(RuntimeKeys.GENERATION, session.spec.generation);
            request.putString(RuntimeKeys.PACKAGE_NAME, session.spec.packageName);
            request.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.spec.virtualUserId);
            request.putString(RuntimeKeys.COMPONENT_CLASS, required(componentClass, "componentClass"));
            request.putInt(RuntimeKeys.CALLER_TASK_ID, taskId);
            request.putInt(RuntimeKeys.REQUEST_CODE, registration.assignedRequestCode());
            request.putString(RuntimeKeys.ACTIVITY_RESULT_KEY, required(registryKey, "registryKey"));
            request.putString(RuntimeKeys.INTENT_SENDER_TOKEN,
                    intentSenderToken == null ? "" : intentSenderToken.trim());
            RouteBrokerClient.launchActivity(host, request, callback);
        });
    }

    public static Intent toIntent(ActivityResultIntentSnapshot snapshot) {
        Intent intent = new Intent();
        if (snapshot == null) return intent;
        if (!snapshot.action().isEmpty()) intent.setAction(snapshot.action());
        if (!snapshot.dataUri().isEmpty() && !snapshot.mimeType().isEmpty()) {
            intent.setDataAndType(Uri.parse(snapshot.dataUri()), snapshot.mimeType());
        } else if (!snapshot.dataUri().isEmpty()) {
            intent.setData(Uri.parse(snapshot.dataUri()));
        } else if (!snapshot.mimeType().isEmpty()) {
            intent.setType(snapshot.mimeType());
        }
        if (!snapshot.componentName().isEmpty()) {
            String[] parts = snapshot.componentName().split("/", 2);
            if (parts.length == 2) intent.setComponent(new ComponentName(parts[0], parts[1]));
        }
        if (snapshot.flags() != 0) intent.addFlags(snapshot.flags());
        for (Map.Entry<String, String> entry : snapshot.extras().entrySet()) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        return intent;
    }

    public static ActivityResultIntentSnapshot snapshot(Intent intent) {
        if (intent == null) return ActivityResultIntentSnapshot.empty();
        ComponentName component = intent.getComponent();
        Map<String, String> extras = readExtras(intent);
        return ActivityResultIntentSnapshot.fromMap(
                value(intent.getAction()),
                intent.getData() == null ? "" : intent.getData().toString(),
                value(intent.getType()),
                component == null ? "" : component.getPackageName() + "/" + component.getClassName(),
                readInt(intent, "getFlags"),
                readClipDescription(intent),
                extras);
    }

    private void call(
            String operation,
            String registryKey,
            int resultCode,
            ActivityResultIntentSnapshot resultIntent,
            RegistrationCallback callback) {
        String token;
        synchronized (this) { token = activityToken; }
        ActivityResultRequest request = new ActivityResultRequest(
                RuntimeProtocol.CURRENT,
                UUID.randomUUID().toString(),
                session.spec.sessionId,
                session.spec.generation,
                session.spec.virtualUserId,
                session.spec.packageName,
                operation,
                token,
                registryKey,
                resultCode,
                resultIntent);
        RouteBrokerClient.activityResultOperation(host, request, callback::complete);
    }

    private static Map<String, String> readExtras(Intent intent) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        try {
            Method method = Intent.class.getMethod("getExtras");
            Object result = method.invoke(intent);
            if (!(result instanceof Bundle extras)) return Map.of();
            for (String key : extras.keySet()) {
                if (values.size() >= 64) break;
                Object value = extras.get(key);
                if (value != null) values.put(key, String.valueOf(value));
            }
        } catch (ReflectiveOperationException ignored) { }
        return Map.copyOf(values);
    }

    private static int readInt(Intent intent, String methodName) {
        try { return (Integer) Intent.class.getMethod(methodName).invoke(intent); }
        catch (ReflectiveOperationException ignored) { return 0; }
    }

    private static String readClipDescription(Intent intent) {
        try {
            Object clip = Intent.class.getMethod("getClipData").invoke(intent);
            if (clip == null) return "";
            Object description = clip.getClass().getMethod("getDescription").invoke(clip);
            return description == null ? "" : String.valueOf(description);
        } catch (ReflectiveOperationException ignored) { return ""; }
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String value(String value) { return value == null ? "" : value; }
}
