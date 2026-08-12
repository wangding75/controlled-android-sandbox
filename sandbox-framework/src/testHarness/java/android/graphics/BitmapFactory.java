package android.graphics;

/** Minimal bitmap decoder fixture for static compilation. */
public final class BitmapFactory {
    public static final class Options {
        public boolean inJustDecodeBounds;
        public int outWidth;
        public int outHeight;
    }
    private BitmapFactory() { }
    public static Bitmap decodeByteArray(byte[] data, int offset, int length) { return new Bitmap(); }
    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) { return new Bitmap(); }
    public static Bitmap decodeFile(String path) { return new Bitmap(); }
    public static Bitmap decodeFile(String path, Options options) { return new Bitmap(); }
}
