package com.warden.controlledsandbox.framework.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Generic image/video source reader used by camera adapters; it has no DingTalk knowledge. */
public final class VirtualCameraCaptureEngine {
    private static final long MAX_SOURCE_BYTES = 256L * 1024L * 1024L;

    private VirtualCameraCaptureEngine() { }

    public static byte[] read(File guestFilesRoot, VirtualCameraSourceSnapshot source,
            long frameTimeMs, boolean nv21) throws Exception {
        if (guestFilesRoot == null || source == null || !source.isConfigured()) {
            throw new IllegalStateException("VIRTUAL_CAMERA_SOURCE_NOT_CONFIGURED");
        }
        File root = guestFilesRoot.getCanonicalFile();
        File file = new File(root, source.relativePath()).getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()) || !file.isFile()) {
            throw new IllegalStateException("VIRTUAL_CAMERA_SOURCE_MISSING");
        }
        if (file.length() > MAX_SOURCE_BYTES) throw new IllegalStateException("VIRTUAL_CAMERA_SOURCE_TOO_LARGE");
        verifySha256(file, source.sha256());
        Bitmap bitmap;
        if (VirtualCameraSourceSnapshot.IMAGE.equals(source.kind())) {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) throw new IllegalStateException("VIRTUAL_CAMERA_IMAGE_DECODE_FAILED");
        } else {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                bitmap = retriever.getFrameAtTime(Math.max(0L, frameTimeMs) * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bitmap == null) throw new IllegalStateException("VIRTUAL_CAMERA_VIDEO_FRAME_FAILED");
            } finally {
                retriever.release();
            }
        }
        Bitmap transformed = rotate(bitmap, source.orientationDegrees());
        if (transformed != bitmap) bitmap.recycle();
        try {
            if (nv21) return nv21(transformed);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!transformed.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                throw new IllegalStateException("VIRTUAL_CAMERA_JPEG_ENCODE_FAILED");
            }
            return output.toByteArray();
        } finally {
            transformed.recycle();
        }
    }

    public static String sha256(byte[] value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(value));
    }

    private static byte[] nv21(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] output = new byte[width * height * 3 / 2];
        int yIndex = 0;
        int uvIndex = width * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = argb[y * width + x];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int yValue = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                output[yIndex++] = (byte) clamp(yValue);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    output[uvIndex++] = (byte) clamp(v);
                    output[uvIndex++] = (byte) clamp(u);
                }
            }
        }
        return output;
    }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    private static Bitmap rotate(Bitmap bitmap, int degrees) {
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }
    private static void verifySha256(File file, String expected) throws Exception {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("VIRTUAL_CAMERA_SOURCE_HASH_INVALID");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!expected.equalsIgnoreCase(hex(digest.digest()))) {
            throw new SecurityException("VIRTUAL_CAMERA_SOURCE_HASH_MISMATCH");
        }
    }
    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return out.toString();
    }
}
