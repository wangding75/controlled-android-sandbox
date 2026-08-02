package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

import android.content.SharedPreferences;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Small dependency-free atomic SharedPreferences implementation scoped to a Guest instance. */
public final class SandboxSharedPreferences implements SharedPreferences {
    private static final int MAGIC = 0x43535046; // CSPF
    private static final int VERSION = 1;
    private static final byte STRING = 1;
    private static final byte INT = 2;
    private static final byte LONG = 3;
    private static final byte FLOAT = 4;
    private static final byte BOOLEAN = 5;
    private static final byte STRING_SET = 6;
    private final File file;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean active = true;
    private final Set<OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArraySet<>();

    SandboxSharedPreferences(File file) {
        this.file = file;
        load();
    }

    @Override public synchronized Map<String, ?> getAll() { requireActive(); return Collections.unmodifiableMap(copyValues(values)); }
    @Override public synchronized String getString(String key, String defValue) { requireActive(); Object v = values.get(key); return v instanceof String ? (String) v : defValue; }
    @Override public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
        requireActive();
        Object value = values.get(key);
        if (!(value instanceof Set<?>)) return defValues;
        @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
        return Collections.unmodifiableSet(new HashSet<>(strings));
    }
    @Override public synchronized int getInt(String key, int defValue) { requireActive(); Object v = values.get(key); return v instanceof Integer ? (Integer) v : defValue; }
    @Override public synchronized long getLong(String key, long defValue) { requireActive(); Object v = values.get(key); return v instanceof Long ? (Long) v : defValue; }
    @Override public synchronized float getFloat(String key, float defValue) { requireActive(); Object v = values.get(key); return v instanceof Float ? (Float) v : defValue; }
    @Override public synchronized boolean getBoolean(String key, boolean defValue) { requireActive(); Object v = values.get(key); return v instanceof Boolean ? (Boolean) v : defValue; }
    @Override public synchronized boolean contains(String key) { requireActive(); return values.containsKey(key); }
    @Override public synchronized Editor edit() { requireActive(); return new EditorImpl(); }
    @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { requireActive(); if (listener != null) listeners.add(listener); }
    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { requireActive(); listeners.remove(listener); }

    synchronized void invalidateAfterMove() {
        active = false;
        values.clear();
        listeners.clear();
    }

    private void requireActive() {
        if (!active) throw new IllegalStateException("SHARED_PREFERENCES_MOVED");
    }

    private void load() {
        synchronized (this) {
            values.clear();
            if (!file.isFile()) return;
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unsupported preferences file");
                int count = input.readInt();
                if (count < 0 || count > 100_000) throw new IOException("Invalid preference count");
                for (int i = 0; i < count; i++) {
                    String key = readString(input);
                    byte type = input.readByte();
                    Object value;
                    switch (type) {
                        case STRING: value = readString(input); break;
                        case INT: value = input.readInt(); break;
                        case LONG: value = input.readLong(); break;
                        case FLOAT: value = input.readFloat(); break;
                        case BOOLEAN: value = input.readBoolean(); break;
                        case STRING_SET:
                            int size = input.readInt();
                            if (size < 0 || size > 100_000) throw new IOException("Invalid string-set size");
                            Set<String> set = new HashSet<>();
                            for (int j = 0; j < size; j++) set.add(readString(input));
                            value = set;
                            break;
                        default: throw new IOException("Unknown preference type " + type);
                    }
                    values.put(key, value);
                }
            } catch (Exception corrupt) {
                File rejected = new File(file.getParentFile(), file.getName() + ".corrupt");
                if (!file.renameTo(rejected)) file.delete();
                values.clear();
            }
        }
    }

    private synchronized boolean persist(Map<String, Object> replacement) {
        requireActive();
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) return false;
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream raw = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(raw))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(replacement.size());
            for (Map.Entry<String, Object> entry : replacement.entrySet()) {
                writeString(output, entry.getKey());
                Object value = entry.getValue();
                if (value instanceof String) { output.writeByte(STRING); writeString(output, (String) value); }
                else if (value instanceof Integer) { output.writeByte(INT); output.writeInt((Integer) value); }
                else if (value instanceof Long) { output.writeByte(LONG); output.writeLong((Long) value); }
                else if (value instanceof Float) { output.writeByte(FLOAT); output.writeFloat((Float) value); }
                else if (value instanceof Boolean) { output.writeByte(BOOLEAN); output.writeBoolean((Boolean) value); }
                else if (value instanceof Set<?>) {
                    output.writeByte(STRING_SET);
                    Set<?> set = (Set<?>) value;
                    output.writeInt(set.size());
                    for (Object item : set) writeString(output, String.valueOf(item));
                } else throw new IOException("Unsupported preference value " + value.getClass());
            }
            output.flush();
            raw.getFD().sync();
        } catch (IOException failure) {
            temporary.delete();
            return false;
        }
        try {
            DurableAtomicFile.replacePrepared(temporary.toPath(), file.toPath());
        } catch (IOException failure) {
            temporary.delete();
            return false;
        }
        values.clear();
        values.putAll(copyValues(replacement));
        return true;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new EOFException("Invalid string length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static Map<String, Object> copyValues(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value);
            copy.put(entry.getKey(), value);
        }
        return copy;
    }

    private final class EditorImpl implements Editor {
        private final Map<String, Object> updates = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clear;

        @Override public Editor putString(String key, String value) { return update(key, value); }
        @Override public Editor putStringSet(String key, Set<String> values) { return update(key, values == null ? null : new HashSet<>(values)); }
        @Override public Editor putInt(String key, int value) { return update(key, value); }
        @Override public Editor putLong(String key, long value) { return update(key, value); }
        @Override public Editor putFloat(String key, float value) { return update(key, value); }
        @Override public Editor putBoolean(String key, boolean value) { return update(key, value); }
        @Override public Editor remove(String key) { updates.remove(key); removals.add(key); return this; }
        @Override public Editor clear() { clear = true; return this; }
        @Override public boolean commit() { return applyInternal(); }
        @Override public void apply() { applyInternal(); }

        private Editor update(String key, Object value) {
            if (key == null) throw new NullPointerException("key");
            removals.remove(key);
            updates.put(key, value);
            return this;
        }

        private boolean applyInternal() {
            List<String> changed = new ArrayList<>();
            boolean success;
            synchronized (SandboxSharedPreferences.this) {
                requireActive();
                Map<String, Object> next = copyValues(values);
                if (clear) {
                    changed.addAll(next.keySet());
                    next.clear();
                }
                for (String key : removals) if (next.remove(key) != null) changed.add(key);
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    Object old = next.get(entry.getKey());
                    Object value = entry.getValue();
                    if (value == null) next.remove(entry.getKey()); else next.put(entry.getKey(), value);
                    if (old == null ? value != null : !old.equals(value)) changed.add(entry.getKey());
                }
                success = persist(next);
            }
            if (success) {
                for (String key : changed) for (OnSharedPreferenceChangeListener listener : listeners) {
                    listener.onSharedPreferenceChanged(SandboxSharedPreferences.this, key);
                }
            }
            return success;
        }
    }
}
