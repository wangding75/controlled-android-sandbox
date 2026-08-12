package android.telephony;

import android.content.Context;
import java.util.List;

/** Minimal API surface used by the static framework and SMS cache fixtures. */
public class TelephonyManager {
    private static Object sISms;

    public TelephonyManager() { }
    public TelephonyManager(Context context) { }

    public int getPhoneCount() { return 0; }
    public boolean isVoiceCapable() { return false; }
    public String getImei() { return null; }
    public String getImei(int slotIndex) { return null; }
    public String getMeid() { return null; }
    public String getMeid(int slotIndex) { return null; }
    public String getDeviceId() { return null; }
    public String getDeviceId(int slotIndex) { return null; }
    public String getSubscriberId() { return null; }
    public String getSimSerialNumber() { return null; }
    public String getLine1Number() { return null; }
    public List<CellInfo> getAllCellInfo() { return List.of(); }
    public String getNetworkOperatorName() { return ""; }
    public String getNetworkOperator() { return ""; }
    public String getNetworkCountryIso() { return ""; }
    public String getSimOperator() { return ""; }
    public String getSimOperatorName() { return ""; }
    public String getSimCountryIso() { return ""; }

    public static void resetSmsServiceForTest(Object service) { sISms = service; }
    public static Object smsServiceForTest() { return sISms; }
}
