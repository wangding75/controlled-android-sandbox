package android.hardware.display;

/** Test fixture for Context.DISPLAY_SERVICE's manager-to-global relationship. */
public final class DisplayManager {
    private final DisplayManagerGlobal mGlobal;
    private final java.util.ArrayList<Object> mDisplays = new java.util.ArrayList<>();
    private final java.util.ArrayList<Object> mTempDisplays = new java.util.ArrayList<>();

    public DisplayManager(DisplayManagerGlobal global) {
        mGlobal = global;
        mDisplays.add("host-display");
        mTempDisplays.add("host-temp-display");
    }

    public DisplayManagerGlobal globalForTest() { return mGlobal; }
    public java.util.List<Object> displaysForTest() { return mDisplays; }
    public java.util.List<Object> tempDisplaysForTest() { return mTempDisplays; }
}
