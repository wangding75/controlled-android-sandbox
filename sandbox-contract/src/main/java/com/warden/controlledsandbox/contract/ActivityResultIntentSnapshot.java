package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed and bounded transport snapshot of an Activity result Intent. */
public final class ActivityResultIntentSnapshot implements Parcelable {
    public static final Creator<ActivityResultIntentSnapshot> CREATOR = new Creator<>() {
        @Override public ActivityResultIntentSnapshot createFromParcel(Parcel source) {
            ArrayList<String> keys = source.createStringArrayList();
            ArrayList<String> values = source.createStringArrayList();
            return new ActivityResultIntentSnapshot(
                    source.readString(), source.readString(), source.readString(),
                    source.readString(), source.readInt(), source.readString(), keys, values);
        }
        @Override public ActivityResultIntentSnapshot[] newArray(int size) {
            return new ActivityResultIntentSnapshot[size];
        }
    };

    private final String action;
    private final String dataUri;
    private final String mimeType;
    private final String componentName;
    private final int flags;
    private final String clipDescription;
    private final List<String> extraKeys;
    private final List<String> extraValues;

    public ActivityResultIntentSnapshot(
            String action,
            String dataUri,
            String mimeType,
            String componentName,
            int flags,
            String clipDescription,
            List<String> extraKeys,
            List<String> extraValues) {
        this.action = ContractChecks.optionalText(action, "action", 1024);
        this.dataUri = ContractChecks.optionalText(dataUri, "dataUri", 4096);
        this.mimeType = ContractChecks.optionalText(mimeType, "mimeType", 255);
        this.componentName = ContractChecks.optionalText(componentName, "componentName", 512);
        this.flags = flags;
        this.clipDescription = ContractChecks.optionalText(clipDescription, "clipDescription", 1024);
        this.extraKeys = List.copyOf(extraKeys == null ? List.of() : extraKeys);
        this.extraValues = List.copyOf(extraValues == null ? List.of() : extraValues);
        if (this.extraKeys.size() != this.extraValues.size() || this.extraKeys.size() > 64) {
            throw new IllegalArgumentException("Activity result extra lists are invalid");
        }
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (int index = 0; index < this.extraKeys.size(); index++) {
            String key = ContractChecks.requiredText(this.extraKeys.get(index), "extraKey", 256);
            String value = ContractChecks.optionalText(this.extraValues.get(index), "extraValue", 4096);
            if (unique.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate Activity result extra: " + key);
            }
        }
    }

    public static ActivityResultIntentSnapshot empty() {
        return new ActivityResultIntentSnapshot("", "", "", "", 0, "", List.of(), List.of());
    }

    public static ActivityResultIntentSnapshot fromMap(
            String action, String dataUri, String mimeType, String componentName, int flags,
            String clipDescription, Map<String, String> extras) {
        Map<String, String> source = extras == null ? Map.of() : extras;
        return new ActivityResultIntentSnapshot(action, dataUri, mimeType, componentName, flags,
                clipDescription, new ArrayList<>(source.keySet()), new ArrayList<>(source.values()));
    }

    public String action() { return action; }
    public String dataUri() { return dataUri; }
    public String mimeType() { return mimeType; }
    public String componentName() { return componentName; }
    public int flags() { return flags; }
    public String clipDescription() { return clipDescription; }
    public List<String> extraKeys() { return extraKeys; }
    public List<String> extraValues() { return extraValues; }
    public Map<String, String> extras() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < extraKeys.size(); i++) out.put(extraKeys.get(i), extraValues.get(i));
        return Map.copyOf(out);
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int parcelFlags) {
        dest.writeStringList(extraKeys);
        dest.writeStringList(extraValues);
        dest.writeString(action);
        dest.writeString(dataUri);
        dest.writeString(mimeType);
        dest.writeString(componentName);
        dest.writeInt(flags);
        dest.writeString(clipDescription);
    }
}
