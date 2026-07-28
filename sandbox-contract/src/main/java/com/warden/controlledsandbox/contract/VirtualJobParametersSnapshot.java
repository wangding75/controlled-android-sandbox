package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Version-neutral subset of host JobParameters safe to deliver to a Guest runtime. */
public final class VirtualJobParametersSnapshot implements Parcelable {
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    private static final int MAX_LIST_ITEMS = 128;
    private static final int MAX_STRING_CHARS = 4096;
    private static final int MAX_LIST_CHARS = 64 * 1024;
    private final int hostJobId;
    private final int guestJobId;
    private final String namespace;
    private final byte[] extras;
    private final byte[] transientExtras;
    private final byte[] clipData;
    private final int clipGrantFlags;
    private final boolean overrideDeadlineExpired;
    private final boolean expedited;
    private final boolean userInitiated;
    private final List<String> triggeredUris;
    private final List<String> triggeredAuthorities;
    private final byte[] network;
    private final int stopReason;
    private final int internalStopReason;
    private final String debugStopReason;
    private final long dispatchToken;

    public VirtualJobParametersSnapshot(int hostJobId, int guestJobId, String namespace,
            byte[] extras, byte[] transientExtras, byte[] clipData, int clipGrantFlags,
            boolean overrideDeadlineExpired, boolean expedited, boolean userInitiated,
            List<String> triggeredUris, List<String> triggeredAuthorities, byte[] network,
            int stopReason, int internalStopReason, String debugStopReason, long dispatchToken) {
        if (hostJobId < 0 || guestJobId < -1 || dispatchToken < 0L) {
            throw new IllegalArgumentException("invalid virtual job parameter identity");
        }
        this.hostJobId = hostJobId;
        this.guestJobId = guestJobId;
        this.namespace = safe(namespace);
        this.extras = payload(extras, "extras");
        this.transientExtras = payload(transientExtras, "transientExtras");
        this.clipData = payload(clipData, "clipData");
        this.clipGrantFlags = clipGrantFlags;
        this.overrideDeadlineExpired = overrideDeadlineExpired;
        this.expedited = expedited;
        this.userInitiated = userInitiated;
        this.triggeredUris = strings(triggeredUris, "triggeredUris");
        this.triggeredAuthorities = strings(triggeredAuthorities, "triggeredAuthorities");
        this.network = payload(network, "network");
        this.stopReason = stopReason;
        this.internalStopReason = internalStopReason;
        this.debugStopReason = safe(debugStopReason);
        this.dispatchToken = dispatchToken;
    }

    private VirtualJobParametersSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readString(), in.createByteArray(), in.createByteArray(),
                in.createByteArray(), in.readInt(), in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.createStringArrayList(), in.createStringArrayList(),
                in.createByteArray(), in.readInt(), in.readInt(), in.readString(), in.readLong());
    }

    public int hostJobId() { return hostJobId; }
    public int guestJobId() { return guestJobId; }
    public String namespace() { return namespace; }
    public byte[] extras() { return extras.clone(); }
    public byte[] transientExtras() { return transientExtras.clone(); }
    public byte[] clipData() { return clipData.clone(); }
    public int clipGrantFlags() { return clipGrantFlags; }
    public boolean overrideDeadlineExpired() { return overrideDeadlineExpired; }
    public boolean expedited() { return expedited; }
    public boolean userInitiated() { return userInitiated; }
    public List<String> triggeredUris() { return triggeredUris; }
    public List<String> triggeredAuthorities() { return triggeredAuthorities; }
    public byte[] network() { return network.clone(); }
    public int stopReason() { return stopReason; }
    public int internalStopReason() { return internalStopReason; }
    public String debugStopReason() { return debugStopReason; }
    public long dispatchToken() { return dispatchToken; }

    public VirtualJobParametersSnapshot forGuest(int value, long token) {
        return new VirtualJobParametersSnapshot(hostJobId, value, namespace, extras, transientExtras,
                clipData, clipGrantFlags, overrideDeadlineExpired, expedited, userInitiated,
                triggeredUris, triggeredAuthorities, network, stopReason, internalStopReason,
                debugStopReason, token);
    }

    public VirtualJobParametersSnapshot withStopReason(int reason, int internalReason, String debugReason) {
        return new VirtualJobParametersSnapshot(hostJobId, guestJobId, namespace, extras,
                transientExtras, clipData, clipGrantFlags, overrideDeadlineExpired, expedited,
                userInitiated, triggeredUris, triggeredAuthorities, network, reason,
                internalReason, debugReason, dispatchToken);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(hostJobId); out.writeInt(guestJobId); out.writeString(namespace);
        out.writeByteArray(extras); out.writeByteArray(transientExtras); out.writeByteArray(clipData);
        out.writeInt(clipGrantFlags); out.writeInt(overrideDeadlineExpired ? 1 : 0);
        out.writeInt(expedited ? 1 : 0); out.writeInt(userInitiated ? 1 : 0);
        out.writeStringList(triggeredUris); out.writeStringList(triggeredAuthorities);
        out.writeByteArray(network); out.writeInt(stopReason); out.writeInt(internalStopReason);
        out.writeString(debugStopReason); out.writeLong(dispatchToken);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualJobParametersSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualJobParametersSnapshot createFromParcel(Parcel in) {
            return new VirtualJobParametersSnapshot(in);
        }
        @Override public VirtualJobParametersSnapshot[] newArray(int size) {
            return new VirtualJobParametersSnapshot[size];
        }
    };

    private static byte[] payload(byte[] value, String name) {
        byte[] copy = value == null ? new byte[0] : value.clone();
        if (copy.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException(name + " too large");
        return copy;
    }
    private static List<String> strings(List<String> values, String name) {
        List<String> out = new ArrayList<>();
        int characters = 0;
        if (values != null) for (String value : values) {
            if (out.size() >= MAX_LIST_ITEMS) throw new IllegalArgumentException(name + " too large");
            String normalized = safe(value);
            characters += normalized.length();
            if (characters > MAX_LIST_CHARS) throw new IllegalArgumentException(name + " too large");
            out.add(normalized);
        }
        return Collections.unmodifiableList(out);
    }
    private static String safe(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_STRING_CHARS) throw new IllegalArgumentException("string too large");
        return normalized;
    }
}
