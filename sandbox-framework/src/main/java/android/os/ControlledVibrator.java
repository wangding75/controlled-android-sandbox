package android.os;

import android.media.AudioAttributes;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPowerProfileSnapshot;

/** Guest-owned Vibrator implementation for platform isolated processes. */
public final class ControlledVibrator extends Vibrator {
    private final VirtualPowerProfileSnapshot profile;

    public ControlledVibrator(VirtualPowerProfileSnapshot profile) {
        this.profile = profile;
    }

    @Override public boolean hasVibrator() {
        return allowed();
    }

    @Override public boolean hasAmplitudeControl() { return false; }

    @Override public void cancel() { }

    @Override public void vibrate(long milliseconds) { enforce(milliseconds); }
    @Override public void vibrate(long milliseconds, AudioAttributes attributes) {
        enforce(milliseconds);
    }
    @Override public void vibrate(long[] pattern, int repeat) {
        enforce(pattern == null || pattern.length == 0 ? 0L : max(pattern));
    }
    @Override public void vibrate(long[] pattern, int repeat, AudioAttributes attributes) {
        vibrate(pattern, repeat);
    }
    @Override public void vibrate(VibrationEffect effect) { enforce(profile.maximumVibrationDurationMs()); }
    @Override public void vibrate(VibrationEffect effect, AudioAttributes attributes) {
        vibrate(effect);
    }
    @Override public void vibrate(VibrationEffect effect, VibrationAttributes attributes) {
        vibrate(effect);
    }

    private boolean allowed() {
        return profile != null && !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())
                && profile.allowVibration();
    }

    private void enforce(long duration) {
        if (!allowed()) throw new SecurityException("VIRTUAL_VIBRATION_DENIED");
        if (duration < 0 || duration > profile.maximumVibrationDurationMs()) {
            throw new IllegalArgumentException("VIRTUAL_VIBRATION_DURATION_EXCEEDED");
        }
    }

    private static long max(long[] values) {
        long result = 0L;
        for (long value : values) result = Math.max(result, value);
        return result;
    }
}
