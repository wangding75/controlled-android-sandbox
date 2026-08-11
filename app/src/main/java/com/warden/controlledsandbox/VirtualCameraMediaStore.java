package com.warden.controlledsandbox;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;

/** Copies controlled media into the instance-owned Guest files root and returns only metadata. */
final class VirtualCameraMediaStore {
    private static final long MAX_IMAGE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_VIDEO_BYTES = 256L * 1024L * 1024L;

    private VirtualCameraMediaStore() { }

    static VirtualCameraSourceSnapshot importSource(Context context, String packageName,
            int virtualUserId, Uri sourceUri, String requestedKind) throws Exception {
        if (context == null || packageName == null || packageName.trim().isEmpty()
                || sourceUri == null) throw new IllegalArgumentException("camera source is required");
        String mime = context.getContentResolver().getType(sourceUri);
        String kind = normalizeKind(requestedKind, mime, sourceUri.toString());
        long maximum = VirtualCameraSourceSnapshot.VIDEO.equals(kind)
                ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
        File filesRoot = new File(context.getFilesDir(), "instances/u" + virtualUserId + "/"
                + safe(packageName) + "/data/files");
        File mediaRoot = new File(filesRoot, "virtual-camera");
        if (!mediaRoot.isDirectory() && !mediaRoot.mkdirs() && !mediaRoot.isDirectory()) {
            throw new IllegalStateException("CAMERA_MEDIA_DIRECTORY_CREATE_FAILED");
        }
        File temporary = File.createTempFile("source-", ".tmp", mediaRoot);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long length = 0L;
        try (InputStream input = open(context, sourceUri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) throw new IllegalStateException("CAMERA_MEDIA_SOURCE_UNREADABLE");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                length += count;
                if (length > maximum) throw new IllegalArgumentException("CAMERA_MEDIA_TOO_LARGE");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } catch (Exception error) {
            if (!temporary.delete()) temporary.deleteOnExit();
            throw error;
        }
        String sha256 = hex(digest.digest());
        String extension = extension(kind, mime, sourceUri.toString());
        File destination = new File(mediaRoot, "source-" + sha256 + extension);
        if (!destination.isFile()) {
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailure) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } else if (!temporary.delete()) {
            temporary.deleteOnExit();
        }
        int width = 0;
        int height = 0;
        long durationMs = 0L;
        if (VirtualCameraSourceSnapshot.IMAGE.equals(kind)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(destination.getAbsolutePath(), options);
            width = Math.max(0, options.outWidth);
            height = Math.max(0, options.outHeight);
        } else {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(destination.getAbsolutePath());
                String widthValue = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String heightValue = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String durationValue = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                width = parseBoundedInt(widthValue, 0, 16_384);
                height = parseBoundedInt(heightValue, 0, 16_384);
                durationMs = parseBoundedLong(durationValue, 0L, 24L * 60L * 60L * 1000L);
            } finally {
                retriever.release();
            }
        }
        String relative = "virtual-camera/" + destination.getName();
        return new VirtualCameraSourceSnapshot(kind, relative, mimeFor(kind, mime), sha256,
                width, height, 0, durationMs);
    }

    static File resolve(Context context, String packageName, int virtualUserId,
            VirtualCameraSourceSnapshot source) throws Exception {
        File filesRoot = new File(context.getFilesDir(), "instances/u" + virtualUserId + "/"
                + safe(packageName) + "/data/files").getCanonicalFile();
        File file = new File(filesRoot, source.relativePath()).getCanonicalFile();
        if (!file.toPath().startsWith(filesRoot.toPath()) || !file.isFile()) {
            throw new IllegalStateException("CAMERA_MEDIA_SOURCE_MISSING");
        }
        return file;
    }

    private static InputStream open(Context context, Uri uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme())) return new FileInputStream(uri.getPath());
        return context.getContentResolver().openInputStream(uri);
    }
    private static String normalizeKind(String requested, String mime, String value) {
        String normalized = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) normalized = mime != null && mime.startsWith("video/")
                ? VirtualCameraSourceSnapshot.VIDEO : VirtualCameraSourceSnapshot.IMAGE;
        if (!VirtualCameraSourceSnapshot.IMAGE.equals(normalized)
                && !VirtualCameraSourceSnapshot.VIDEO.equals(normalized)) {
            throw new IllegalArgumentException("CAMERA_MEDIA_KIND_INVALID:" + value);
        }
        return normalized;
    }
    private static String mimeFor(String kind, String mime) {
        if (mime != null && !mime.trim().isEmpty()) return mime.trim();
        return VirtualCameraSourceSnapshot.VIDEO.equals(kind) ? "video/mp4" : "image/jpeg";
    }
    private static String extension(String kind, String mime, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (VirtualCameraSourceSnapshot.VIDEO.equals(kind)) return lower.endsWith(".mp4") ? ".mp4" : ".video";
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        return ".jpg";
    }
    private static int parseBoundedInt(String value, int fallback, int maximum) {
        try { int parsed = Integer.parseInt(value == null ? "" : value); return parsed < 0 || parsed > maximum ? fallback : parsed; }
        catch (Exception ignored) { return fallback; }
    }
    private static long parseBoundedLong(String value, long fallback, long maximum) {
        try { long parsed = Long.parseLong(value == null ? "" : value); return parsed < 0 || parsed > maximum ? fallback : parsed; }
        catch (Exception ignored) { return fallback; }
    }
    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return out.toString();
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
