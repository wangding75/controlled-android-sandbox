package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Typed Activity result waiting for Guest delivery. */
public final class ActivityResultSnapshot implements Parcelable {
    public static final Creator<ActivityResultSnapshot> CREATOR = new Creator<>() {
        @Override public ActivityResultSnapshot createFromParcel(Parcel source) {
            return new ActivityResultSnapshot(
                    source.readString(), source.readString(), source.readString(), source.readString(),
                    source.readInt(), source.readInt(), source.readString(), source.readString(),
                    (ActivityResultIntentSnapshot) source.readParcelable(
                            ActivityResultIntentSnapshot.class.getClassLoader()));
        }
        @Override public ActivityResultSnapshot[] newArray(int size) {
            return new ActivityResultSnapshot[size];
        }
    };

    private final String callerActivityToken;
    private final String calleeActivityToken;
    private final String resultWho;
    private final String registryKey;
    private final int requestCode;
    private final int resultCode;
    private final String intentSenderToken;
    private final String dataToken;
    private final ActivityResultIntentSnapshot resultIntent;

    public ActivityResultSnapshot(
            String callerActivityToken, String calleeActivityToken, String resultWho,
            String registryKey, int requestCode, int resultCode, String intentSenderToken,
            String dataToken, ActivityResultIntentSnapshot resultIntent) {
        this.callerActivityToken = ContractChecks.requiredText(
                callerActivityToken, "callerActivityToken", 128);
        this.calleeActivityToken = ContractChecks.requiredText(
                calleeActivityToken, "calleeActivityToken", 128);
        this.resultWho = ContractChecks.optionalText(resultWho, "resultWho", 256);
        this.registryKey = ContractChecks.optionalText(registryKey, "registryKey", 256);
        if (requestCode < 0 || requestCode > 0xffff) {
            throw new IllegalArgumentException("requestCode must be 0..65535");
        }
        this.requestCode = requestCode;
        this.resultCode = resultCode;
        this.intentSenderToken = ContractChecks.optionalText(
                intentSenderToken, "intentSenderToken", 512);
        this.dataToken = ContractChecks.optionalText(dataToken, "dataToken", 512);
        this.resultIntent = resultIntent == null ? ActivityResultIntentSnapshot.empty() : resultIntent;
    }

    public String callerActivityToken() { return callerActivityToken; }
    public String calleeActivityToken() { return calleeActivityToken; }
    public String resultWho() { return resultWho; }
    public String registryKey() { return registryKey; }
    public int requestCode() { return requestCode; }
    public int resultCode() { return resultCode; }
    public String intentSenderToken() { return intentSenderToken; }
    public String dataToken() { return dataToken; }
    public ActivityResultIntentSnapshot resultIntent() { return resultIntent; }

    @Override public int describeContents() { return resultIntent.describeContents(); }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(callerActivityToken);
        dest.writeString(calleeActivityToken);
        dest.writeString(resultWho);
        dest.writeString(registryKey);
        dest.writeInt(requestCode);
        dest.writeInt(resultCode);
        dest.writeString(intentSenderToken);
        dest.writeString(dataToken);
        dest.writeParcelable(resultIntent, flags);
    }
}
