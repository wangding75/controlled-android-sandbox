package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Stable, typed error payload for Binder contracts. */
public final class SandboxError implements Parcelable {
    public static final Creator<SandboxError> CREATOR = new Creator<>() {
        @Override public SandboxError createFromParcel(Parcel source) {
            return new SandboxError(source.readString(), source.readString(), source.readInt() != 0);
        }

        @Override public SandboxError[] newArray(int size) {
            return new SandboxError[size];
        }
    };

    private final String code;
    private final String message;
    private final boolean retryable;

    public SandboxError(String code, String message, boolean retryable) {
        this.code = ContractChecks.requiredText(code, "error.code", 64);
        this.message = ContractChecks.optionalText(message, "error.message", 512);
        this.retryable = retryable;
    }

    public String code() { return code; }
    public String message() { return message; }
    public boolean retryable() { return retryable; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(code);
        dest.writeString(message);
        dest.writeInt(retryable ? 1 : 0);
    }
}
