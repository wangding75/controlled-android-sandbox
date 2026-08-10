package com.warden.controlledsandbox.framework.core;

import android.content.Context;
import android.hardware.ControlledSensorManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.util.ArrayList;
import java.util.List;

/** Reversible public-API SensorManager catalog replacement for ROMs with hidden catalog fields. */
public final class SensorCatalogHook implements AutoCloseable {
    private final AutoCloseable override;

    private SensorCatalogHook(AutoCloseable override) { this.override = override; }

    public static AutoCloseable install(Context context, GuestIdentity identity) throws Exception {
        VirtualSensorProfileSnapshot profile = identity.virtualServices().deviceServiceProfile().sensors();
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return () -> { };
        Object original = context.getSystemService("sensor");
        if (!(original instanceof SensorManager manager)) {
            throw new IllegalStateException("System service unavailable: sensor");
        }
        List<Sensor> virtualSensors = new ArrayList<>();
        if (!VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())) {
            for (VirtualSensorSnapshot sensor : profile.sensors()) {
                virtualSensors.add((Sensor) FrameworkDeviceObjectFactory.sensor(Sensor.class, sensor));
            }
        }
        ControlledSensorManager projected = new ControlledSensorManager(virtualSensors);
        AutoCloseable override = GuestSystemServiceOverrideRegistry.install(
                context, "sensor", projected);
        return new SensorCatalogHook(override);
    }

    @Override public void close() {
        try { override.close(); } catch (Exception ignored) { }
    }
}
