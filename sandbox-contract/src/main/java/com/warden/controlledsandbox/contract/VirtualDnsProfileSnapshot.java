package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Virtual DNS resolver and private-DNS policy. */
public final class VirtualDnsProfileSnapshot implements Parcelable {
    public static final String PRIVATE_DNS_OFF = "OFF";
    public static final String PRIVATE_DNS_AUTOMATIC = "AUTOMATIC";
    public static final String PRIVATE_DNS_HOSTNAME = "HOSTNAME";
    private final String mode;
    private final List<String> servers;
    private final List<String> searchDomains;
    private final String privateDnsMode;
    private final String privateDnsHostname;
    private final boolean allowRawQueries;
    private final List<VirtualDnsRecordSnapshot> records;

    public VirtualDnsProfileSnapshot(String mode, List<String> servers, List<String> searchDomains,
            String privateDnsMode, String privateDnsHostname, boolean allowRawQueries,
            List<VirtualDnsRecordSnapshot> records) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.servers = VirtualNetworkSnapshot.ipLiterals(servers, "dnsServers", 16);
        this.searchDomains = VirtualNetworkSnapshot.strings(searchDomains, "searchDomains", 16, 253);
        String dnsMode = ContractChecks.requiredText(privateDnsMode, "privateDnsMode", 32)
                .toUpperCase(java.util.Locale.ROOT);
        if (!PRIVATE_DNS_OFF.equals(dnsMode) && !PRIVATE_DNS_AUTOMATIC.equals(dnsMode)
                && !PRIVATE_DNS_HOSTNAME.equals(dnsMode)) {
            throw new IllegalArgumentException("privateDnsMode is invalid");
        }
        this.privateDnsMode = dnsMode;
        this.privateDnsHostname = ContractChecks.optionalText(privateDnsHostname, "privateDnsHostname", 253);
        if (PRIVATE_DNS_HOSTNAME.equals(dnsMode) && this.privateDnsHostname.isEmpty()) {
            throw new IllegalArgumentException("privateDnsHostname is required");
        }
        this.allowRawQueries = allowRawQueries;
        List<VirtualDnsRecordSnapshot> copy = records == null ? List.of() : new ArrayList<>(records);
        if (copy.size() > 256 || copy.contains(null)) throw new IllegalArgumentException("DNS records are invalid");
        this.records = Collections.unmodifiableList(copy);
    }
    private VirtualDnsProfileSnapshot(Parcel in) {
        this(in.readString(), in.createStringArrayList(), in.createStringArrayList(), in.readString(),
                in.readString(), in.readInt() != 0, in.createTypedArrayList(VirtualDnsRecordSnapshot.CREATOR));
    }
    public String mode() { return mode; }
    public List<String> servers() { return servers; }
    public List<String> searchDomains() { return searchDomains; }
    public String privateDnsMode() { return privateDnsMode; }
    public String privateDnsHostname() { return privateDnsHostname; }
    public boolean allowRawQueries() { return allowRawQueries; }
    public List<VirtualDnsRecordSnapshot> records() { return records; }
    public VirtualDnsRecordSnapshot record(String hostname, String type) {
        if (hostname == null || type == null) return null;
        for (VirtualDnsRecordSnapshot record : records) {
            if (record.hostname().equalsIgnoreCase(hostname) && record.type().equalsIgnoreCase(type)) return record;
        }
        return null;
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeStringList(servers); out.writeStringList(searchDomains);
        out.writeString(privateDnsMode); out.writeString(privateDnsHostname);
        out.writeInt(allowRawQueries ? 1 : 0); out.writeTypedList(records);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualDnsProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualDnsProfileSnapshot createFromParcel(Parcel in) { return new VirtualDnsProfileSnapshot(in); }
        @Override public VirtualDnsProfileSnapshot[] newArray(int size) { return new VirtualDnsProfileSnapshot[size]; }
    };
}
