package android.telephony;

import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Guest-facing telephony manager that projects profile-owned operator metadata. */
public final class ControlledTelephonyManager extends TelephonyManager {
    private TelephonyManager delegate;
    private Supplier<VirtualTelephonyProfileSnapshot> profileSupplier;

    private ControlledTelephonyManager() { super(); }

    /** Allocates without invoking the inaccessible framework hidden constructor. */
    public static ControlledTelephonyManager create(TelephonyManager delegate,
            Supplier<VirtualTelephonyProfileSnapshot> profileSupplier) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
        ControlledTelephonyManager result = (ControlledTelephonyManager)
                allocate.invoke(unsafe, ControlledTelephonyManager.class);
        result.delegate = Objects.requireNonNull(delegate, "delegate");
        result.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
        return result;
    }

    @Override public int getPhoneCount() { return profile().slots().size(); }
    @Override public boolean isVoiceCapable() { return profile().voiceCapable(); }
    @Override public String getImei() { return delegate.getImei(); }
    @Override public String getImei(int slotIndex) { return delegate.getImei(slotIndex); }
    @Override public String getMeid() { return delegate.getMeid(); }
    @Override public String getMeid(int slotIndex) { return delegate.getMeid(slotIndex); }
    @Override public String getDeviceId() { return delegate.getDeviceId(); }
    @Override public String getDeviceId(int slotIndex) { return delegate.getDeviceId(slotIndex); }
    @Override public String getSubscriberId() { return delegate.getSubscriberId(); }
    @Override public String getSimSerialNumber() { return delegate.getSimSerialNumber(); }
    @Override public String getLine1Number() { return delegate.getLine1Number(); }
    @Override public List<CellInfo> getAllCellInfo() { return delegate.getAllCellInfo(); }

    @Override public String getNetworkOperatorName() {
        VirtualTelephonySlotSnapshot slot = profile().defaultSlot();
        return slot == null ? "" : slot.carrierName();
    }
    @Override public String getNetworkOperator() {
        VirtualTelephonySlotSnapshot slot = profile().defaultSlot();
        return slot == null ? "" : slot.networkOperator();
    }
    @Override public String getNetworkCountryIso() {
        VirtualTelephonySlotSnapshot slot = profile().defaultSlot();
        return slot == null ? "" : slot.networkCountryIso();
    }
    @Override public String getSimOperator() {
        VirtualTelephonySlotSnapshot slot = profile().defaultSlot();
        return slot == null ? "" : slot.simOperator();
    }
    @Override public String getSimOperatorName() {
        VirtualTelephonySlotSnapshot slot = profile().defaultSlot();
        return slot == null ? "" : slot.carrierName();
    }
    @Override public String getSimCountryIso() {
        VirtualTelephonySlotSnapshot slot = profile().defaultSlot();
        return slot == null ? "" : slot.simCountryIso();
    }

    private VirtualTelephonyProfileSnapshot profile() {
        VirtualTelephonyProfileSnapshot value = profileSupplier.get();
        if (value == null) throw new IllegalStateException("VIRTUAL_TELEPHONY_PROFILE_UNAVAILABLE");
        return value;
    }
}
