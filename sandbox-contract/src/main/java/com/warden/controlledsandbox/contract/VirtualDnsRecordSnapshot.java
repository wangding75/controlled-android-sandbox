package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Bounded deterministic DNS answer. */
public final class VirtualDnsRecordSnapshot implements Parcelable {
    public static final String A = "A";
    public static final String AAAA = "AAAA";
    public static final String CNAME = "CNAME";
    private final String hostname;
    private final String type;
    private final List<String> values;
    private final int ttlSeconds;

    public VirtualDnsRecordSnapshot(String hostname, String type, List<String> values, int ttlSeconds) {
        this.hostname = ContractChecks.requiredText(hostname, "hostname", 253).toLowerCase(java.util.Locale.ROOT);
        String normalized = ContractChecks.requiredText(type, "dnsType", 16).toUpperCase(java.util.Locale.ROOT);
        if (!A.equals(normalized) && !AAAA.equals(normalized) && !CNAME.equals(normalized)) {
            throw new IllegalArgumentException("dnsType is invalid");
        }
        this.type = normalized;
        this.values = VirtualNetworkSnapshot.strings(values, "dnsValues", 16, 253);
        if (this.values.isEmpty() || ttlSeconds < 0 || ttlSeconds > 604_800) {
            throw new IllegalArgumentException("DNS answer is invalid");
        }
        this.ttlSeconds = ttlSeconds;
    }
    private VirtualDnsRecordSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.createStringArrayList(), in.readInt());
    }
    public String hostname() { return hostname; }
    public String type() { return type; }
    public List<String> values() { return values; }
    public int ttlSeconds() { return ttlSeconds; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(hostname); out.writeString(type); out.writeStringList(values); out.writeInt(ttlSeconds);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualDnsRecordSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualDnsRecordSnapshot createFromParcel(Parcel in) { return new VirtualDnsRecordSnapshot(in); }
        @Override public VirtualDnsRecordSnapshot[] newArray(int size) { return new VirtualDnsRecordSnapshot[size]; }
    };
}
