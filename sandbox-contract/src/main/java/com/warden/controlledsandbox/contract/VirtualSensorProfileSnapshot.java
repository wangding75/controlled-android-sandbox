package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded virtual sensor catalog and event-rate policy. */
public final class VirtualSensorProfileSnapshot implements Parcelable {
    private final String mode;
    private final int maximumEventsPerSecond;
    private final List<VirtualSensorSnapshot> sensors;

    public VirtualSensorProfileSnapshot(String mode, int maximumEventsPerSecond,
            List<VirtualSensorSnapshot> sensors) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        if (maximumEventsPerSecond < 1 || maximumEventsPerSecond > 1000) {
            throw new IllegalArgumentException("maximumEventsPerSecond is invalid");
        }
        this.maximumEventsPerSecond = maximumEventsPerSecond;
        List<VirtualSensorSnapshot> copy = sensors == null ? List.of() : new ArrayList<>(sensors);
        if (copy.size() > 128 || copy.contains(null)) throw new IllegalArgumentException("sensors are invalid");
        Set<Integer> handles = new LinkedHashSet<>();
        for (VirtualSensorSnapshot sensor : copy) {
            if (!handles.add(sensor.handle())) throw new IllegalArgumentException("duplicate sensor handle");
        }
        this.sensors = Collections.unmodifiableList(copy);
    }

    private VirtualSensorProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt(), in.createTypedArrayList(VirtualSensorSnapshot.CREATOR));
    }

    public String mode() { return mode; }
    public int maximumEventsPerSecond() { return maximumEventsPerSecond; }
    public List<VirtualSensorSnapshot> sensors() { return sensors; }
    public VirtualSensorSnapshot sensorForType(int type) {
        for (VirtualSensorSnapshot sensor : sensors) if (sensor.type() == type) return sensor;
        return null;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeInt(maximumEventsPerSecond); out.writeTypedList(sensors);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualSensorProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualSensorProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualSensorProfileSnapshot(in);
        }
        @Override public VirtualSensorProfileSnapshot[] newArray(int size) {
            return new VirtualSensorProfileSnapshot[size];
        }
    };
}
