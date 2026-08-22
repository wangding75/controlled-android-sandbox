package android.hardware;

/** Minimal platform shape used by the static fixture compiler. */
public interface SensorEventListener {
    void onSensorChanged(SensorEvent event);
    void onAccuracyChanged(Sensor sensor, int accuracy);
}
