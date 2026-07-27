package android.app;

/** Host-side fixture for the hidden ActivityTaskManager singleton shape. */
public final class ActivityTaskManager {
    private static final Singleton IActivityTaskManagerSingleton = new Singleton();

    private ActivityTaskManager() {
    }

    public static void setServiceForTest(Object service) {
        IActivityTaskManagerSingleton.mInstance = service;
    }

    public static Object getServiceForTest() {
        return IActivityTaskManagerSingleton.mInstance;
    }

    private static final class Singleton {
        private Object mInstance;

        private Object get() {
            return mInstance;
        }
    }
}
