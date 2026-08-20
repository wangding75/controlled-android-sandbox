package com.warden.controlledsandbox.framework.binder;

import android.os.IBinder;
import android.os.Parcel;

import java.util.Objects;

/** Immutable metadata view over one Binder transaction and its live Parcel pair. */
public final class BinderTransaction {
    /** Android's IBinder.FLAG_ONEWAY. Kept local for compact/static API stubs. */
    public static final int FLAG_ONEWAY = 0x00000001;

    private final String descriptor;
    private final String serviceName;
    private final int code;
    private final int flags;
    private final Parcel input;
    private final Parcel output;
    private final BinderIdentity identity;

    public BinderTransaction(
            String descriptor,
            String serviceName,
            int code,
            Parcel input,
            Parcel output,
            int flags,
            BinderIdentity identity) {
        this.descriptor = required(descriptor, "descriptor");
        this.serviceName = serviceName == null ? "" : serviceName.trim();
        this.code = code;
        this.input = Objects.requireNonNull(input, "input");
        this.output = output;
        this.flags = flags;
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public String descriptor() { return descriptor; }
    public String serviceName() { return serviceName; }
    public int code() { return code; }
    public Parcel input() { return input; }
    public Parcel output() { return output; }
    public int flags() { return flags; }
    public boolean oneWay() { return (flags & FLAG_ONEWAY) != 0; }
    public boolean expectsReply() { return !oneWay() && output != null; }
    public int inputSize() { return input.dataSize(); }
    public int outputSize() { return output == null ? 0 : output.dataSize(); }
    public BinderIdentity identity() { return identity; }

    /** True for Android's descriptor discovery transaction. */
    public boolean isInterfaceDescriptorQuery() {
        return code == IBinder.INTERFACE_TRANSACTION;
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
