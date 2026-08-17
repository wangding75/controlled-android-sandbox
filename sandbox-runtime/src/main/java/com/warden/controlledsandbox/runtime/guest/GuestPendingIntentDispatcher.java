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
    private volatile GuestIntentResolver resolver;

    GuestPendingIntentDispatcher(Context context, GuestPackageSpec spec) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
    }

    @Override public int dispatch(VirtualPendingIntentRegistry.Record record,
            VirtualPendingIntentRegistry.SendRequest sendRequest) {
        Intent intent = selectedIntent(record.payload(), sendRequest);
        if (record.spec().kind() == VirtualPendingIntentRegistry.Kind.ACTIVITY_RESULT) {
            return dispatchActivityResult(record, intent, sendRequest);
        }
        Bundle request = spec.toBundle();
        applyIntent(request, intent, record.spec().kind());
        request.putString("pendingIntentSenderId", record.persistentTokenId());
        request.putInt("pendingIntentRequestCode", record.spec().requestCode());
        request.putString("pendingIntentKind", record.spec().kind().name());
        request.putString("pendingIntentCreatorPackage", record.packageName());
        request.putInt("pendingIntentCreatorUid", record.creatorUid());
        Context brokerContext = brokerContext();
        RouteBrokerClient.Callback callback = result -> {
            Bundle event = new Bundle(result);
            event.putString("pendingIntentSenderId", record.persistentTokenId());
            event.putString("pendingIntentKind", record.spec().kind().name());
            RuntimeEventLog.event("VIRTUAL_PENDING_INTENT_DELIVERY", event);
        };
        switch (record.spec().kind()) {
            case ACTIVITY -> RouteBrokerClient.launchActivity(brokerContext, request, callback);
            case BROADCAST -> {
                request.putInt(RuntimeKeys.BROADCAST_RESULT_CODE,
                        sendRequest == null ? 0 : sendRequest.resultCode());
                request.putString(ComponentOperations.OPERATION,
                        intent.getComponent() == null
                                ? ComponentOperations.SEND_IMPLICIT_BROADCAST
                                : ComponentOperations.SEND_BROADCAST);
                RouteBrokerClient.invokeComponent(brokerContext, request, callback);
            }
            case SERVICE, FOREGROUND_SERVICE -> {
                // A foreground-service sender must enter the same policy/deadline/recovery
                // transaction as Context.startForegroundService(). An advisory boolean is not
                // consumed by GuestComponentRuntime and silently downgraded this path.
                request.putString(ComponentOperations.OPERATION,
                        serviceOperation(record.spec().kind()));
                RouteBrokerClient.invokeComponent(brokerContext, request, callback);
            }
            case ACTIVITY_RESULT -> throw new AssertionError("handled above");
        }
        return 0;
    }

    static String serviceOperation(VirtualPendingIntentRegistry.Kind kind) {
        if (kind == VirtualPendingIntentRegistry.Kind.FOREGROUND_SERVICE) {
            return ComponentOperations.START_FOREGROUND_SERVICE;
        }
        if (kind == VirtualPendingIntentRegistry.Kind.SERVICE) {
            return ComponentOperations.START_SERVICE;
        }
        throw new IllegalArgumentException("PendingIntent kind is not a Service: " + kind);
    }

    private int dispatchActivityResult(VirtualPendingIntentRegistry.Record record, Intent intent,
                                       VirtualPendingIntentRegistry.SendRequest sendRequest) {
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
                sendRequest == null ? 0 : sendRequest.resultCode(),
                GuestActivityResultBridge.snapshot(intent));
        RouteBrokerClient.activityResultOperation(brokerContext(), request, result -> {
            Bundle event = new Bundle();
            event.putString("pendingIntentSenderId", record.persistentTokenId());
            event.putString("pendingIntentKind", record.spec().kind().name());
            event.putBoolean("activityResultAccepted", result.successful());
            RuntimeEventLog.event("VIRTUAL_PENDING_INTENT_ACTIVITY_RESULT", event);
        });
        return 0;
    }

    private Context brokerContext() {
        return context instanceof GuestContext guest ? guest.hostServiceContext() : context;
    }

    static Intent selectedIntent(Object payload,
            VirtualPendingIntentRegistry.SendRequest request) {
        Intent base = null;
        if (payload instanceof Intent[] intents && intents.length > 0) base = intents[intents.length - 1];
        else if (payload instanceof android.os.Parcelable[] values && values.length > 0
                && values[values.length - 1] instanceof Intent) {
            base = (Intent) values[values.length - 1];
        }
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

    private void applyIntent(Bundle target, Intent intent,
                             VirtualPendingIntentRegistry.Kind pendingKind) {
        ComponentName component = intent.getComponent();
        if (component != null
                || pendingKind == VirtualPendingIntentRegistry.Kind.ACTIVITY
                || pendingKind == VirtualPendingIntentRegistry.Kind.SERVICE
                || pendingKind == VirtualPendingIntentRegistry.Kind.FOREGROUND_SERVICE) {
            GuestIntentResolver.Target resolved = resolver().resolveOne(intent,
                    resolverKind(pendingKind));
            target.putAll(pendingKind == VirtualPendingIntentRegistry.Kind.ACTIVITY
                    ? resolver().activityRequest(intent, resolved)
                    : resolver().request(intent, resolved));
        } else {
            // Do not collapse an implicit broadcast to the first receiver.  Broker-side routing
            // must retain the full ordered/multi-receiver result set.
            GuestIntentResolver.applyIntent(target, intent);
        }
        String clip = clipDescription(intent);
        if (!clip.isEmpty()) target.putString("pendingIntentClipDescription", clip);
        copyExtras(intent, target);
    }

    private static GuestIntentResolver.Kind resolverKind(
            VirtualPendingIntentRegistry.Kind pendingKind) {
        return switch (pendingKind) {
            case ACTIVITY -> GuestIntentResolver.Kind.ACTIVITY;
            case SERVICE, FOREGROUND_SERVICE -> GuestIntentResolver.Kind.SERVICE;
            case BROADCAST -> GuestIntentResolver.Kind.RECEIVER;
            case ACTIVITY_RESULT -> throw new IllegalArgumentException(
                    "activity result is dispatched through its dedicated route");
        };
    }

    private GuestIntentResolver resolver() {
        GuestIntentResolver current = resolver;
        if (current != null) return current;
        synchronized (this) {
            current = resolver;
            if (current == null) {
                // GuestContext's PackageManager proxy is installed as part of FrameworkHooks.
                // Constructing it during bindApplication would violate that bootstrap order;
                // delivery is the first point at which the complete virtual PM is available.
                current = new GuestIntentResolver(spec, context.getPackageManager());
                resolver = current;
            }
            return current;
        }
    }

    private static void copyExtras(Intent intent, Bundle target) {
        try {
            Method method = Intent.class.getMethod("getExtras");
            Object value = method.invoke(intent);
            if (value instanceof Bundle) target.putAll((Bundle) value);
        } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
    }

    private static void copyExtras(Intent source, Intent target) {
        try {
            Method method = Intent.class.getMethod("getExtras");
            Object value = method.invoke(source);
            if (value instanceof Bundle) Intent.class.getMethod("putExtras", Bundle.class).invoke(target, value);
        } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
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
