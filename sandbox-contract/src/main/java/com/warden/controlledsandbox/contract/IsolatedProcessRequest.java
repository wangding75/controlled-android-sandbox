package com.warden.controlledsandbox.contract;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Typed top-level contract for one capability-scoped isolated Service operation.
 *
 * <p>The opaque payload remains the legacy component transport, but the route identity,
 * generation, slot and capability token are outside that Bundle and are validated before the
 * isolated worker can touch Guest runtime state.</p>
 */
public final class IsolatedProcessRequest implements Parcelable {
    private final int protocol;
    private final String sessionId;
    private final long generation;
    private final int processSlot;
    private final int virtualUserId;
    private final String packageName;
    private final String processName;
    private final String componentClass;
    private final String packageRevision;
    private final String operation;
    private final String capabilityToken;
    private final Bundle payload;

    public IsolatedProcessRequest(int protocol, String sessionId, long generation,
            int processSlot, int virtualUserId, String packageName, String processName,
            String componentClass, String packageRevision, String operation,
            String capabilityToken, Bundle payload) {
        if (protocol < 1) throw new IllegalArgumentException("protocol must be positive");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.protocol = protocol;
        this.sessionId = ContractChecks.requiredText(sessionId, "sessionId", 128);
        this.generation = generation;
        this.processSlot = bounded(processSlot, "processSlot", 0,
                ProcessSlotContract.MAX_ORDINARY_SLOT);
        this.virtualUserId = ContractChecks.nonNegative(virtualUserId, "virtualUserId");
        this.packageName = packageName(packageName);
        this.processName = ContractChecks.requiredText(processName, "processName", 320);
        this.componentClass = ContractChecks.requiredText(componentClass, "componentClass", 512);
        this.packageRevision = ContractChecks.requiredText(packageRevision, "packageRevision", 192);
        this.operation = serviceOperation(operation);
        this.capabilityToken = ContractChecks.requiredText(capabilityToken, "capabilityToken", 192);
        this.payload = payload == null ? new Bundle() : new Bundle(payload);
        this.payload.setClassLoader(IsolatedProcessRequest.class.getClassLoader());
    }

    private IsolatedProcessRequest(Parcel in) {
        this(in.readInt(), in.readString(), in.readLong(), in.readInt(), in.readInt(),
                in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readParcelable(
                        IsolatedProcessRequest.class.getClassLoader()));
    }

    public int protocol() { return protocol; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public int processSlot() { return processSlot; }
    public int virtualUserId() { return virtualUserId; }
    public String packageName() { return packageName; }
    public String processName() { return processName; }
    public String componentClass() { return componentClass; }
    public String packageRevision() { return packageRevision; }
    public String operation() { return operation; }
    public String capabilityToken() { return capabilityToken; }
    public Bundle payload() {
        return new Bundle(payload);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(protocol);
        out.writeString(sessionId);
        out.writeLong(generation);
        out.writeInt(processSlot);
        out.writeInt(virtualUserId);
        out.writeString(packageName);
        out.writeString(processName);
        out.writeString(componentClass);
        out.writeString(packageRevision);
        out.writeString(operation);
        out.writeString(capabilityToken);
        out.writeParcelable(payload, flags);
    }

    @Override public int describeContents() { return Parcelable.CONTENTS_FILE_DESCRIPTOR; }

    public static final Creator<IsolatedProcessRequest> CREATOR = new Creator<>() {
        @Override public IsolatedProcessRequest createFromParcel(Parcel source) {
            return new IsolatedProcessRequest(source);
        }
        @Override public IsolatedProcessRequest[] newArray(int size) {
            return new IsolatedProcessRequest[size];
        }
    };

    private static int bounded(int value, String name, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside " + minimum + ".." + maximum);
        }
        return value;
    }

    private static String packageName(String value) {
        String normalized = ContractChecks.requiredText(value, "packageName", 255);
        if (!normalized.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("packageName is invalid");
        }
        return normalized;
    }

    private static String serviceOperation(String value) {
        String normalized = ContractChecks.requiredText(value, "operation", 64);
        switch (normalized) {
            case "PREPARE_ISOLATED_SERVICE":
            case "START_SERVICE":
            case "START_FOREGROUND_SERVICE":
            case "STOP_SERVICE":
            case "STOP_SERVICE_START_ID":
            case "SET_SERVICE_FOREGROUND":
            case "BIND_SERVICE":
            case "UNBIND_SERVICE":
            case "ROUTE_FRAMEWORK_SERVICE":
            case "FRAMEWORK_SERVICE_EVENT":
            case "RECOVER_FRAMEWORK_SERVICE":
            case "STATUS_ISOLATED_SERVICE":
                return normalized;
            default:
                throw new IllegalArgumentException("unsupported isolated Service operation: " + normalized);
        }
    }
}
