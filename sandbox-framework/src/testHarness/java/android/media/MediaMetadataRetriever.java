package android.media;

import android.graphics.Bitmap;

/** Minimal media fixture for static compilation of image/video source code. */
public class MediaMetadataRetriever {
    public static final int OPTION_CLOSEST_SYNC = 2;
    public static final int METADATA_KEY_VIDEO_WIDTH = 18;
    public static final int METADATA_KEY_VIDEO_HEIGHT = 19;
    public static final int METADATA_KEY_DURATION = 9;
    public void setDataSource(String path) { }
    public Bitmap getFrameAtTime(long timeUs, int option) { return new Bitmap(); }
    public String extractMetadata(int key) { return "0"; }
    public void release() { }
}
