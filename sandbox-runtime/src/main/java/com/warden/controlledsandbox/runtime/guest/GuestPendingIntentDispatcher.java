package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Method;

/** Converts a virtual sender delivery back into the existing Broker component routes. */
final class GuestPendingIntentDispatcher implements PendingIntentFrameworkInterceptor.Dispatcher {
    private final Context context;
    private final GuestPackageSpec spec;

    GuestPendingIntentDispatcher(Context context, GuestPackageSpec spec) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
    }

    @Override public int dispatch(VirtualPendingIntentRegistry.Record record, Intent fillInIntent) {
        Intent intent = selectedIntent(record.payload(), fillInIntent);
        Bundle request = spec.toBundle();
        applyIntent(request, intent);
        request.putString("pendingIntentSenderId", Long.toString(record.id()));
        request.putInt("pendingIntentRequestCode", record.spec().requestCode());
        request.putString("pendingIntentKind", record.spec().kind().name());
        RouteBrokerClient.Callback callback = result -> {
            Bundle event = new Bundle(result);
            event.putLong("pendingIntentSenderId", record.id());
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
            case ACTIVITY_RESULT -> throw new UnsupportedOperationException(
                    "VIRTUAL_PENDING_INTENT_ACTIVITY_RESULT_NOT_IMPLEMENTED");
        }
        return 0;
    }

    private static Intent selectedIntent(Object payload, Intent fillIn) {
        Intent base = null;
        if (payload instanceof Intent[] intents && intents.length > 0) base = intents[intents.length - 1];
        else if (payload instanceof Intent) base = (Intent) payload;
        if (fillIn != null) return new Intent(fillIn);
        return base == null ? new Intent() : new Intent(base);
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
        copyExtras(intent, target);
    }

    private static void copyExtras(Intent intent, Bundle target) {
        try {
            Method method = Intent.class.getMethod("getExtras");
            Object value = method.invoke(intent);
            if (value instanceof Bundle) target.putAll((Bundle) value);
        } catch (Throwable ignored) { }
    }
}
