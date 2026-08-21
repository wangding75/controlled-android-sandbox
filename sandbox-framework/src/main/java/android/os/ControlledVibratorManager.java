package android.os;

import android.annotation.SuppressLint;
import com.warden.controlledsandbox.contract.VirtualPowerProfileSnapshot;

/** Guest-owned VibratorManager projection for platform isolated processes. */
@SuppressLint("NewApi")
public final class ControlledVibratorManager extends VibratorManager {
    private final ControlledVibrator vibrator;

    public ControlledVibratorManager(VirtualPowerProfileSnapshot profile) {
        vibrator = new ControlledVibrator(profile);
    }

    @Override public int[] getVibratorIds() {
        return vibrator.hasVibrator() ? new int[] {0} : new int[0];
    }

    @Override public Vibrator getVibrator(int vibratorId) {
        return vibratorId == 0 ? vibrator : new ControlledVibrator(null);
    }

    @Override public Vibrator getDefaultVibrator() { return vibrator; }
    @SuppressLint("MissingPermission")
    @Override public void cancel() { vibrator.cancel(); }
}
