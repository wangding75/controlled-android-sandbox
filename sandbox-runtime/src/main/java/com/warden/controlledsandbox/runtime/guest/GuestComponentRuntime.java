package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.ProviderBulkInsertRuntime;
import com.warden.controlledsandbox.runtime.provider.GuestProviderFileTransport;
import com.warden.controlledsandbox.runtime.provider.ProviderBatchRuntime;
import com.warden.controlledsandbox.runtime.provider.ProviderCursorTransport;
import com.warden.controlledsandbox.domain.component.service.ForegroundServiceStateMachine;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.IProviderQueryCancellation;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local lifecycle owner for non-Activity Guest components. */
public final class GuestComponentRuntime {
    private final GuestRuntimeEnvironment.Session session;
    private final Map<String, ServiceRecord> services = new LinkedHashMap<>();
    private final Map<String, ReceiverRecord> receivers = new LinkedHashMap<>();
    private final Map<String, ProviderRecord> providersByClass = new LinkedHashMap<>();
    private final Map<String, ProviderRecord> providersByAuthority = new LinkedHashMap<>();
    private final Object providerLock = new Object();
    private final ProviderCursorTransport cursorTransport = new ProviderCursorTransport();
    private final GuestProviderFileTransport fileTransport = new GuestProviderFileTransport();
    private int nextStartId = 1;

    GuestComponentRuntime(GuestRuntimeEnvironment.Session session) { this.session = session; }

    /**
     * Mirrors Android's process bootstrap order: enabled providers declared for this Guest
     * process are attached before Application.onCreate().  Explicit provider operations remain
     * lazy for providers in other processes and for components not selected by this process.
     */
    void prepareDeclaredProviders() throws Exception {
        int fromSnapshot = 0;
        int fromManifest = 0;
        int skippedOtherProcess = 0;
        java.util.Set<String> preparedClasses = new java.util.LinkedHashSet<>();
        for (VirtualComponentSnapshot component : session.spec.packageState().components()) {
            if (!"PROVIDER".equals(component.type()) || !component.enabled()) continue;
            android.util.Log.i("CS_PROVIDER_DECL", "source=snapshot class=" + component.className()
                    + " process=" + component.processName() + " authority=" + component.authority());
            if (!sameProcess(component.processName())) {
                skippedOtherProcess++;
                continue;
            }
            fromSnapshot += prepareProviderAuthorities(component.className(), component.authority());
            preparedClasses.add(component.className());
        }
        com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel manifest =
                parseBaseManifest();
        if (manifest != null) {
            for (com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel.Component
                    component : manifest.providers()) {
                android.util.Log.i("CS_PROVIDER_DECL", "source=manifest class=" + component.className()
                        + " process=" + component.processName() + " authority=" + component.authorities());
                if (!component.enabled() || preparedClasses.contains(component.className())) continue;
                if (!sameProcess(component.processName())) {
                    skippedOtherProcess++;
                    continue;
                }
                fromManifest += prepareProviderAuthorities(component.className(), component.authorities());
                preparedClasses.add(component.className());
            }
        }
        android.util.Log.i("CS_RUNTIME", "GUEST_PROVIDERS_BOUND snapshot=" + fromSnapshot
                + " manifestExtra=" + fromManifest + " skippedOtherProcess=" + skippedOtherProcess
                + " classes=" + preparedClasses.size());
    }

    private int prepareProviderAuthorities(String className, String authorities) throws Exception {
        int prepared = 0;
        if (authorities == null || authorities.trim().isEmpty()) {
            throw new IllegalStateException("PROVIDER_AUTHORITY_REQUIRED:" + className);
        }
        for (String authority : authorities.split(";")) {
            String normalized = authority == null ? "" : authority.trim();
            if (normalized.isEmpty()) continue;
            prepareProvider(className, normalized);
            prepared++;
        }
        return prepared;
    }

    private com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel parseBaseManifest() {
        com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel frameworkManifest =
                parseFrameworkManifest();
        if (frameworkManifest != null) return frameworkManifest;
        if (session.spec.isolatedProcess) {
            // The isolated route has no pathname capability for the logical APK path.  The
            // framework XmlResourceParser above is the authoritative FD-backed artifact
            // boundary; never fall back to ZipFile here and silently lose manifest providers.
            return null;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(session.spec.apkFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            try (java.io.InputStream input = zip.getInputStream(entry)) {
                return new com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser()
                        .parse(input);
            }
        } catch (Exception error) {
            android.util.Log.w("CS_RUNTIME", "base manifest providers unavailable", error);
            return null;
        }
    }

    /**
     * AssetManager owns the decompression and binary-XML decoding boundary.  This is required
     * for FD-backed isolated APKs: AndroidManifest.xml is commonly compressed and therefore
     * cannot be exposed as an AssetFileDescriptor or read through a pathname ZipFile.
     */
    private com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel parseFrameworkManifest() {
        if (session.resources.manifestAssets != null) {
            try (android.content.res.XmlResourceParser parser =
                         session.resources.manifestAssets.openXmlResourceParser(
                                 "AndroidManifest.xml")) {
                final String androidNamespace = "http://schemas.android.com/apk/res/android";
                com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel model =
                        new com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel();
                model.packageName(session.spec.packageName);
                int queriesDepth = -1;
                int event;
                while ((event = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        if ("queries".equals(parser.getName())) {
                            queriesDepth = parser.getDepth();
                            continue;
                        }
                        if (!"provider".equals(parser.getName()) || queriesDepth >= 0) continue;
                        String rawName = frameworkAttribute(parser, androidNamespace, "name");
                        String authorities = frameworkAttribute(parser, androidNamespace, "authorities");
                        if (rawName == null || rawName.trim().isEmpty()
                                || authorities == null || authorities.trim().isEmpty()) continue;
                        String permission = frameworkAttribute(parser, androidNamespace, "permission");
                        String readPermission = frameworkAttribute(parser, androidNamespace, "readPermission");
                        String writePermission = frameworkAttribute(parser, androidNamespace, "writePermission");
                        if (readPermission == null || readPermission.trim().isEmpty()) readPermission = permission;
                        if (writePermission == null || writePermission.trim().isEmpty()) writePermission = permission;
                        String process = frameworkAttribute(parser, androidNamespace, "process");
                        boolean exported = parser.getAttributeBooleanValue(androidNamespace,
                                "exported", false);
                        boolean exportedExplicit = parser.getAttributeValue(androidNamespace,
                                "exported") != null;
                        boolean enabled = parser.getAttributeBooleanValue(androidNamespace,
                                "enabled", true);
                        boolean grantUriPermissions = parser.getAttributeBooleanValue(androidNamespace,
                                "grantUriPermissions", false);
                        model.addProvider(new com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel.Component(
                                model.resolveClassName(rawName), process, exported, exportedExplicit,
                                enabled, false, authorities, permission == null ? "" : permission,
                                readPermission == null ? "" : readPermission,
                                writePermission == null ? "" : writePermission, grantUriPermissions));
                    } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG
                            && parser.getDepth() == queriesDepth) {
                        queriesDepth = -1;
                    }
                }
                return model;
            } catch (Exception error) {
                android.util.Log.w("CS_RUNTIME", "base manifest framework parser unavailable", error);
            }
        }
        return null;
    }

    private static String frameworkAttribute(android.content.res.XmlResourceParser parser,
                                             String namespace, String name) {
        String value = parser.getAttributeValue(namespace, name);
        if (value == null) value = parser.getAttributeValue(null, name);
        return value;
    }

    private boolean sameProcess(String componentProcess) {
        String declared = componentProcess == null ? "" : componentProcess.trim();
        String packageProcess = session.spec.packageName;
        String sessionProcess = session.spec.processName == null || session.spec.processName.isEmpty()
                ? packageProcess : session.spec.processName;
        String effective;
        if (declared.isEmpty()) effective = packageProcess;
        else if (declared.startsWith(":")) effective = packageProcess + declared;
        else effective = declared;
        return sessionProcess.equals(effective) || packageProcess.equals(effective)
                && sessionProcess.equals(packageProcess);
    }

    Bundle invoke(Bundle request) {
        try {
            if (request == null) throw new IllegalArgumentException("request is required");
            String operation = required(request, ComponentOperations.OPERATION);
            if (requiresMainThread(operation)) {
                return session.mainThread.call(() -> invokeOperation(request, operation));
            }
            return withGuestClassLoader(() -> invokeOperation(request, operation));
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return failure(error);
        }
    }

    private Bundle invokeOperation(Bundle request, String operation) throws Exception {
        String componentClass = request.getString(RuntimeKeys.COMPONENT_CLASS, "");
        IsolatedComponentPolicy.requireSupported(session.packageMetadata, componentClass,
                request.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false));
        if (ComponentOperations.isServiceOperation(operation)) {
            return invokeServiceOperation(componentClass, request, operation);
        }
        if (ComponentOperations.isProviderOperation(operation)) {
            return invokeProviderOperation(componentClass, request, operation);
        }
        return invokeReceiverOperation(componentClass, request, operation);
    }

    private Bundle invokeServiceOperation(String componentClass, Bundle request, String operation)
            throws Exception {
        return switch (operation) {
            case ComponentOperations.START_SERVICE ->
                    startServiceThroughFramework(componentClass, request, false);
            case ComponentOperations.START_FOREGROUND_SERVICE ->
                    startServiceThroughFramework(componentClass, request, true);
            case ComponentOperations.ROUTE_FRAMEWORK_SERVICE ->
                    routeIsolatedOrFrameworkService(componentClass, request);
            case ComponentOperations.FRAMEWORK_SERVICE_EVENT ->
                    applyIsolatedFrameworkServiceEvent(componentClass, request);
            case ComponentOperations.RECOVER_FRAMEWORK_SERVICE -> {
                GuestActivityThreadServiceBridge framework = session.context.serviceFrameworkBridge();
                if (framework == null) throw new IllegalStateException(
                        "GUEST_SERVICE_FRAMEWORK_BRIDGE_UNAVAILABLE_FOR_RECOVERY");
                yield framework.recover(request, componentClass,
                        request.getBoolean(RuntimeKeys.FRAMEWORK_SERVICE_FOREGROUND, false));
            }
            case ComponentOperations.STOP_SERVICE -> stopServiceThroughFramework(componentClass, request);
            case ComponentOperations.STOP_SERVICE_START_ID -> stopServiceStartId(componentClass,
                    request.getInt(RuntimeKeys.SERVICE_START_ID, -1));
            case ComponentOperations.SET_SERVICE_FOREGROUND -> setServiceForeground(componentClass, request);
            case ComponentOperations.BIND_SERVICE -> bindService(componentClass,
                    required(request, RuntimeKeys.CONNECTION_ID), request);
            case ComponentOperations.UNBIND_SERVICE -> unbindService(componentClass,
                    required(request, RuntimeKeys.CONNECTION_ID));
            default -> throw new IllegalArgumentException("Unknown Service operation: " + operation);
        };
    }

    private Bundle invokeReceiverOperation(String componentClass, Bundle request, String operation)
            throws Exception {
        return switch (operation) {
            case ComponentOperations.ROUTE_FRAMEWORK_RECEIVER ->
                    routeFrameworkReceiver(componentClass, request);
            case ComponentOperations.REGISTER_RECEIVER -> registerReceiver(componentClass, request);
            case ComponentOperations.UNREGISTER_RECEIVER -> unregisterReceiver(
                    required(request, RuntimeKeys.RECEIVER_ID));
            case ComponentOperations.SEND_BROADCAST -> sendBroadcast(componentClass, request);
            default -> throw new IllegalArgumentException("Unknown component operation: " + operation);
        };
    }

    private Bundle routeFrameworkReceiver(String componentClass, Bundle request) {
        GuestActivityThreadServiceBridge framework = session.context.serviceFrameworkBridge();
        if (framework == null) {
            throw new IllegalStateException("GUEST_RECEIVER_FRAMEWORK_BRIDGE_UNAVAILABLE");
        }
        return framework.dispatchFrameworkReceiver(request, componentClass);
    }

    private Bundle invokeProviderOperation(String componentClass, Bundle request, String operation)
            throws Exception {
        if (ComponentOperations.PREPARE_PROVIDER.equals(operation)) {
            return prepareProvider(componentClass, required(request, ComponentOperations.AUTHORITY));
        }
        if (ComponentOperations.PROVIDER_CURSOR_PAGE.equals(operation)) {
            return withSessionIdentity(cursorTransport.page(required(request, RuntimeKeys.CURSOR_TOKEN),
                    session.spec.sessionId, session.spec.generation,
                    request.getInt(RuntimeKeys.CURSOR_OFFSET, 0),
                    request.getLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE, -1),
                    request.getInt(RuntimeKeys.CURSOR_PAGE_SIZE, 64)));
        }
        if (ComponentOperations.PROVIDER_CURSOR_CLOSE.equals(operation)) {
            return withSessionIdentity(cursorTransport.close(required(request, RuntimeKeys.CURSOR_TOKEN),
                    session.spec.sessionId, session.spec.generation));
        }
        if (ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(operation)) {
            return withSessionIdentity(cursorTransport.cancel(required(request, RuntimeKeys.CURSOR_TOKEN),
                    session.spec.sessionId, session.spec.generation));
        }
        if (ComponentOperations.PROVIDER_FILE_CLOSE.equals(operation)) {
            return withSessionIdentity(fileTransport.close(required(request, RuntimeKeys.FILE_TOKEN),
                    session.spec.sessionId, session.spec.generation));
        }
        return invokeProviderTransaction(componentClass, request, operation);
    }

    private Bundle invokeProviderTransaction(String componentClass, Bundle request, String operation)
            throws Exception {
        return switch (operation) {
            case ComponentOperations.PROVIDER_QUERY -> queryProvider(componentClass, request);
            case ComponentOperations.PROVIDER_CANONICALIZE -> canonicalizeProvider(
                    componentClass, request, true);
            case ComponentOperations.PROVIDER_UNCANONICALIZE -> canonicalizeProvider(
                    componentClass, request, false);
            case ComponentOperations.PROVIDER_GET_TYPE -> getProviderType(componentClass, request);
            case ComponentOperations.PROVIDER_GET_TYPE_ANONYMOUS ->
                    getProviderTypeAnonymous(componentClass, request);
            case ComponentOperations.PROVIDER_GET_STREAM_TYPES ->
                    getProviderStreamTypes(componentClass, request);
            case ComponentOperations.PROVIDER_REFRESH -> refreshProvider(componentClass, request);
            case ComponentOperations.PROVIDER_INSERT -> insertProvider(componentClass, request);
            case ComponentOperations.PROVIDER_BULK_INSERT -> bulkInsertProvider(componentClass, request);
            case ComponentOperations.PROVIDER_UPDATE -> updateProvider(componentClass, request);
            case ComponentOperations.PROVIDER_DELETE -> deleteProvider(componentClass, request);
            case ComponentOperations.PROVIDER_CALL -> callProvider(componentClass, request);
            case ComponentOperations.PROVIDER_APPLY_BATCH -> applyBatchProvider(componentClass, request);
            case ComponentOperations.PROVIDER_OPEN_FILE -> openProviderFile(componentClass, request);
            case ComponentOperations.PROVIDER_OPEN_ASSET_FILE -> openProviderAssetFile(componentClass, request);
            case ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE ->
                    openProviderTypedAssetFile(componentClass, request);
            default -> throw new IllegalArgumentException("Unknown Provider operation: " + operation);
        };
    }

    private static boolean requiresMainThread(String operation) {
        return ComponentOperations.START_SERVICE.equals(operation)
                || ComponentOperations.START_FOREGROUND_SERVICE.equals(operation)
                || ComponentOperations.STOP_SERVICE.equals(operation)
                || ComponentOperations.STOP_SERVICE_START_ID.equals(operation)
                || ComponentOperations.SET_SERVICE_FOREGROUND.equals(operation)
                || ComponentOperations.BIND_SERVICE.equals(operation)
                || ComponentOperations.UNBIND_SERVICE.equals(operation)
                || ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(operation)
                || ComponentOperations.FRAMEWORK_SERVICE_EVENT.equals(operation)
                || ComponentOperations.SEND_BROADCAST.equals(operation)
                || ComponentOperations.PREPARE_PROVIDER.equals(operation);
    }

    void shutdown() {
        session.mainThread.run(this::shutdownOnMain);
    }

    private void shutdownOnMain() {
        cursorTransport.closeAll();
        fileTransport.closeAll();
        for (ServiceRecord record : services.values()) destroyService(record);
        services.clear();
        receivers.clear();
        session.context.clearDynamicReceivers();
        java.util.List<ProviderRecord> providerRecords;
        synchronized (providerLock) {
            providerRecords = new java.util.ArrayList<>(providersByClass.values());
            providersByClass.clear();
            providersByAuthority.clear();
        }
        for (ProviderRecord record : providerRecords) {
            try { record.provider.shutdown(); }
            catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            }
        }
    }

    private Bundle routeIsolatedOrFrameworkService(String className, Bundle request)
            throws Exception {
        if (session.spec.isolatedProcess) {
            // Isolated workers already occupy the dedicated isolated UID process. A second
            // ROUTE_FRAMEWORK_SERVICE hop back to the Broker would deadlock the same slot.
            return startService(className, request,
                    request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND, false));
        }
        return startServiceThroughFramework(className, request, false);
    }

    private Bundle applyIsolatedFrameworkServiceEvent(String className, Bundle request) {
        if (session.spec.isolatedProcess) {
            Bundle out = success("SERVICE_EVENT_APPLIED", className);
            out.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
            return out;
        }
        throw new IllegalArgumentException("Unknown Service operation: "
                + ComponentOperations.FRAMEWORK_SERVICE_EVENT);
    }

    private Bundle startServiceThroughFramework(String className, Bundle request,
                                                boolean foregroundRequested) throws Exception {
        GuestActivityThreadServiceBridge framework = session.context.serviceFrameworkBridge();
        if (framework == null || session.spec.isolatedProcess) {
            return startService(className, request, foregroundRequested);
        }
        boolean recovery = request.getBoolean(RuntimeKeys.SERVICE_RECOVERY, false);
        if (recovery) {
            return framework.recover(request, className, foregroundRequested);
        }
        framework.start(request, className, foregroundRequested);
        Bundle out = success("SERVICE_STARTED", className);
        out.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        out.putBoolean("created", true);
        out.putInt("startId", 0);
        out.putInt(RuntimeKeys.SERVICE_START_ID, 0);
        out.putInt("startCount", 1);
        out.putInt("connectionCount", 0);
        out.putString("definingLoader", GuestDefiningLoader.of(session).getClass().getName());
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_START", out);
        return out;
    }

    private Bundle stopServiceThroughFramework(String className, Bundle request) {
        GuestActivityThreadServiceBridge framework = session.context.serviceFrameworkBridge();
        if (framework == null || session.spec.isolatedProcess) return stopService(className);
        boolean stopped = framework.stop(request, className);
        Bundle out = success(stopped ? "SERVICE_STOPPED" : "SERVICE_NOT_RUNNING", className);
        out.putBoolean(RuntimeKeys.FRAMEWORK_SERVICE_OWNED, true);
        out.putBoolean("destroyed", stopped);
        RuntimeEventLog.event("GUEST_SERVICE_FRAMEWORK_STOP", out);
        return out;
    }

    private Bundle startService(String className, Bundle request, boolean foregroundRequested) throws Exception {
        long foregroundNowMs = android.os.SystemClock.elapsedRealtime();
        long foregroundTimeoutMs = request.getLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS,
                ForegroundServiceStateMachine.DEFAULT_PROMOTION_TIMEOUT_MS);
        boolean backgroundAllowed = request.getBoolean(
                RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED, true);
        String exemptionReason = request.getString(
                RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON, "");
        int declaredTypeMask = request.getInt(
                RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, 0);
        if (foregroundRequested) {
            ForegroundServiceStateMachine validation = new ForegroundServiceStateMachine();
            validation.requestStart(foregroundNowMs, foregroundTimeoutMs, backgroundAllowed,
                    exemptionReason, declaredTypeMask);
        }
        ServiceRecord record = getOrCreateService(className);
        String action = request.getString(ComponentOperations.ACTION, "");
        boolean recovery = request.getBoolean(RuntimeKeys.SERVICE_RECOVERY, false);
        boolean redelivered = request.getBoolean(RuntimeKeys.SERVICE_REDELIVERED, false);
        // The platform contract passes null to a sticky restart.  Only a redelivery receives the
        // retained wire Intent; decoding an empty Broker envelope here would turn null into a
        // non-null Intent and changes real-world Service restart behavior.
        Intent intent = recovery && !redelivered
                ? null : com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.decode(request);
        int recoveredStartId = request.getInt(RuntimeKeys.SERVICE_START_ID, -1);
        int startId = recovery && recoveredStartId > 0 ? recoveredStartId : nextStartId++;
        if (recovery) nextStartId = Math.max(nextStartId, startId + 1);
        if (foregroundRequested) {
            record.foregroundPolicy.requestStart(foregroundNowMs, foregroundTimeoutMs,
                    backgroundAllowed, exemptionReason, declaredTypeMask);
        }
        int flags = redelivered
                ? Service.START_FLAG_REDELIVERY : 0;
        int resultCode;
        try {
            resultCode = record.service.onStartCommand(intent, flags, startId);
            // A Service callback is a legitimate late native-load boundary: static JNI
            // initialization often occurs from onStartCommand rather than construction. Refresh
            // the process-local relocation policy after the callback so the newly loaded Guest
            // module receives the same identity/FS/FD policy as modules present at bootstrap.
            requireNativeHookRefresh("SERVICE_START");
        } catch (Throwable error) {
            try {
                if (foregroundRequested) record.foregroundPolicy.terminate("SERVICE_START_CALLBACK_FAILED");
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            throw error;
        }
        record.startCount++;
        record.lastStartId = startId;
        record.lastStartIntent = intent;
        Bundle out = success(recovery ? "SERVICE_RECOVERED" : "SERVICE_STARTED", className);
        out.putBoolean("created", record.createdNow);
        record.createdNow = false;
        out.putInt("startId", startId);
        out.putInt(RuntimeKeys.SERVICE_START_ID, startId);
        out.putInt("startCount", record.startCount);
        out.putInt("connectionCount", record.connections.size());
        out.putInt("onStartCommandResult", resultCode);
        putForegroundSnapshot(out, record);
        out.putBoolean(RuntimeKeys.SERVICE_REDELIVERED,
                request.getBoolean(RuntimeKeys.SERVICE_REDELIVERED, false));
        RuntimeEventLog.event("GUEST_SERVICE_START", out);
        return out;
    }

    private Bundle stopService(String className) {
        ServiceRecord record = services.get(className);
        if (record == null) return success("SERVICE_NOT_RUNNING", className);
        record.startCount = 0;
        record.foregroundPolicy.terminate("SERVICE_STOPPED");
        boolean destroyed = settleService(className, record);
        Bundle out = success(destroyed ? "SERVICE_STOPPED" : "SERVICE_STOP_REQUESTED", className);
        out.putInt("connectionCount", record.connections.size());
        out.putBoolean("destroyed", destroyed);
        putForegroundSnapshot(out, record);
        RuntimeEventLog.event("GUEST_SERVICE_STOP", out);
        return out;
    }

    private Bundle stopServiceStartId(String className, int startId) {
        if (startId < 1) throw new IllegalArgumentException("serviceStartId must be positive");
        ServiceRecord record = services.get(className);
        if (record == null) return success("SERVICE_NOT_RUNNING", className);
        boolean stopped = startId == record.lastStartId;
        if (stopped) {
            record.startCount = 0;
            record.foregroundPolicy.terminate("SERVICE_STOPPED_BY_START_ID");
        }
        boolean destroyed = stopped && settleService(className, record);
        Bundle out = success(destroyed ? "SERVICE_STOPPED_BY_START_ID"
                : stopped ? "SERVICE_STOP_REQUESTED_BY_START_ID" : "SERVICE_START_ID_STALE", className);
        out.putInt(RuntimeKeys.SERVICE_START_ID, startId);
        out.putBoolean(RuntimeKeys.SERVICE_STOPPED_BY_START_ID, stopped);
        out.putBoolean("destroyed", destroyed);
        out.putInt("connectionCount", record.connections.size());
        RuntimeEventLog.event("GUEST_SERVICE_STOP_START_ID", out);
        return out;
    }

    private Bundle setServiceForeground(String className, Bundle request) {
        ServiceRecord record = services.get(className);
        if (record == null) throw new IllegalArgumentException("SERVICE_NOT_RUNNING");
        boolean foreground = request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, false);
        if (foreground) {
            if (record.startCount == 0) throw new IllegalStateException("FOREGROUND_SERVICE_NOT_STARTED");
            try {
                record.foregroundPolicy.promote(android.os.SystemClock.elapsedRealtime(),
                        request.getInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, 0),
                        request.getInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, -1),
                        request.getString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, ""));
            } catch (RuntimeException error) {
                if (record.foregroundPolicy.snapshot().state()
                        == ForegroundServiceStateMachine.State.TIMED_OUT) {
                    record.startCount = 0;
                    settleService(className, record);
                }
                throw error;
            }
        } else {
            record.foregroundPolicy.demote(
                    request.getBoolean(RuntimeKeys.SERVICE_FOREGROUND_REMOVE_NOTIFICATION, true),
                    "SERVICE_FOREGROUND_DEMOTED");
        }
        Bundle out = success(foreground ? "SERVICE_FOREGROUND" : "SERVICE_BACKGROUND", className);
        putForegroundSnapshot(out, record);
        out.putInt("startCount", record.startCount);
        out.putInt("connectionCount", record.connections.size());
        RuntimeEventLog.event("GUEST_SERVICE_FOREGROUND", out);
        return out;
    }

    private Bundle bindService(String className, String connectionId, Bundle request) throws Exception {
        ServiceRecord record = getOrCreateService(className);
        if (record.connections.containsKey(connectionId)) throw new IllegalStateException("DUPLICATE_SERVICE_CONNECTION");
        Intent intent = com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.decode(request);
        IBinder binder;
        boolean rebound = false;
        if (record.connections.isEmpty() && record.rebindRequested && record.lastBinder != null) {
            record.service.onRebind(intent);
            binder = record.lastBinder;
            record.rebindRequested = false;
            rebound = true;
        } else {
            binder = record.service.onBind(intent);
            record.lastBinder = binder;
        }
        record.connections.put(connectionId, new BoundConnection(connectionId, intent));
        Bundle out = success(binder == null ? "SERVICE_NULL_BINDING" : "SERVICE_BOUND", className);
        out.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        out.putInt("connectionCount", record.connections.size());
        out.putBoolean("created", record.createdNow);
        record.createdNow = false;
        out.putBoolean("rebound", rebound);
        putForegroundSnapshot(out, record);
        if (binder != null) out.putBinder(RuntimeKeys.BINDER, binder);
        RuntimeEventLog.event("GUEST_SERVICE_BIND", out);
        return out;
    }

    private Bundle unbindService(String className, String connectionId) {
        ServiceRecord record = services.get(className);
        if (record == null) throw new IllegalArgumentException("SERVICE_NOT_RUNNING");
        BoundConnection removed = record.connections.remove(connectionId);
        if (removed == null) throw new IllegalArgumentException("UNKNOWN_SERVICE_CONNECTION");
        if (record.connections.isEmpty()) record.rebindRequested = record.service.onUnbind(removed.intent);
        boolean destroyed = settleService(className, record);
        Bundle out = success(destroyed ? "SERVICE_UNBOUND_DESTROYED" : "SERVICE_UNBOUND", className);
        out.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        out.putInt("connectionCount", record.connections.size());
        out.putBoolean("rebindRequested", record.rebindRequested);
        out.putBoolean("destroyed", destroyed);
        putForegroundSnapshot(out, record);
        RuntimeEventLog.event("GUEST_SERVICE_UNBIND", out);
        return out;
    }

    private ServiceRecord getOrCreateService(String className) throws Exception {
        if (className == null || className.trim().isEmpty()) throw new IllegalArgumentException("Service class is required");
        ServiceRecord record = services.get(className);
        if (record != null) return record;
        Class<?> type = GuestDefiningLoader.loadComponent(session, className);
        if (!Service.class.isAssignableFrom(type)) throw new IllegalArgumentException("Component is not a Service: " + className);
        Service service = GuestComponentFactory.instantiateService(GuestDefiningLoader.of(session),
                factoryClass(), className, new Intent());
        attachBaseContext(service, session.context);
        setOptionalField(service, "mApplication", session.application);
        setOptionalField(service, "mClassName", className);
        service.onCreate();
        requireNativeHookRefresh("SERVICE_CREATE");
        record = new ServiceRecord(service);
        record.createdNow = true;
        services.put(className, record);
        return record;
    }

    private void requireNativeHookRefresh(String stage) {
        if (session.nativeHooksInstalled) {
            boolean refreshed = NativePolicy.refreshHooks();
            android.util.Log.i("CS_NATIVE_HOOK", "REFRESH stage=" + stage
                    + " refreshed=" + refreshed + " status=" + NativePolicy.hookStatus());
            if (refreshed) return;
            throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_" + stage + ":"
                    + NativePolicy.hookStatus());
        }
    }

    private boolean settleService(String className, ServiceRecord record) {
        if (record.startCount > 0 || !record.connections.isEmpty()) return false;
        services.remove(className);
        destroyService(record);
        return true;
    }

    private static void destroyService(ServiceRecord record) {
        try { record.service.onDestroy(); } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
        record.connections.clear();
        record.lastBinder = null;
        record.lastStartIntent = null;
        record.foregroundPolicy.terminate("SERVICE_DESTROYED");
    }

    private static void putForegroundSnapshot(Bundle out, ServiceRecord record) {
        ForegroundServiceStateMachine.Snapshot snapshot = record.foregroundPolicy.snapshot();
        out.putBoolean(RuntimeKeys.SERVICE_FOREGROUND, snapshot.state() == ForegroundServiceStateMachine.State.ACTIVE);
        out.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED,
                snapshot.state() == ForegroundServiceStateMachine.State.PENDING
                        || snapshot.state() == ForegroundServiceStateMachine.State.ACTIVE);
        out.putString(RuntimeKeys.SERVICE_FOREGROUND_STATE, snapshot.state().name());
        out.putLong(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_AT_MS, snapshot.requestedAtMs());
        out.putLong(RuntimeKeys.SERVICE_FOREGROUND_DEADLINE_MS, snapshot.promotionDeadlineMs());
        out.putLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTED_AT_MS, snapshot.promotedAtMs());
        out.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK, snapshot.declaredTypeMask());
        out.putInt(RuntimeKeys.SERVICE_FOREGROUND_ACTIVE_TYPE_MASK, snapshot.activeTypeMask());
        out.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, snapshot.notificationId());
        out.putString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG, snapshot.notificationTag());
        out.putString(RuntimeKeys.SERVICE_FOREGROUND_TERMINAL_REASON, snapshot.terminalReason());
    }

    private Bundle registerReceiver(String className, Bundle request) throws Exception {
        if (className.trim().isEmpty()) throw new IllegalArgumentException("Receiver class is required");
        String receiverId = request.getString(RuntimeKeys.RECEIVER_ID, "");
        if (receiverId.trim().isEmpty()) throw new IllegalArgumentException("receiverId is required");
        if (receivers.containsKey(receiverId)) throw new IllegalStateException("DUPLICATE_RECEIVER_ID");
        ArrayList<String> actions = request.getStringArrayList(RuntimeKeys.RECEIVER_ACTIONS);
        if (actions == null) actions = new ArrayList<>();
        BroadcastReceiver receiver;
        if (request.getBoolean(RuntimeKeys.RECEIVER_DYNAMIC_INSTANCE, false)) {
            receiver = session.context.dynamicReceiver(receiverId);
            if (!receiver.getClass().getName().equals(className)) {
                throw new SecurityException("DYNAMIC_RECEIVER_CLASS_MISMATCH");
            }
        } else {
            receiver = newReceiver(className);
        }
        ReceiverRecord record = new ReceiverRecord(receiverId, className, receiver,
                request.getBoolean(RuntimeKeys.RECEIVER_DYNAMIC_INSTANCE, false)
                        ? session.context.dynamicReceivers.scheduler(receiverId) : null,
                new ArrayList<>(actions), request.getBoolean(RuntimeKeys.RECEIVER_EXPORTED, false));
        receivers.put(receiverId, record);
        Bundle out = success("RECEIVER_REGISTERED", className);
        out.putString(RuntimeKeys.RECEIVER_ID, receiverId);
        out.putInt("receiverCount", receivers.size());
        RuntimeEventLog.event("GUEST_RECEIVER_REGISTER", out);
        return out;
    }

    private Bundle unregisterReceiver(String receiverId) {
        ReceiverRecord removed = receivers.remove(receiverId);
        if (removed == null) throw new IllegalArgumentException("UNKNOWN_RECEIVER_ID");
        Bundle out = success("RECEIVER_UNREGISTERED", removed.className);
        out.putString(RuntimeKeys.RECEIVER_ID, receiverId);
        out.putInt("receiverCount", receivers.size());
        RuntimeEventLog.event("GUEST_RECEIVER_UNREGISTER", out);
        return out;
    }

    private Bundle sendBroadcast(String className, Bundle request) throws Exception {
        String action = request.getString(ComponentOperations.ACTION, "");
        Intent intent = com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec.decode(request);
        String receiverId = request.getString(RuntimeKeys.RECEIVER_ID, "");
        boolean ordered = request.getBoolean(RuntimeKeys.BROADCAST_ORDERED, false);
        if (ordered) {
            if (!receiverId.trim().isEmpty()) {
                ReceiverRecord record = receivers.get(receiverId);
                if (record == null) throw new IllegalArgumentException("UNKNOWN_RECEIVER_ID");
                return deliverOrderedReceiver(record, intent, request, action);
            }
            if (className == null || className.trim().isEmpty()) {
                throw new IllegalArgumentException("ORDERED_RECEIVER_CLASS_REQUIRED");
            }
            ReceiverRecord record = new ReceiverRecord("", className, newReceiver(className), null,
                    new ArrayList<>(), false);
            return deliverOrderedReceiver(record, intent, request, action);
        }

        int delivered = 0;
        if (!receiverId.trim().isEmpty()) {
            ReceiverRecord record = receivers.get(receiverId);
            if (record == null) throw new IllegalArgumentException("UNKNOWN_RECEIVER_ID");
            deliverReceiver(record, intent);
            delivered = 1;
            className = record.className;
        } else if (className != null && !className.trim().isEmpty()) {
            deliverReceiver(new ReceiverRecord("", className, newReceiver(className), null,
                    new ArrayList<>(), false), intent);
            delivered = 1;
        } else {
            for (ReceiverRecord record : receivers.values()) {
                if (record.actions.contains(action)) {
                    deliverReceiver(record, intent);
                    delivered++;
                }
            }
        }
        Bundle out = success(delivered == 0 ? "BROADCAST_NO_RECEIVERS" : "BROADCAST_DELIVERED",
                className == null ? "" : className);
        out.putString(ComponentOperations.ACTION, action);
        out.putInt("deliveredCount", delivered);
        RuntimeEventLog.event("GUEST_BROADCAST", out);
        return out;
    }

    private Bundle deliverOrderedReceiver(ReceiverRecord record, Intent intent,
                                          Bundle request, String action) throws Exception {
        OrderedReceiverPendingResultBridge bridge = OrderedReceiverPendingResultBridge.install(
                record.receiver, request, session.orderedReceiverFinishInterceptor);
        try {
            deliverReceiver(record, intent);
            Bundle out = bridge.afterOnReceive();
            out.putString(ComponentOperations.ACTION, action);
            out.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
            out.putInt("deliveredCount", 1);
            RuntimeEventLog.event("GUEST_ORDERED_BROADCAST", out);
            return out;
        } catch (Throwable error) {
            try {
                bridge.cancelLocal();
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            throw error;
        }
    }

    private void deliverReceiver(ReceiverRecord record, Intent intent) {
        if (record.scheduler == null || record.scheduler.getLooper() == Looper.getMainLooper()) {
            session.mainThread.run(() -> record.receiver.onReceive(session.context, intent));
            return;
        }
        session.mainThread.callOnHandler(record.scheduler, () -> {
            record.receiver.onReceive(session.context, intent);
            return null;
        });
    }

    private BroadcastReceiver newReceiver(String className) throws Exception {
        Class<?> type = GuestDefiningLoader.loadComponent(session, className);
        if (!BroadcastReceiver.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Component is not a BroadcastReceiver: " + className);
        }
        return GuestComponentFactory.instantiateReceiver(GuestDefiningLoader.of(session), factoryClass(),
                className, new Intent());
    }

    private String factoryClass() {
        return session.context.getApplicationInfo().appComponentFactory;
    }

    private Bundle prepareProvider(String className, String authority) throws Exception {
        ProviderRecord existing;
        synchronized (providerLock) { existing = providersByAuthority.get(authority); }
        if (existing != null && existing.className.equals(className)) {
            return providerResult("PROVIDER_ALREADY_READY", existing);
        }
        ProviderRecord byClass;
        synchronized (providerLock) { byClass = providersByClass.get(className); }
        if (byClass != null) {
            synchronized (providerLock) { providersByAuthority.putIfAbsent(authority, byClass); }
            return providerResult("PROVIDER_AUTHORITY_ATTACHED", byClass);
        }
        Class<?> type = GuestDefiningLoader.loadComponent(session, className);
        if (!ContentProvider.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Component is not a ContentProvider: " + className);
        }
        ContentProvider provider = GuestComponentFactory.instantiateProvider(
                GuestDefiningLoader.of(session), factoryClass(), className);
        requireNativeHookRefresh("PROVIDER_CREATE");
        ProviderInfo info = session.packageMetadata.providerForClass(className);
        if (info == null) info = session.packageMetadata.provider(authority);
        if (info == null || !className.equals(info.name)) {
            throw new SecurityException("PROVIDER_METADATA_MISMATCH:" + className + ":" + authority);
        }
        info.applicationInfo = session.context.getApplicationInfo();
        android.os.Bundle metadata = session.resources.manifestMetadata.providerForClass(className);
        if (metadata == null || metadata.isEmpty()) {
            metadata = session.resources.manifestMetadata.provider(authority);
        }
        if (metadata != null && !metadata.isEmpty()) info.metaData = metadata;
        provider.attachInfo(session.context, info);
        ProviderRecord record = new ProviderRecord(className, authority, provider);
        synchronized (providerLock) {
            providersByClass.put(className, record);
            // First installed class owns a duplicated authority for resolver lookup.
            providersByAuthority.putIfAbsent(authority, record);
        }
        Bundle out = providerResult("PROVIDER_READY", record);
        RuntimeEventLog.event("GUEST_PROVIDER_PREPARE", out);
        return out;
    }

    private Bundle queryProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        String[] projection = toArray(request.getStringArrayList(RuntimeKeys.PROVIDER_PROJECTION));
        String[] selectionArgs = toArray(request.getStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS));
        android.database.Cursor cursor = null;
        android.os.CancellationSignal cancellationSignal = new android.os.CancellationSignal();
        IProviderQueryCancellation cancellationChannel = queryCancellationChannel(request);
        IProviderQueryCancellation cancellationEndpoint = new IProviderQueryCancellation.Stub() {
            @Override public void attach(IProviderQueryCancellation ignored) { }
            @Override public void cancel() { cancellationSignal.cancel(); }
            @Override public void detach() { }
        };
        if (cancellationChannel != null) cancellationChannel.attach(cancellationEndpoint);
        Bundle queryArgs = request.getBundle(RuntimeKeys.PROVIDER_QUERY_ARGS);
        try {
            cancellationSignal.throwIfCanceled();
            if (queryArgs != null) {
                cursor = queryProviderWithArgs(record.provider, uri, projection, queryArgs,
                        cancellationSignal);
            } else {
                cursor = record.provider.query(uri, projection,
                        request.getString(RuntimeKeys.PROVIDER_SELECTION), selectionArgs,
                        request.getString(RuntimeKeys.PROVIDER_SORT_ORDER));
            }
            cancellationSignal.throwIfCanceled();
            Bundle out = cursorTransport.open(cursor, required(request, RuntimeKeys.CURSOR_TOKEN),
                    session.spec.sessionId, providerInstance(record), session.spec.generation,
                    request.getInt(RuntimeKeys.CURSOR_PAGE_SIZE, 64),
                    request.getLong(RuntimeKeys.CURSOR_TTL_MS, ProviderCursorTransport.DEFAULT_LEASE_TTL_MS));
            cursor = null;
            withSessionIdentity(out);
            out.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
            out.putString(ComponentOperations.AUTHORITY, record.authority);
            out.putString(RuntimeKeys.URI, uri.toString());
            RuntimeEventLog.event("GUEST_PROVIDER_QUERY", out);
            return out;
        } finally {
            if (cursor != null) {
                try { cursor.close(); }
                catch (Throwable ignored) {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
                }
            }
            if (cancellationChannel != null) {
                try { cancellationChannel.detach(); }
                catch (android.os.RemoteException ignored) { }
            }
        }
    }

    private static IProviderQueryCancellation queryCancellationChannel(Bundle request) {
        android.os.IBinder binder = request.getBinder(RuntimeKeys.PROVIDER_QUERY_CANCEL_CHANNEL);
        return binder == null ? null : IProviderQueryCancellation.Stub.asInterface(binder);
    }

    private static android.database.Cursor queryProviderWithArgs(
            android.content.ContentProvider provider, Uri uri, String[] projection,
            Bundle queryArgs, android.os.CancellationSignal cancellationSignal) throws Exception {
        try {
            java.lang.reflect.Method modern = provider.getClass().getMethod("query", Uri.class,
                    String[].class, Bundle.class, android.os.CancellationSignal.class);
            Object result = modern.invoke(provider, uri, projection, new Bundle(queryArgs),
                    cancellationSignal);
            return (android.database.Cursor) result;
        } catch (NoSuchMethodException unavailableOnLegacyApi) {
            // API 25 and the compact verifier stubs expose only the legacy signature.  Preserve
            // the standard SQL argument subset when falling back instead of dropping queryArgs.
            return provider.query(uri, projection,
                    queryArgs.getString("android:query-arg-sql-selection"),
                    queryStringArray(queryArgs, "android:query-arg-sql-selection-args"),
                    queryArgs.getString("android:query-arg-sql-sort-order"));
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("PROVIDER_MODERN_QUERY_FAILED", cause);
        }
    }

    private static String[] queryStringArray(Bundle args, String key) {
        try {
            java.lang.reflect.Method getter = Bundle.class.getMethod("getStringArray", String.class);
            Object value = getter.invoke(args, key);
            if (value instanceof String[] strings) return strings;
        } catch (NoSuchMethodException ignored) {
            // Compact API stubs and pre-API 12 compatibility layers may not expose this getter.
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("PROVIDER_QUERY_ARGS_UNREADABLE:" + key, error);
        }
        ArrayList<String> values = args.getStringArrayList(key);
        return values == null ? null : values.toArray(new String[0]);
    }

    private Bundle getProviderType(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        Bundle out = providerResult("PROVIDER_TYPE", record);
        out.putString("mimeType", record.provider.getType(uri));
        out.putString(RuntimeKeys.URI, uri.toString());
        return out;
    }

    private Bundle canonicalizeProvider(String className, Bundle request, boolean canonicalize)
            throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        String methodName = canonicalize ? "canonicalize" : "uncanonicalize";
        Uri result = invokeProviderUri(record.provider, methodName, uri);
        Bundle out = providerResult(canonicalize ? "PROVIDER_CANONICALIZED"
                : "PROVIDER_UNCANONICALIZED", record);
        out.putString(RuntimeKeys.URI, result == null ? "" : result.toString());
        return out;
    }

    private Bundle getProviderTypeAnonymous(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        String mimeType;
        try {
            java.lang.reflect.Method method = record.provider.getClass().getMethod(
                    "getTypeAnonymous", Uri.class);
            mimeType = (String) method.invoke(record.provider, uri);
        } catch (NoSuchMethodException unavailableOnLegacyApi) {
            mimeType = record.provider.getType(uri);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("PROVIDER_TYPE_ANONYMOUS_FAILED", cause);
        }
        Bundle out = providerResult("PROVIDER_TYPE_ANONYMOUS", record);
        out.putString("mimeType", mimeType);
        out.putString(RuntimeKeys.URI, uri.toString());
        return out;
    }

    private Bundle getProviderStreamTypes(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        String filter = required(request, RuntimeKeys.PROVIDER_MIME_TYPE);
        String[] types;
        try {
            java.lang.reflect.Method method = record.provider.getClass().getMethod(
                    "getStreamTypes", Uri.class, String.class);
            types = (String[]) method.invoke(record.provider, uri, filter);
        } catch (NoSuchMethodException unavailableOnLegacyApi) {
            types = null;
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("PROVIDER_STREAM_TYPES_FAILED", cause);
        }
        Bundle out = providerResult("PROVIDER_STREAM_TYPES", record);
        if (types != null) out.putStringArray(RuntimeKeys.PROVIDER_STREAM_TYPES, types.clone());
        out.putString(RuntimeKeys.PROVIDER_MIME_TYPE, filter);
        out.putString(RuntimeKeys.URI, uri.toString());
        return out;
    }

    private Bundle refreshProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        Bundle extras = request.getBundle(RuntimeKeys.PROVIDER_EXTRAS);
        android.os.CancellationSignal cancellationSignal = new android.os.CancellationSignal();
        IProviderQueryCancellation cancellationChannel = queryCancellationChannel(request);
        IProviderQueryCancellation cancellationEndpoint = new IProviderQueryCancellation.Stub() {
            @Override public void attach(IProviderQueryCancellation ignored) { }
            @Override public void cancel() { cancellationSignal.cancel(); }
            @Override public void detach() { }
        };
        if (cancellationChannel != null) cancellationChannel.attach(cancellationEndpoint);
        boolean refreshed;
        try {
            cancellationSignal.throwIfCanceled();
            java.lang.reflect.Method method = record.provider.getClass().getMethod("refresh",
                    Uri.class, Bundle.class, android.os.CancellationSignal.class);
            refreshed = (Boolean) method.invoke(record.provider, uri,
                    extras == null ? null : new Bundle(extras), cancellationSignal);
            cancellationSignal.throwIfCanceled();
        } catch (NoSuchMethodException unavailableOnLegacyApi) {
            refreshed = false;
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("PROVIDER_REFRESH_FAILED", cause);
        } finally {
            if (cancellationChannel != null) {
                try { cancellationChannel.detach(); }
                catch (android.os.RemoteException ignored) { }
            }
        }
        Bundle out = providerResult("PROVIDER_REFRESHED", record);
        out.putBoolean("refreshed", refreshed);
        out.putString(RuntimeKeys.URI, uri.toString());
        return out;
    }

    private static Uri invokeProviderUri(android.content.ContentProvider provider, String methodName,
                                         Uri uri) throws Exception {
        try {
            java.lang.reflect.Method method = provider.getClass().getMethod(methodName, Uri.class);
            return (Uri) method.invoke(provider, uri);
        } catch (NoSuchMethodException unavailableOnLegacyApi) {
            return null;
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("PROVIDER_URI_TRANSFORM_FAILED:" + methodName, cause);
        }
    }

    private Bundle insertProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        Uri inserted = record.provider.insert(uri, contentValues(request.getBundle(RuntimeKeys.PROVIDER_VALUES)));
        Bundle out = providerResult("PROVIDER_INSERTED", record);
        out.putString(RuntimeKeys.URI, inserted == null ? "" : inserted.toString());
        RuntimeEventLog.event("GUEST_PROVIDER_INSERT", out);
        return out;
    }

    private Bundle bulkInsertProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        ProviderBulkInsertRuntime.validate(request);
        int count = request.getInt(RuntimeKeys.PROVIDER_BULK_VALUE_COUNT, 0);
        ContentValues[] values = new ContentValues[count];
        for (int index = 0; index < count; index++) {
            Bundle wire = request.getBundle(RuntimeKeys.PROVIDER_BULK_VALUE_PREFIX + index);
            if (wire == null) throw new IllegalArgumentException("PROVIDER_BULK_VALUE_MISSING:" + index);
            values[index] = contentValues(wire);
        }
        int inserted = record.provider.bulkInsert(uri, values);
        Bundle out = providerResult("PROVIDER_BULK_INSERTED", record);
        out.putInt("affectedRows", inserted);
        RuntimeEventLog.event("GUEST_PROVIDER_BULK_INSERT", out);
        return out;
    }

    private Bundle updateProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        int count = record.provider.update(uri, contentValues(request.getBundle(RuntimeKeys.PROVIDER_VALUES)),
                request.getString(RuntimeKeys.PROVIDER_SELECTION),
                toArray(request.getStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS)));
        Bundle out = providerResult("PROVIDER_UPDATED", record);
        out.putInt("affectedRows", count);
        RuntimeEventLog.event("GUEST_PROVIDER_UPDATE", out);
        return out;
    }

    private Bundle deleteProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        int count = record.provider.delete(uri, request.getString(RuntimeKeys.PROVIDER_SELECTION),
                toArray(request.getStringArrayList(RuntimeKeys.PROVIDER_SELECTION_ARGS)));
        Bundle out = providerResult("PROVIDER_DELETED", record);
        out.putInt("affectedRows", count);
        RuntimeEventLog.event("GUEST_PROVIDER_DELETE", out);
        return out;
    }

    private Bundle callProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        String method = required(request, RuntimeKeys.PROVIDER_METHOD);
        String argument = request.getString(RuntimeKeys.PROVIDER_ARGUMENT);
        Bundle extras = request.getBundle(RuntimeKeys.PROVIDER_EXTRAS);
        Bundle returned = record.provider.call(method, argument,
                extras == null ? null : ProviderCallBundleCodec.copy(extras));
        Bundle out = providerResult("PROVIDER_CALLED", record);
        out.putString(RuntimeKeys.PROVIDER_METHOD, method);
        out.putBundle(RuntimeKeys.PROVIDER_RESULT,
                returned == null ? null : ProviderCallBundleCodec.copy(returned));
        RuntimeEventLog.event("GUEST_PROVIDER_CALL", out);
        return out;
    }

    private Bundle applyBatchProvider(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        try {
            ProviderBatchRuntime.Batch batch = ProviderBatchRuntime.validate(request, record.authority);
            Bundle out = withSessionIdentity(ProviderBatchRuntime.execute(record.provider, batch));
            out.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
            out.putString(ComponentOperations.AUTHORITY, record.authority);
            RuntimeEventLog.event("GUEST_PROVIDER_APPLY_BATCH", out);
            return out;
        } catch (ProviderBatchRuntime.BatchException error) {
            Bundle out = failure(error);
            out.putInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, error.operationIndex());
            RuntimeEventLog.event("GUEST_PROVIDER_APPLY_BATCH_FAILED", out);
            return out;
        }
    }

    private Bundle openProviderFile(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        Bundle out = fileTransport.openFile(record.provider, uri,
                required(request, RuntimeKeys.PROVIDER_FILE_MODE),
                required(request, RuntimeKeys.FILE_TOKEN), session.spec.sessionId, session.spec.generation,
                android.os.SystemClock.elapsedRealtime(),
                request.getLong(RuntimeKeys.FILE_TTL_MS, GuestProviderFileTransport.DEFAULT_LEASE_TTL_MS));
        withSessionIdentity(out);
        attachProviderFileResult(out, record, uri);
        RuntimeEventLog.event("GUEST_PROVIDER_OPEN_FILE", out);
        return out;
    }

    private Bundle openProviderAssetFile(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        Bundle out = fileTransport.openAssetFile(record.provider, uri,
                required(request, RuntimeKeys.PROVIDER_FILE_MODE),
                required(request, RuntimeKeys.FILE_TOKEN), session.spec.sessionId, session.spec.generation,
                android.os.SystemClock.elapsedRealtime(),
                request.getLong(RuntimeKeys.FILE_TTL_MS, GuestProviderFileTransport.DEFAULT_LEASE_TTL_MS));
        withSessionIdentity(out);
        attachProviderFileResult(out, record, uri);
        RuntimeEventLog.event("GUEST_PROVIDER_OPEN_ASSET_FILE", out);
        return out;
    }

    private Bundle openProviderTypedAssetFile(String className, Bundle request) throws Exception {
        ProviderRecord record = requireProvider(className, request);
        Uri uri = Uri.parse(required(request, RuntimeKeys.URI));
        Bundle out = fileTransport.openTypedAssetFile(record.provider, uri,
                required(request, RuntimeKeys.PROVIDER_MIME_TYPE),
                request.getBundle(RuntimeKeys.PROVIDER_FILE_OPTIONS),
                required(request, RuntimeKeys.FILE_TOKEN), session.spec.sessionId, session.spec.generation,
                android.os.SystemClock.elapsedRealtime(),
                request.getLong(RuntimeKeys.FILE_TTL_MS, GuestProviderFileTransport.DEFAULT_LEASE_TTL_MS));
        withSessionIdentity(out);
        attachProviderFileResult(out, record, uri);
        RuntimeEventLog.event("GUEST_PROVIDER_OPEN_TYPED_ASSET_FILE", out);
        return out;
    }

    private static void attachProviderFileResult(Bundle out, ProviderRecord record, Uri uri) {
        out.putString(RuntimeKeys.COMPONENT_CLASS, record.className);
        out.putString(ComponentOperations.AUTHORITY, record.authority);
        out.putString(RuntimeKeys.URI, uri.toString());
    }

    private ProviderRecord requireProvider(String className, Bundle request) throws Exception {
        String authority = request.getString(ComponentOperations.AUTHORITY, "");
        ProviderRecord record;
        synchronized (providerLock) {
            record = authority.trim().isEmpty() ? null : providersByAuthority.get(authority);
            if (record == null && className != null && !className.trim().isEmpty()) {
                record = providersByClass.get(className);
            }
        }
        if (record == null) {
            if (className == null || className.trim().isEmpty()) {
                throw new IllegalArgumentException("Provider class is required");
            }
            if (authority.trim().isEmpty()) {
                throw new IllegalArgumentException("providerAuthority is required");
            }
            session.mainThread.call(() -> prepareProvider(className, authority));
            synchronized (providerLock) { record = providersByAuthority.get(authority); }
        }
        if (record == null) throw new IllegalStateException("PROVIDER_PREPARE_DID_NOT_PUBLISH");
        return record;
    }

    private String providerInstance(ProviderRecord record) {
        return session.spec.virtualUserId + ":" + session.spec.packageName + ":" + record.authority;
    }

    private Bundle providerResult(String status, ProviderRecord record) {
        Bundle out = success(status, record.className);
        out.putString(ComponentOperations.AUTHORITY, record.authority);
        return out;
    }

    private static ContentValues contentValues(Bundle values) {
        ContentValues out = new ContentValues();
        if (values == null) return out;
        for (String key : values.keySet()) {
            Object value = values.get(key);
            if (value == null) out.putNull(key);
            else if (value instanceof String) out.put(key, (String) value);
            else if (value instanceof Integer) out.put(key, (Integer) value);
            else if (value instanceof Long) out.put(key, (Long) value);
            else if (value instanceof Boolean) out.put(key, (Boolean) value);
            else if (value instanceof Float) out.put(key, (Float) value);
            else if (value instanceof Double) out.put(key, (Double) value);
            else if (value instanceof byte[]) out.put(key, (byte[]) value);
            else throw new IllegalArgumentException("Unsupported ContentValues type for " + key);
        }
        return out;
    }

    private static String[] toArray(ArrayList<String> values) {
        return values == null ? null : values.toArray(new String[0]);
    }


    private static void attachBaseContext(ContextWrapper wrapper, Context context) throws Exception {
        Method method = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        method.setAccessible(true);
        method.invoke(wrapper, context);
    }

    private static void setOptionalField(Object target, String name, Object value) {
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
                return;
            }
        }
    }

    private Bundle success(String status, String component) {
        Bundle out = new Bundle();
        // Component results are also the RuntimeEventLog payload. Keep the logical
        // process identity on every legacy/isolated component edge so a callback
        // cannot be mistaken for a host-side generation or an unrelated slot.
        out.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        out.putLong(RuntimeKeys.GENERATION, session.generation());
        out.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        out.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        out.putString(RuntimeKeys.PROCESS_NAME, session.spec.processName);
        out.putString(RuntimeKeys.STATUS, status);
        out.putString(RuntimeKeys.COMPONENT_CLASS, component);
        return out;
    }

    private <T> T withGuestClassLoader(java.util.concurrent.Callable<T> action) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(session.classLoader);
            return action.call();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private Bundle failure(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        Bundle out = withSessionIdentity(new Bundle());
        out.putString(RuntimeKeys.STATUS, "FAILED");
        out.putString(RuntimeKeys.ERROR_TYPE, root.getClass().getName());
        out.putString(RuntimeKeys.ERROR_MESSAGE, String.valueOf(root.getMessage()));
        return out;
    }

    private Bundle withSessionIdentity(Bundle out) {
        if (out == null) out = new Bundle();
        out.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        out.putLong(RuntimeKeys.GENERATION, session.generation());
        out.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        out.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        out.putString(RuntimeKeys.PROCESS_NAME, session.spec.processName);
        return out;
    }

    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static final class ServiceRecord {
        final Service service;
        final Map<String, BoundConnection> connections = new LinkedHashMap<>();
        int startCount;
        int lastStartId;
        Intent lastStartIntent;
        final ForegroundServiceStateMachine foregroundPolicy = new ForegroundServiceStateMachine();
        IBinder lastBinder;
        boolean rebindRequested;
        boolean createdNow;
        ServiceRecord(Service service) { this.service = service; }
    }

    private static final class BoundConnection {
        final String id;
        final Intent intent;
        BoundConnection(String id, Intent intent) { this.id = id; this.intent = intent; }
    }

    private static final class ReceiverRecord {
        final String id;
        final String className;
        final BroadcastReceiver receiver;
        final Handler scheduler;
        final ArrayList<String> actions;
        final boolean exported;
        ReceiverRecord(String id, String className, BroadcastReceiver receiver, Handler scheduler,
                       ArrayList<String> actions, boolean exported) {
            this.id = id;
            this.className = className;
            this.receiver = receiver;
            this.scheduler = scheduler;
            this.actions = actions;
            this.exported = exported;
        }
    }

    private static final class ProviderRecord {
        final String className;
        final String authority;
        final ContentProvider provider;
        ProviderRecord(String className, String authority, ContentProvider provider) {
            this.className = className;
            this.authority = authority;
            this.provider = provider;
        }
    }
}
