package android.hardware;

import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Public-API SensorManager projection used when a ROM hides its catalog fields. */
public final class ControlledSensorManager extends SensorManager {
    private final List<Sensor> sensors;

    public ControlledSensorManager(List<Sensor> sensors) {
        super();
        this.sensors = Collections.unmodifiableList(new ArrayList<>(sensors));
    }

    @Override public int getSensors() {
        int result = 0;
        for (Sensor sensor : sensors) {
            int type = sensor == null ? 0 : sensor.getType();
            if (type > 0 && type < Integer.SIZE - 1) result |= 1 << type;
        }
        return result;
    }

    @Override public List<Sensor> getSensorList(int type) {
        return filter(type, false);
    }

    @Override public List<Sensor> getDynamicSensorList(int type) {
        return filter(type, true);
    }

    @Override public Sensor getDefaultSensor(int type) {
        return getDefaultSensor(type, false);
    }

    @Override public Sensor getDefaultSensor(int type, boolean wakeUp) {
        for (Sensor sensor : sensors) {
            if (sensor != null && sensor.getType() == type
                    && (!wakeUp || sensor.isWakeUpSensor())) return sensor;
        }
        return null;
    }

    @Override public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                               int samplingPeriodUs) {
        return false;
    }

    @Override public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                               int samplingPeriodUs, int maxReportLatencyUs) {
        return false;
    }

    @Override public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                               int samplingPeriodUs, Handler handler) {
        return false;
    }

    @Override public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                               int samplingPeriodUs, int maxReportLatencyUs,
                                               Handler handler) {
        return false;
    }

    @Override public boolean flush(SensorEventListener listener) { return false; }

    private List<Sensor> filter(int type, boolean dynamic) {
        if (dynamic) {
            List<Sensor> result = new ArrayList<>();
            for (Sensor sensor : sensors) if (sensor != null && sensor.isDynamicSensor()
                    && (type == SENSOR_ALL || sensor.getType() == type)) result.add(sensor);
            return Collections.unmodifiableList(result);
        }
        if (type == SENSOR_ALL) return sensors;
        List<Sensor> result = new ArrayList<>();
        for (Sensor sensor : sensors) if (sensor != null && sensor.getType() == type) result.add(sensor);
        return Collections.unmodifiableList(result);
    }
}
