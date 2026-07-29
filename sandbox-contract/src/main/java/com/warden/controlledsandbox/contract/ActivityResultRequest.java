package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed Activity Result registration, completion and drain request. */
public final class ActivityResultRequest implements Parcelable {
    public static final String REGISTER = "REGISTER";
    public static final String UNREGISTER = "UNREGISTER";
    public static final String FINISH = "FINISH";
    public static final String DRAIN = "DRAIN";
    public static final String SEND = "SEND";

    public static final Creator<ActivityResultRequest> CREATOR = new Creator<>() {
        @Override public ActivityResultRequest createFromParcel(Parcel source) {
            return new ActivityResultRequest(source.readInt(), source.readString(), source.readString(),
                    source.readLong(), source.readInt(), source.readString(), source.readString(),
                    source.readString(), source.readString(), source.readInt(), source.readInt(),
                    (ActivityResultIntentSnapshot) source.readParcelable(
                            ActivityResultIntentSnapshot.class.getClassLoader()));
        }
        @Override public ActivityResultRequest[] newArray(int size) {
            return new ActivityResultRequest[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;
    private final String sessionId;
    private final long generation;
    private final int virtualUserId;
    private final String packageName;
    private final String operation;
    private final String activityToken;
    private final String registryKey;
    private final int requestCode;
    private final int resultCode;
    private final ActivityResultIntentSnapshot resultIntent;

    public ActivityResultRequest(
            int protocolVersion, String requestId, String sessionId, long generation,
            int virtualUserId, String packageName, String operation, String activityToken,
            String registryKey, int resultCode, ActivityResultIntentSnapshot resultIntent) {
        this(protocolVersion, requestId, sessionId, generation, virtualUserId, packageName,
                operation, activityToken, registryKey, -1, resultCode, resultIntent);
    }

    public ActivityResultRequest(
            int protocolVersion, String requestId, String sessionId, long generation,
            int virtualUserId, String packageName, String operation, String activityToken,
            String registryKey, int requestCode, int resultCode,
            ActivityResultIntentSnapshot resultIntent) {
        if (protocolVersion <= 0 || generation < 1 || virtualUserId < 0) {
            throw new IllegalArgumentException("invalid Activity result request identity");
        }
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
        this.sessionId = ContractChecks.requiredText(sessionId, "sessionId", 128);
        this.generation = generation;
        this.virtualUserId = virtualUserId;
        this.packageName = ContractChecks.requiredText(packageName, "packageName", 255);
        this.operation = requireOperation(operation);
        this.activityToken = ContractChecks.requiredText(activityToken, "activityToken", 128);
        this.registryKey = ContractChecks.optionalText(registryKey, "registryKey", 256);
        this.requestCode = requestCode;
        this.resultCode = resultCode;
        this.resultIntent = resultIntent == null ? ActivityResultIntentSnapshot.empty() : resultIntent;
        if ((REGISTER.equals(this.operation) || UNREGISTER.equals(this.operation))
                && this.registryKey.isEmpty()) {
            throw new IllegalArgumentException("registryKey is required");
        }
        if (SEND.equals(this.operation) && (requestCode < 0 || requestCode > 0xffff)) {
            throw new IllegalArgumentException("requestCode is required for SEND");
        }
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public long generation() { return generation; }
    public int virtualUserId() { return virtualUserId; }
    public String packageName() { return packageName; }
    public String operation() { return operation; }
    public String activityToken() { return activityToken; }
    public String registryKey() { return registryKey; }
    public int requestCode() { return requestCode; }
    public int resultCode() { return resultCode; }
    public ActivityResultIntentSnapshot resultIntent() { return resultIntent; }

    @Override public int describeContents() { return resultIntent.describeContents(); }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion); dest.writeString(requestId); dest.writeString(sessionId);
        dest.writeLong(generation); dest.writeInt(virtualUserId); dest.writeString(packageName);
        dest.writeString(operation); dest.writeString(activityToken); dest.writeString(registryKey);
        dest.writeInt(requestCode); dest.writeInt(resultCode); dest.writeParcelable(resultIntent, flags);
    }

    private static String requireOperation(String operation) {
        String value = ContractChecks.requiredText(operation, "operation", 32);
        if (!REGISTER.equals(value) && !UNREGISTER.equals(value)
                && !FINISH.equals(value) && !DRAIN.equals(value) && !SEND.equals(value)) {
            throw new IllegalArgumentException("unsupported Activity result operation: " + value);
        }
        return value;
    }
}
