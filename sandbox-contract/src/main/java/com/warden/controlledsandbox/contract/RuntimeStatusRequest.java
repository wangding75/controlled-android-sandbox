package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Versioned request for the typed runtime-status Binder path. */
public final class RuntimeStatusRequest implements Parcelable {
    public static final Creator<RuntimeStatusRequest> CREATOR = new Creator<>() {
        @Override public RuntimeStatusRequest createFromParcel(Parcel source) {
            return new RuntimeStatusRequest(source.readInt(), source.readString());
        }

        @Override public RuntimeStatusRequest[] newArray(int size) {
            return new RuntimeStatusRequest[size];
        }
    };

    private final int protocolVersion;
    private final String requestId;

    public RuntimeStatusRequest(int protocolVersion, String requestId) {
        if (protocolVersion <= 0) throw new IllegalArgumentException("protocolVersion must be positive");
        this.protocolVersion = protocolVersion;
        this.requestId = ContractChecks.requiredText(requestId, "requestId", 128);
    }

    public int protocolVersion() { return protocolVersion; }
    public String requestId() { return requestId; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion);
        dest.writeString(requestId);
    }
}
