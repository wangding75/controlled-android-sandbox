package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Method;
import java.util.UUID;

/** Converts a durable virtual sender delivery back into Broker component/result routes. */
final class GuestPendingIntentDispatcher implements PendingIntentFrameworkInterceptor.Dispatcher {
    private final Context context;
    private final GuestPackageSpec spec;

    GuestPendingIntentDispatcher(Context context, GuestPackageSpec spec) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
    }

    @Override public int dispatch(VirtualPendingIntentRegistry.Record record,
            VirtualPendingIntentRegistry.SendRequest sendRequest) {
        Intent intent = selectedIntent(record.payload(), sendRequest);
        if (record.spec().kind() == VirtualPendingIntentRegistry.Kind.ACTIVITY_RESULT) {
            return dispatchActivityResult(record, intent);
        }
        Bundle request = spec.toBundle();
        applyIntent(request, intent);
        request.putString("pendingIntentSenderId", record.persistentTokenId());
        request.putInt("pendingIntentRequestCode", record.spec().requestCode());
        request.putString("pendingIntentKind", record.spec().kind().name());
        request.putString("pendingIntentCreatorPackage", record.packageName());
        request.putInt("pendingIntentCreatorUid", record.creatorUid());
        RouteBrokerClient.Callback callback = result -> {
            Bundle event = new Bundle(result);
            event.putString("pendingIntentSenderId", record.persistentTokenId());
            event.putString("pendingIntentKind", record.spec().kind().name());
            RuntimeEventLog.event("VIRTUAL_PENDING_INTENT_DELIVERY", event);
        };
        switch (record.spec().kind()) {
            case ACTIVITY -> RouteBrokerClient.launchActivity(context, request, callback);
            case BROADCAST -> {
                request.putString(ComponentOperations.OPERATION,
                        intent.getComponent() == null
                                ? ComponentOperations.SEND_IMPLICIT_BROADCAST
                                : ComponentOperations.SEND_BROADCAST);
                RouteBrokerClient.invokeComponent(context, request, callback);
            }
            case SERVICE, FOREGROUND_SERVICE -> {
                request.putString(ComponentOperations.OPERATION, ComponentOperations.START_SERVICE);
                if (record.spec().kind() == VirtualPendingIntentRegistry.Kind.FOREGROUND_SERVICE) {
                    request.putBoolean("foregroundService", true);
                }
                RouteBrokerClient.invokeComponent(context, request, callback);
            }
            case ACTIVITY_RESULT -> throw new AssertionError("handled above");
        }
        return 0;
    }

    private int dispatchActivityResult(VirtualPendingIntentRegistry.Record record, Intent intent) {
        String targetActivityToken = record.spec().component();
        if (targetActivityToken.isEmpty()) {
            throw new SecurityException("VIRTUAL_PENDING_INTENT_ACTIVITY_RESULT_TARGET_REQUIRED");
        }
        ActivityResultRequest request = new ActivityResultRequest(
                RuntimeProtocol.CURRENT,
                record.persistentTokenId() + ":" + UUID.randomUUID(),
                spec.sessionId,
                spec.generation,
                spec.virtualUserId,
                spec.packageName,
                ActivityResultRequest.SEND,
                targetActivityToken,
                "",
                record.spec().requestCode(),
                0,
                GuestActivityResultBridge.snapshot(intent));
        RouteBrokerClient.activityResultOperation(context, request, result -> {
            Bundle event = new Bundle();
            event.putString("pendingIntentSenderId", record.persistentTokenId());
            event.putString("pendingIntentKind", record.spec().kind().name());
            event.putBoolean("activityResultAccepted", result.successful());
            RuntimeEventLog.event("VIRTUAL_PENDING_INTENT_ACTIVITY_RESULT", event);
        });
        return 0;
    }

    static Intent selectedIntent(Object payload,
            VirtualPendingIntentRegistry.SendRequest request) {
        Intent base = null;
        if (payload instanceof Intent[] intents && intents.length > 0) base = intents[intents.length - 1];
        else if (payload instanceof Intent) base = (Intent) payload;
        Intent result = base == null ? new Intent() : new Intent(base);
        Object fillPayload = request == null ? null : request.fillInPayload();
        if (fillPayload instanceof Intent fillIn) mergeFillIn(result, fillIn);
        applyFlags(result, request == null ? 0 : request.flagsMask(),
                request == null ? 0 : request.flagsValues());
        return result;
    }

    private static void mergeFillIn(Intent base, Intent fillIn) {
        try {
            Method fill = Intent.class.getMethod("fillIn", Intent.class, int.class);
            fill.invoke(base, fillIn, 0);
            return;
        } catch (ReflectiveOperationException ignored) { }
        if (base.getAction() == null || base.getAction().isEmpty()) base.setAction(fillIn.getAction());
        if (base.getComponent() == null && fillIn.getComponent() != null) base.setComponent(fillIn.getComponent());
        if (base.getData() == null && fillIn.getData() != null) base.setData(fillIn.getData());
        copyExtras(fillIn, base);
    }

    private static void applyFlags(Intent intent, int mask, int values) {
        if (mask == 0 && values == 0) return;
        try {
            int current = (Integer) Intent.class.getMethod("getFlags").invoke(intent);
            int merged = (current & ~mask) | (values & mask);
            Intent.class.getMethod("setFlags", int.class).invoke(intent, merged);
        } catch (ReflectiveOperationException ignored) {
            intent.addFlags(values & mask);
        }
    }

    private void applyIntent(Bundle target, Intent intent) {
        ComponentName component = intent.getComponent();
        if (component != null) {
            String targetPackage = component.getPackageName();
            if (targetPackage != null && !targetPackage.isEmpty() && !spec.packageName.equals(targetPackage)) {
                throw new SecurityException("VIRTUAL_PENDING_INTENT_CROSS_PACKAGE_DENIED");
            }
            target.putString(RuntimeKeys.COMPONENT_CLASS, component.getClassName());
        }
        String action = intent.getAction();
        if (action != null && !action.trim().isEmpty()) target.putString(ComponentOperations.ACTION, action);
        if (intent.getData() != null) target.putString(RuntimeKeys.URI, intent.getData().toString());
        String clip = clipDescription(intent);
        if (!clip.isEmpty()) target.putString("pendingIntentClipDescription", clip);
        copyExtras(intent, target);
    }

    private static void copyExtras(Intent intent, Bundle target) {
        try {
            Method method = Intent.class.getMethod("getExtras");
            Object value = method.invoke(intent);
            if (value instanceof Bundle) target.putAll((Bundle) value);
        } catch (Throwable ignored) { }
    }

    private static void copyExtras(Intent source, Intent target) {
        try {
            Method method = Intent.class.getMethod("getExtras");
            Object value = method.invoke(source);
            if (value instanceof Bundle) Intent.class.getMethod("putExtras", Bundle.class).invoke(target, value);
        } catch (Throwable ignored) { }
    }

    private static String clipDescription(Intent intent) {
        try {
            Object clip = Intent.class.getMethod("getClipData").invoke(intent);
            if (clip == null) return "";
            Object description = clip.getClass().getMethod("getDescription").invoke(clip);
            return description == null ? "" : String.valueOf(description);
        } catch (ReflectiveOperationException ignored) { return ""; }
    }
}
