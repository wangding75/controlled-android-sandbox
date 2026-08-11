package android.graphics;

import java.io.OutputStream;

/** Minimal bitmap fixture for static compilation of the generic camera source engine. */
public class Bitmap {
    public enum CompressFormat { JPEG }
    public int getWidth() { return 1; }
    public int getHeight() { return 1; }
    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) { }
    public boolean compress(CompressFormat format, int quality, OutputStream output) { return true; }
    public void recycle() { }
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height,
            Matrix matrix, boolean filter) { return source; }
}
