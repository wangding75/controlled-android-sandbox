package android.app;

/** Host-side fixture for the hidden ActivityManager singleton shape. */
public final class ActivityManager {
    private static final Singleton IActivityManagerSingleton = new Singleton();

    private ActivityManager() {
    }

    public static void setServiceForTest(Object service) {
        IActivityManagerSingleton.mInstance = service;
    }

    public static Object getServiceForTest() {
        return IActivityManagerSingleton.mInstance;
    }

    private static final class Singleton {
        private Object mInstance;

        private Object get() {
            return mInstance;
        }
    }
}
