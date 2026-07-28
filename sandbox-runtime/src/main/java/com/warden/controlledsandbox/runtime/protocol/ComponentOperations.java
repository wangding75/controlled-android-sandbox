package com.warden.controlledsandbox.runtime.protocol;

public final class ComponentOperations {
    public static final String OPERATION = "componentOperation";
    public static final String ACTION = "intentAction";
    public static final String AUTHORITY = "providerAuthority";
    public static final String START_SERVICE = "START_SERVICE";
    public static final String START_FOREGROUND_SERVICE = "START_FOREGROUND_SERVICE";
    public static final String STOP_SERVICE = "STOP_SERVICE";
    public static final String STOP_SERVICE_START_ID = "STOP_SERVICE_START_ID";
    public static final String SET_SERVICE_FOREGROUND = "SET_SERVICE_FOREGROUND";
    public static final String BIND_SERVICE = "BIND_SERVICE";
    public static final String UNBIND_SERVICE = "UNBIND_SERVICE";
    public static final String REGISTER_RECEIVER = "REGISTER_RECEIVER";
    public static final String UNREGISTER_RECEIVER = "UNREGISTER_RECEIVER";
    public static final String SEND_BROADCAST = "SEND_BROADCAST";
    public static final String SEND_IMPLICIT_BROADCAST = "SEND_IMPLICIT_BROADCAST";
    public static final String SEND_ORDERED_BROADCAST = "SEND_ORDERED_BROADCAST";
    public static final String PREPARE_PROVIDER = "PREPARE_PROVIDER";
    public static final String PROVIDER_QUERY = "PROVIDER_QUERY";
    public static final String PROVIDER_CURSOR_PAGE = "PROVIDER_CURSOR_PAGE";
    public static final String PROVIDER_CURSOR_CLOSE = "PROVIDER_CURSOR_CLOSE";
    public static final String PROVIDER_CURSOR_CANCEL = "PROVIDER_CURSOR_CANCEL";
    public static final String PROVIDER_GET_TYPE = "PROVIDER_GET_TYPE";
    public static final String PROVIDER_INSERT = "PROVIDER_INSERT";
    public static final String PROVIDER_UPDATE = "PROVIDER_UPDATE";
    public static final String PROVIDER_DELETE = "PROVIDER_DELETE";
    public static final String PROVIDER_CALL = "PROVIDER_CALL";
    public static final String PROVIDER_APPLY_BATCH = "PROVIDER_APPLY_BATCH";
    public static final String PROVIDER_OPEN_FILE = "PROVIDER_OPEN_FILE";
    public static final String PROVIDER_OPEN_ASSET_FILE = "PROVIDER_OPEN_ASSET_FILE";
    public static final String PROVIDER_OPEN_TYPED_ASSET_FILE = "PROVIDER_OPEN_TYPED_ASSET_FILE";
    public static final String PROVIDER_FILE_CLOSE = "PROVIDER_FILE_CLOSE";
    public static final String PROVIDER_OBSERVER_REGISTER = "PROVIDER_OBSERVER_REGISTER";
    public static final String PROVIDER_OBSERVER_UNREGISTER = "PROVIDER_OBSERVER_UNREGISTER";
    public static final String PROVIDER_NOTIFY_CHANGE = "PROVIDER_NOTIFY_CHANGE";

    private ComponentOperations() { }

    public static boolean isServiceOperation(String operation) {
        return START_SERVICE.equals(operation)
                || START_FOREGROUND_SERVICE.equals(operation)
                || STOP_SERVICE.equals(operation)
                || STOP_SERVICE_START_ID.equals(operation)
                || SET_SERVICE_FOREGROUND.equals(operation)
                || BIND_SERVICE.equals(operation)
                || UNBIND_SERVICE.equals(operation);
    }

    public static void requireKnownServiceOperation(String operation) {
        if (operation != null && operation.contains("SERVICE") && !isServiceOperation(operation)) {
            throw new IllegalArgumentException("Unknown Service operation: " + operation);
        }
    }

    public static boolean isProviderOperation(String operation) {
        return PREPARE_PROVIDER.equals(operation)
                || isProviderTransactionOperation(operation)
                || PROVIDER_CURSOR_PAGE.equals(operation)
                || PROVIDER_CURSOR_CLOSE.equals(operation)
                || PROVIDER_CURSOR_CANCEL.equals(operation)
                || isProviderFileLeaseOperation(operation)
                || isProviderObserverOperation(operation);
    }

    public static void requireKnownProviderOperation(String operation) {
        if (operation != null && operation.startsWith("PROVIDER_") && !isProviderOperation(operation)) {
            throw new IllegalArgumentException("Unknown Provider operation: " + operation);
        }
    }

    public static boolean isProviderTransactionOperation(String operation) {
        return PROVIDER_QUERY.equals(operation)
                || PROVIDER_GET_TYPE.equals(operation)
                || PROVIDER_INSERT.equals(operation)
                || PROVIDER_UPDATE.equals(operation)
                || PROVIDER_DELETE.equals(operation)
                || PROVIDER_CALL.equals(operation)
                || PROVIDER_APPLY_BATCH.equals(operation)
                || PROVIDER_OBSERVER_REGISTER.equals(operation)
                || isProviderFileOpenOperation(operation);
    }

    public static boolean isProviderFileOpenOperation(String operation) {
        return PROVIDER_OPEN_FILE.equals(operation)
                || PROVIDER_OPEN_ASSET_FILE.equals(operation)
                || PROVIDER_OPEN_TYPED_ASSET_FILE.equals(operation);
    }

    public static boolean isProviderFileLeaseOperation(String operation) {
        return PROVIDER_FILE_CLOSE.equals(operation);
    }

    public static boolean isProviderObserverOperation(String operation) {
        return PROVIDER_OBSERVER_REGISTER.equals(operation)
                || PROVIDER_OBSERVER_UNREGISTER.equals(operation)
                || PROVIDER_NOTIFY_CHANGE.equals(operation);
    }

    public static boolean requiresProviderRead(String operation) {
        return PROVIDER_QUERY.equals(operation) || PROVIDER_GET_TYPE.equals(operation)
                || PROVIDER_CURSOR_PAGE.equals(operation) || PROVIDER_CURSOR_CLOSE.equals(operation)
                || PROVIDER_CURSOR_CANCEL.equals(operation)
                || PROVIDER_OPEN_TYPED_ASSET_FILE.equals(operation);
    }

    public static boolean requiresProviderWrite(String operation) {
        return PROVIDER_INSERT.equals(operation) || PROVIDER_UPDATE.equals(operation)
                || PROVIDER_DELETE.equals(operation) || PROVIDER_CALL.equals(operation);
    }
}
