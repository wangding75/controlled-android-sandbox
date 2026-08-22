package android.hardware;

import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Public-API SensorManager projection used when a ROM hides its catalog fields. */
public final class ControlledSensorManager extends SensorManager {
    private static final ScheduledExecutorService CALLBACK_EXECUTOR =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "controlled-sandbox-sensor-manager");
                thread.setDaemon(true);
                return thread;
            });
    private final List<Sensor> sensors;
    private final Map<SensorEventListener, Registration> registrations = new IdentityHashMap<>();

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
        return register(listener, sensor);
    }

    @Override public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                               int samplingPeriodUs, int maxReportLatencyUs) {
        return register(listener, sensor);
    }

    @Override public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                               int samplingPeriodUs, Handler handler) {
        return register(listener, sensor);
    }

    @Override public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                               int samplingPeriodUs, int maxReportLatencyUs,
                                               Handler handler) {
        return register(listener, sensor);
    }

    @Override public void unregisterListener(SensorEventListener listener) {
        synchronized (registrations) {
            Registration registration = registrations.remove(listener);
            if (registration != null) registration.cancel();
        }
    }

    @Override public void unregisterListener(SensorEventListener listener, Sensor sensor) {
        unregisterListener(listener);
    }

    @Override public boolean flush(SensorEventListener listener) {
        Registration registration;
        synchronized (registrations) { registration = registrations.get(listener); }
        if (registration == null) return false;
        invokeFlush(listener, registration.sensor);
        return true;
    }

    private boolean register(SensorEventListener listener, Sensor sensor) {
        if (listener == null || sensor == null || !sensors.contains(sensor)) return false;
        Registration registration = new Registration(listener, sensor);
        synchronized (registrations) {
            Registration previous = registrations.put(listener, registration);
            if (previous != null) previous.cancel();
        }
        deliver(registration);
        registration.future = CALLBACK_EXECUTOR.scheduleAtFixedRate(
                () -> deliver(registration), 250L, 250L, TimeUnit.MILLISECONDS);
        return true;
    }

    private void deliver(Registration registration) {
        synchronized (registrations) {
            if (registrations.get(registration.listener) != registration) return;
        }
        try {
            registration.listener.onSensorChanged(event(registration.sensor));
        } catch (Throwable ignored) {
            // A Guest callback is best-effort; unregister/lease cleanup remains authoritative.
        }
    }

    private static void invokeFlush(SensorEventListener listener, Sensor sensor) {
        try {
            java.lang.reflect.Method method = listener.getClass().getMethod(
                    "onFlushCompleted", Sensor.class);
            method.setAccessible(true);
            method.invoke(listener, sensor);
        } catch (Throwable ignored) {
            // SensorEventListener (rather than SensorEventListener2) has no flush callback.
        }
    }

    private static SensorEvent event(Sensor sensor) {
        try {
            java.lang.reflect.Constructor<SensorEvent> constructor;
            try {
                constructor = SensorEvent.class.getDeclaredConstructor(int.class);
            } catch (NoSuchMethodException missingSizedConstructor) {
                constructor = SensorEvent.class.getDeclaredConstructor();
            }
            constructor.setAccessible(true);
            SensorEvent event = constructor.getParameterCount() == 0
                    ? constructor.newInstance() : constructor.newInstance(3);
            java.lang.reflect.Field values = SensorEvent.class.getDeclaredField("values");
            values.setAccessible(true);
            values.set(event, new float[]{sensor.getType(), sensor.getType() + 0.25f,
                    sensor.getType() + 0.5f});
            event.sensor = sensor;
            event.accuracy = 3;
            event.timestamp = System.nanoTime();
            return event;
        } catch (Throwable error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("CONTROLLED_SENSOR_EVENT_CONSTRUCTION_FAILED", error);
        }
    }

    private final class Registration {
        private final SensorEventListener listener;
        private final Sensor sensor;
        private ScheduledFuture<?> future;

        private Registration(SensorEventListener listener, Sensor sensor) {
            this.listener = listener;
            this.sensor = sensor;
        }

        private void cancel() {
            if (future != null) future.cancel(false);
        }
    }

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
