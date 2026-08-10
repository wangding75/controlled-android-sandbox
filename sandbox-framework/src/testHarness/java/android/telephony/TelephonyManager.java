package android.telephony;

/** Minimal static SMS cache fixture for API32/API35 cache synchronization tests. */
public final class TelephonyManager {
    private static Object sISms;

    private TelephonyManager() { }

    public static void resetSmsServiceForTest(Object service) { sISms = service; }
    public static Object smsServiceForTest() { return sISms; }
}
