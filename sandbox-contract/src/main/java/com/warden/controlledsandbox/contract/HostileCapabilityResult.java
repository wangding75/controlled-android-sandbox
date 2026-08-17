package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/** Broker response. Never includes a host path. */
public final class HostileCapabilityResult implements Parcelable {
    private final boolean successful;
    private final String status;
    private final String body;
    private final String errorType;
    private final ParcelFileDescriptor delegatedFd;

    public HostileCapabilityResult(boolean successful, String status, String body,
            String errorType, ParcelFileDescriptor delegatedFd) {
        this.successful = successful;
        this.status = ContractChecks.requiredText(status, "status", 96);
        this.body = ContractChecks.optionalText(body, "body", 4096);
        this.errorType = ContractChecks.optionalText(errorType, "errorType", 160);
        this.delegatedFd = delegatedFd;
        if (successful && !this.errorType.isEmpty()) {
            throw new IllegalArgumentException("successful capability result cannot contain an error");
        }
        if (!successful && this.delegatedFd != null) {
            throw new IllegalArgumentException("failed capability result cannot carry an FD");
        }
    }

    public static HostileCapabilityResult success(String status, String body) {
        return new HostileCapabilityResult(true, status, body, "", null);
    }

    public static HostileCapabilityResult successFd(String status, ParcelFileDescriptor fd) {
        return new HostileCapabilityResult(true, status, "", "", fd);
    }

    public static HostileCapabilityResult denied(String errorType) {
        return new HostileCapabilityResult(false, "DENIED", "", errorType, null);
    }

    private HostileCapabilityResult(Parcel in) {
        this(in.readInt() != 0, in.readString(), in.readString(), in.readString(),
                in.readInt() != 0 ? in.readParcelable(ParcelFileDescriptor.class.getClassLoader())
                        : null);
    }

    public boolean successful() { return successful; }
    public String status() { return status; }
    public String body() { return body; }
    public String errorType() { return errorType; }
    public ParcelFileDescriptor delegatedFd() { return delegatedFd; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(successful ? 1 : 0);
        out.writeString(status);
        out.writeString(body);
        out.writeString(errorType);
        if (delegatedFd != null) {
            out.writeInt(1);
            out.writeParcelable(delegatedFd, flags);
        } else {
            out.writeInt(0);
        }
    }

    @Override public int describeContents() {
        return delegatedFd != null ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    public static final Creator<HostileCapabilityResult> CREATOR = new Creator<>() {
        @Override public HostileCapabilityResult createFromParcel(Parcel in) {
            return new HostileCapabilityResult(in);
        }

        @Override public HostileCapabilityResult[] newArray(int size) {
            return new HostileCapabilityResult[size];
        }
    };
}
