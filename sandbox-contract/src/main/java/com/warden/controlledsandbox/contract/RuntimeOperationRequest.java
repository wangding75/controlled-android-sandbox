package com.warden.controlledsandbox.contract;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Set;

/** Versioned top-level envelope for runtime operations that still carry legacy component payloads. */
public final class RuntimeOperationRequest implements Parcelable {
    public static final String PREPARE_GUEST = "PREPARE_GUEST";
    public static final String LAUNCH_ACTIVITY = "LAUNCH_ACTIVITY";
    public static final String INVOKE_COMPONENT = "INVOKE_COMPONENT";
    public static final String GRANT_URI_PERMISSION = "GRANT_URI_PERMISSION";
    public static final String REVOKE_URI_PERMISSION = "REVOKE_URI_PERMISSION";
    public static final String CONSUME_ROUTE = "CONSUME_ROUTE";
    public static final String ACTIVITY_EVENT = "ACTIVITY_EVENT";
    public static final String SESSION_STATUS = "SESSION_STATUS";
    public static final String GUEST_RUNTIME_STATUS = "GUEST_RUNTIME_STATUS";

    private static final Set<String> OPERATIONS = Set.of(
            PREPARE_GUEST, LAUNCH_ACTIVITY, INVOKE_COMPONENT,
            GRANT_URI_PERMISSION, REVOKE_URI_PERMISSION, CONSUME_ROUTE,
            ACTIVITY_EVENT, SESSION_STATUS, GUEST_RUNTIME_STATUS);

    public static final Creator<RuntimeOperationRequest> CREATOR = new Creator<>() {
        @Override public RuntimeOperationRequest createFromParcel(Parcel source) {
            return new RuntimeOperationRequest(
                    source.readInt(), source.readString(), source.readString(),
                    source.readString(), source.readInt(), source.readString(),
                    source.readLong(), source.readParcelable(RuntimeOperationRequest.class.getClassLoader()));
        }

        @Override public RuntimeOperationRequest[] newArray(int size) {
            return new RuntimeOperationRequest[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;
    private final String operation;
    private final String packageName;
    private final int virtualUserId;
    private final String sessionId;
    private final long generation;
    private final Bundle payload;

    public RuntimeOperationRequest(int protocolVersion, String requestId, String operation,
            String packageName, int virtualUserId, String sessionId, long generation,
            Bundle payload) {
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        String normalizedOperation = ContractChecks.requiredText(operation, "operation", 64);
        if (!OPERATIONS.contains(normalizedOperation)) {
            throw new IllegalArgumentException("unsupported runtime operation: " + normalizedOperation);
        }
        if (virtualUserId < -1) {
            throw new IllegalArgumentException("virtualUserId must be -1 or non-negative");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        String normalizedPackage = ContractChecks.optionalText(packageName, "packageName", 255);
        if (!normalizedPackage.isEmpty()
                && !normalizedPackage.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("packageName is invalid");
        }
        String normalizedSession = ContractChecks.optionalText(sessionId, "sessionId", 128);
        if (!normalizedSession.isEmpty() && generation < 1) {
            throw new IllegalArgumentException("session generation must be positive");
        }
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
        this.operation = normalizedOperation;
        this.packageName = normalizedPackage;
        this.virtualUserId = virtualUserId;
        this.sessionId = normalizedSession;
        this.generation = generation;
        this.payload = copyPayload(payload);
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }
    public String operation() { return operation; }
    public String packageName() { return packageName; }
    public int virtualUserId() { return virtualUserId; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public Bundle payload() { return copyPayload(payload); }

    /**
     * Runtime-operation payloads cross Binder and may contain contract Parcelables.  A Bundle
     * decoded with the boot loader cannot restore those classes in a secondary app process.
     */
    private static Bundle copyPayload(Bundle source) {
        ClassLoader loader = RuntimeOperationRequest.class.getClassLoader();
        if (source == null) {
            Bundle empty = new Bundle();
            empty.setClassLoader(loader);
            return empty;
        }
        // On API 32 a Binder-restored Bundle may still carry the boot class loader.  Set the
        // contract loader before copying; copying first can eagerly unparcel custom Parcelables.
        source.setClassLoader(loader);
        Bundle copy = new Bundle(source);
        copy.setClassLoader(loader);
        return copy;
    }

    @Override public int describeContents() { return payload.describeContents(); }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion);
        dest.writeString(requestId);
        dest.writeString(operation);
        dest.writeString(packageName);
        dest.writeInt(virtualUserId);
        dest.writeString(sessionId);
        dest.writeLong(generation);
        dest.writeParcelable(payload, flags);
    }
}
