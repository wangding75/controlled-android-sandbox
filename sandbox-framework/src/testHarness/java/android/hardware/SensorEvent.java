package android.hardware;

/** Minimal platform shape used by the static fixture compiler. */
public class SensorEvent {
    public final float[] values;
    public Sensor sensor;
    public long timestamp;
    public int accuracy;

    public SensorEvent(int valueSize) { values = new float[Math.max(0, valueSize)]; }
}
