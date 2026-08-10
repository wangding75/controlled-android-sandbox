package android.os;

import java.util.HashMap;
import java.util.Map;

/** Minimal ServiceManager cache surface used by framework compatibility self-tests. */
public final class ServiceManager {
    public static final Map<String, IBinder> sCache = new HashMap<>();

    private ServiceManager() { }

    public static IBinder getService(String name) {
        return sCache.get(name);
    }
}
