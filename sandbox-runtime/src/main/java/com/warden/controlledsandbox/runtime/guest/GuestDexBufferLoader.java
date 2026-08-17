package com.warden.controlledsandbox.runtime.guest;

import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts verified DEX entries from APK capabilities without exposing a pathname to ART. */
final class GuestDexBufferLoader {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_DEX_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_TOTAL_DEX_BYTES = 768L * 1024L * 1024L;

    private GuestDexBufferLoader() { }

    static List<ByteBuffer> load(ParcelFileDescriptor base,
                                 List<ParcelFileDescriptor> splits) throws IOException {
        if (base == null || base.getFd() < 0) {
            throw new IllegalArgumentException("FD-backed base APK is required");
        }
        ArrayList<DexEntry> entries = new ArrayList<>();
        appendArchive(base, "base", entries);
        if (splits != null) {
            for (int index = 0; index < splits.size(); index++) {
                ParcelFileDescriptor split = splits.get(index);
                if (split == null || split.getFd() < 0) {
                    throw new IllegalArgumentException("FD-backed split APK is invalid: " + index);
                }
                appendArchive(split, "split-" + index, entries);
            }
        }
        if (entries.isEmpty()) throw new IOException("FD-backed APK contains no DEX entries");
        entries.sort(Comparator.comparing((DexEntry value) -> value.archive)
                .thenComparingInt(value -> dexOrdinal(value.name)));
        ArrayList<ByteBuffer> buffers = new ArrayList<>(entries.size());
        for (DexEntry entry : entries) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(entry.bytes.length);
            buffer.put(entry.bytes).flip();
            buffers.add(buffer);
        }
        return List.copyOf(buffers);
    }

    private static void appendArchive(ParcelFileDescriptor source, String archive,
                                      List<DexEntry> out) throws IOException {
        ParcelFileDescriptor duplicate = source.dup();
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(duplicate)) {
            try {
                input.getChannel().position(0L);
            } catch (IOException notSeekable) {
                throw new IOException("APK capability is not seekable: " + archive, notSeekable);
            }
            try (ZipInputStream zip = new ZipInputStream(input)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                ZipEntry entry;
                long total = totalBytes(out);
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!isDexName(name)) {
                        zip.closeEntry();
                        continue;
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                            entry.getSize() > 0 && entry.getSize() <= MAX_DEX_BYTES
                                    ? (int) entry.getSize() : BUFFER_SIZE);
                    long size = 0L;
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        size += read;
                        total += read;
                        if (size > MAX_DEX_BYTES || total > MAX_TOTAL_DEX_BYTES) {
                            throw new IOException("APK DEX capability exceeds memory bound: " + archive);
                        }
                        bytes.write(buffer, 0, read);
                    }
                    out.add(new DexEntry(archive, name, bytes.toByteArray()));
                    zip.closeEntry();
                }
            }
        }
    }

    private static long totalBytes(List<DexEntry> entries) {
        long total = 0L;
        for (DexEntry entry : entries) total += entry.bytes.length;
        return total;
    }

    private static boolean isDexName(String name) {
        if (name == null || !name.endsWith(".dex")) return false;
        if ("classes.dex".equals(name)) return true;
        if (!name.startsWith("classes") || !name.endsWith(".dex")) return false;
        String ordinal = name.substring("classes".length(), name.length() - ".dex".length());
        if (ordinal.isEmpty()) return false;
        for (int i = 0; i < ordinal.length(); i++) {
            if (!Character.isDigit(ordinal.charAt(i))) return false;
        }
        return Integer.parseInt(ordinal) >= 2;
    }

    private static int dexOrdinal(String name) {
        if ("classes.dex".equals(name)) return 1;
        try {
            return Integer.parseInt(name.substring("classes".length(), name.length() - 4));
        } catch (RuntimeException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static final class DexEntry {
        final String archive;
        final String name;
        final byte[] bytes;

        DexEntry(String archive, String name, byte[] bytes) {
            this.archive = archive;
            this.name = name;
            this.bytes = bytes;
        }
    }
}
