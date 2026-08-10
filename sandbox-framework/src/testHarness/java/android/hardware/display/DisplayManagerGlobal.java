package android.hardware.display;

import java.util.ArrayList;
import java.util.List;

/** Test fixture for the API32/API35 DisplayManagerGlobal cache layout. */
public final class DisplayManagerGlobal {
    private static DisplayManagerGlobal sInstance;
    private final IDisplayManager mDm;
    private final ArrayList<Object> mDisplayInfoCache = new ArrayList<>();
    private int[] mDisplayIdCache = new int[] {99};

    public DisplayManagerGlobal(IDisplayManager dm) {
        mDm = dm;
        mDisplayInfoCache.add("host-display-info");
    }

    public static DisplayManagerGlobal getInstance() { return sInstance; }
    public static void setInstanceForTest(DisplayManagerGlobal value) { sInstance = value; }
    public IDisplayManager serviceForTest() { return mDm; }
    public List<Object> infoCacheForTest() { return mDisplayInfoCache; }
    public int[] idCacheForTest() { return mDisplayIdCache; }
}
