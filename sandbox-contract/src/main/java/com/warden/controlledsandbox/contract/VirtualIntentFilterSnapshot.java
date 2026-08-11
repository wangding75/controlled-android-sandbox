package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Typed intent-filter metadata supplied by the Binder-owned package authority. */
public final class VirtualIntentFilterSnapshot implements Parcelable {
    public static final int MAX_DATA_RULES = 1024;

    private final int priority;
    private final ArrayList<String> actions;
    private final ArrayList<String> categories;
    private final ArrayList<VirtualIntentDataSnapshot> data;

    public VirtualIntentFilterSnapshot(int priority, List<String> actions,
                                       List<String> categories,
                                       List<VirtualIntentDataSnapshot> data) {
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority out of range");
        }
        this.priority = priority;
        this.actions = names(actions, "action", 256);
        this.categories = names(categories, "category", 256);
        this.data = new ArrayList<>(data == null ? List.of() : data);
        if (this.data.size() > MAX_DATA_RULES) {
            throw new IllegalArgumentException("Too many data rules");
        }
    }

    private VirtualIntentFilterSnapshot(Parcel in) {
        this(in.readInt(), in.createStringArrayList(), in.createStringArrayList(),
                in.createTypedArrayList(VirtualIntentDataSnapshot.CREATOR));
    }

    public int priority() { return priority; }
    public List<String> actions() { return Collections.unmodifiableList(actions); }
    public List<String> categories() { return Collections.unmodifiableList(categories); }
    public List<VirtualIntentDataSnapshot> data() { return Collections.unmodifiableList(data); }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(priority); out.writeStringList(actions); out.writeStringList(categories);
        out.writeTypedList(data);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualIntentFilterSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualIntentFilterSnapshot createFromParcel(Parcel in) {
            return new VirtualIntentFilterSnapshot(in);
        }
        @Override public VirtualIntentFilterSnapshot[] newArray(int size) {
            return new VirtualIntentFilterSnapshot[size];
        }
    };

    private static ArrayList<String> names(List<String> input, String name, int maximum) {
        ArrayList<String> output = new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (input == null) return output;
        if (input.size() > maximum) throw new IllegalArgumentException("Too many " + name + " values");
        for (String item : input) {
            if (item == null || item.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
            String normalized = item.trim();
            if (!unique.add(normalized)) throw new IllegalArgumentException("Duplicate " + name + ": " + normalized);
        }
        output.addAll(unique);
        return output;
    }
}
