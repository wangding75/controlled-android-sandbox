package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Durable virtual AppWidget allocation/binding record. */
public final class VirtualWidgetSnapshot implements Parcelable {
    private final int appWidgetId;
    private final int hostId;
    private final String providerPackage;
    private final String providerClass;
    private final boolean bound;
    private final List<String> optionKeys;
    private final List<String> optionValues;
    private final byte[] remoteViewsPayload;
    private final long updatedAtMs;

    public VirtualWidgetSnapshot(int appWidgetId, int hostId, String providerPackage,
            String providerClass, boolean bound, List<String> optionKeys, List<String> optionValues,
            byte[] remoteViewsPayload, long updatedAtMs) {
        if (appWidgetId < 1 || hostId < 0) throw new IllegalArgumentException("appWidget identity is invalid");
        this.appWidgetId = appWidgetId; this.hostId = hostId;
        this.providerPackage = ContractChecks.optionalText(providerPackage, "widgetProviderPackage", 255);
        this.providerClass = ContractChecks.optionalText(providerClass, "widgetProviderClass", 255);
        this.bound = bound;
        if (bound && (this.providerPackage.isEmpty() || this.providerClass.isEmpty())) {
            throw new IllegalArgumentException("bound widget requires provider");
        }
        this.optionKeys = VirtualUserProfileSnapshot.strings(optionKeys, "widgetOptionKeys", 64, 128);
        this.optionValues = VirtualUserProfileSnapshot.strings(optionValues, "widgetOptionValues", 64, 2048);
        if (this.optionKeys.size() != this.optionValues.size()) {
            throw new IllegalArgumentException("widget options are misaligned");
        }
        byte[] payload = remoteViewsPayload == null ? new byte[0] : remoteViewsPayload.clone();
        if (payload.length > 512 * 1024) throw new IllegalArgumentException("remoteViewsPayload is too large");
        this.remoteViewsPayload = payload;
        this.updatedAtMs = ContractChecks.nonNegative(updatedAtMs, "widgetUpdatedAtMs");
    }
    private VirtualWidgetSnapshot(Parcel in) {
        this(in.readInt(), in.readInt(), in.readString(), in.readString(), in.readInt() != 0,
                in.createStringArrayList(), in.createStringArrayList(), in.createByteArray(), in.readLong());
    }
    public int appWidgetId() { return appWidgetId; }
    public int hostId() { return hostId; }
    public String providerPackage() { return providerPackage; }
    public String providerClass() { return providerClass; }
    public boolean bound() { return bound; }
    public List<String> optionKeys() { return optionKeys; }
    public List<String> optionValues() { return optionValues; }
    public byte[] remoteViewsPayload() { return remoteViewsPayload.clone(); }
    public long updatedAtMs() { return updatedAtMs; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(appWidgetId); out.writeInt(hostId); out.writeString(providerPackage); out.writeString(providerClass);
        out.writeInt(bound ? 1 : 0); out.writeStringList(optionKeys); out.writeStringList(optionValues);
        out.writeByteArray(remoteViewsPayload); out.writeLong(updatedAtMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualWidgetSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualWidgetSnapshot createFromParcel(Parcel in) { return new VirtualWidgetSnapshot(in); }
        @Override public VirtualWidgetSnapshot[] newArray(int size) { return new VirtualWidgetSnapshot[size]; }
    };
}
