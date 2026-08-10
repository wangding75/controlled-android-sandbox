package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/** Test fixture for the API32/API35 ActivityClient singleton and controller cache shapes. */
public final class ActivityClient {
    private static final ControllerSingleton INTERFACE_SINGLETON = new ControllerSingleton();

    private ActivityClient() { }

    public static void resetForTest(IActivityClientController controller) {
        INTERFACE_SINGLETON.mInstance = controller;
        INTERFACE_SINGLETON.mKnownInstance = controller;
    }

    public static IActivityClientController getServiceForTest() {
        return INTERFACE_SINGLETON.mKnownInstance != null
                ? INTERFACE_SINGLETON.mKnownInstance : INTERFACE_SINGLETON.get();
    }

    public static IActivityClientController instanceForTest() { return INTERFACE_SINGLETON.mInstance; }
    public static IActivityClientController knownInstanceForTest() {
        return INTERFACE_SINGLETON.mKnownInstance;
    }

    public static Object getSingletonForTest() { return INTERFACE_SINGLETON; }

    public interface IActivityClientController extends IInterface {
        void activityResumed(IBinder token, boolean splashScreenExit);
        void activityDestroyed(IBinder token);
    }

    public static final class Controller extends Binder implements IActivityClientController {
        private int calls;

        public Controller() { attachInterface(this, "android.app.IActivityClientController"); }

        @Override public IBinder asBinder() { return this; }
        @Override public void activityResumed(IBinder token, boolean splashScreenExit) { calls++; }
        @Override public void activityDestroyed(IBinder token) { calls++; }
        public int calls() { return calls; }
        @Override public String getInterfaceDescriptor() {
            return "android.app.IActivityClientController";
        }
    }

    private static final class ControllerSingleton {
        private IActivityClientController mInstance;
        private IActivityClientController mKnownInstance;

        private IActivityClientController get() { return mInstance; }
    }
}
