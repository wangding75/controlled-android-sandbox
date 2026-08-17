package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

/**
 * Copies ContentProvider.call() extras into a bounded, ownership-safe Bundle.
 *
 * <p>Unlike query arguments, call extras are provider-defined and may contain nested Bundles,
 * primitive arrays and immutable framework values.  Treating them as a flat query Bundle breaks
 * real provider APIs; passing the original Bundle through would allow Binder handles or Guest
 * Parcelables to cross the Broker boundary.  This codec keeps the common Android value shapes,
 * recursively copies them, and rejects executable/object-owned values.</p>
 */
public final class ProviderCallBundleCodec {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES_PER_BUNDLE = 64;
    private static final int MAX_TOTAL_ENTRIES = 256;
    private static final int MAX_COLLECTION_LENGTH = 1024;
    private static final int MAX_STRING_LENGTH = 64 * 1024;
    private static final int MAX_TOTAL_BYTES = 1024 * 1024;

    private ProviderCallBundleCodec() { }

    public static Bundle copy(Bundle source) {
        if (source == null) return null;
        return copyBundle(source, 0, new Budget());
    }

    /**
     * Copies the ArrayMap-backed extras used by ContentProviderOperation.CALL.
     *
     * <p>The platform stores these extras in an implementation-owned map rather than a Bundle.
     * Converting that map through the same bounded value codec keeps direct {@code call()} and
     * batch {@code CALL} operations on one ownership and size policy.</p>
     */
    public static Bundle copyMap(Map<?, ?> source) {
        if (source == null) return null;
        if (source.size() > MAX_ENTRIES_PER_BUNDLE) {
            throw error("ENTRY_LIMIT_EXCEEDED");
        }
        Bundle out = new Bundle();
        Budget budget = new Budget();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw error("KEY_INVALID");
            if (key.length() > 256) throw error("KEY_INVALID");
            budget.entry(key.length() * 2 + 8);
            copyValue(out, key, entry.getValue(), 0, budget);
        }
        return out;
    }

    private static Bundle copyBundle(Bundle source, int depth, Budget budget) {
        if (depth > MAX_DEPTH) throw error("DEPTH_LIMIT_EXCEEDED");
        if (source.size() > MAX_ENTRIES_PER_BUNDLE) {
            throw error("ENTRY_LIMIT_EXCEEDED");
        }
        Bundle out = new Bundle();
        for (String key : source.keySet()) {
            if (key == null || key.length() > 256) throw error("KEY_INVALID");
            budget.entry(key.length() * 2 + 8);
            copyValue(out, key, source.get(key), depth, budget);
        }
        return out;
    }

    private static void copyValue(Bundle out, String key, Object value, int depth,
                                  Budget budget) {
        if (value == null) {
            out.putString(key, null);
        } else if (value instanceof String string) {
            budget.string(string);
            out.putString(key, string);
        } else if (value instanceof Boolean booleanValue) {
            out.putBoolean(key, booleanValue);
        } else if (value instanceof Byte byteValue) {
            out.putByte(key, byteValue);
        } else if (value instanceof Short shortValue) {
            out.putShort(key, shortValue);
        } else if (value instanceof Character character) {
            out.putChar(key, character);
        } else if (value instanceof Integer integer) {
            out.putInt(key, integer);
        } else if (value instanceof Long longValue) {
            out.putLong(key, longValue);
        } else if (value instanceof Float floatValue) {
            out.putFloat(key, floatValue);
        } else if (value instanceof Double doubleValue) {
            out.putDouble(key, doubleValue);
        } else if (value instanceof byte[] bytes) {
            budget.collection(bytes.length, bytes.length);
            out.putByteArray(key, bytes.clone());
        } else if (value instanceof String[] strings) {
            budget.collection(strings.length, 0);
            String[] copy = strings.clone();
            for (String string : copy) budget.string(string);
            out.putStringArray(key, copy);
        } else if (value instanceof Bundle nested) {
            copyNested(out, key, nested, depth, budget);
        } else if (value instanceof ArrayList<?> list) {
            copyList(out, key, list, depth, budget);
        } else if (value instanceof CharSequence sequence) {
            String string = sequence.toString();
            budget.string(string);
            // Do not retain a Guest-owned mutable CharSequence in the Host Bundle.  A String is
            // the immutable framework representation accepted by every Android Bundle version.
            invokeBundlePut(out, "putCharSequence", key, CharSequence.class, string);
        } else if (value instanceof Uri uri) {
            // Uri is immutable and its copy contains no Binder/Guest object ownership.
            invokeBundlePut(out, "putParcelable", key, android.os.Parcelable.class,
                    Uri.parse(uri.toString()));
        } else if (value instanceof ComponentName component) {
            invokeBundlePut(out, "putParcelable", key, android.os.Parcelable.class,
                    new ComponentName(component.getPackageName(), component.getClassName()));
        } else if (value instanceof IBinder) {
            throw error("BINDER_VALUE_REJECTED");
        } else if (value.getClass().isArray()
                && value.getClass().getComponentType().isPrimitive()) {
            copyPrimitiveArray(out, key, value, budget);
        } else {
            throw error("TYPE_UNSUPPORTED:" + key);
        }
    }

    private static void copyNested(Bundle out, String key, Bundle nested, int depth,
                                   Budget budget) {
        out.putBundle(key, copyBundle(nested, depth + 1, budget));
    }

    private static void copyList(Bundle out, String key, ArrayList<?> list, int depth,
                                 Budget budget) {
        if (list.size() > MAX_COLLECTION_LENGTH) throw error("COLLECTION_LIMIT_EXCEEDED");
        if (list.isEmpty()) {
            out.putStringArrayList(key, new ArrayList<>());
            return;
        }
        Object first = list.get(0);
        if (first instanceof String || first == null) {
            ArrayList<String> copy = new ArrayList<>(list.size());
            for (Object value : list) {
                if (value != null && !(value instanceof String)) {
                    throw error("LIST_TYPE_MISMATCH:" + key);
                }
                String string = (String) value;
                budget.string(string);
                copy.add(string);
            }
            budget.collection(list.size(), 0);
            out.putStringArrayList(key, copy);
            return;
        }
        if (first instanceof Bundle || first == null) {
            ArrayList<Bundle> copy = new ArrayList<>(list.size());
            for (Object value : list) {
                if (value != null && !(value instanceof Bundle)) {
                    throw error("LIST_TYPE_MISMATCH:" + key);
                }
                copy.add(value == null ? null : copyBundle((Bundle) value, depth + 1, budget));
            }
            budget.collection(list.size(), 0);
            out.putParcelableArrayList(key, copy);
            return;
        }
        throw error("LIST_TYPE_UNSUPPORTED:" + key);
    }

    private static void copyPrimitiveArray(Bundle out, String key, Object value, Budget budget) {
        int length = Array.getLength(value);
        budget.collection(length, primitiveBytes(value.getClass().getComponentType(), length));
        String suffix;
        Class<?> type = value.getClass().getComponentType();
        if (type == boolean.class) suffix = "Boolean";
        else if (type == int.class) suffix = "Int";
        else if (type == long.class) suffix = "Long";
        else if (type == float.class) suffix = "Float";
        else if (type == double.class) suffix = "Double";
        else if (type == short.class) suffix = "Short";
        else if (type == char.class) suffix = "Char";
        else throw error("ARRAY_TYPE_UNSUPPORTED:" + key);
        Object copy = Array.newInstance(type, length);
        System.arraycopy(value, 0, copy, 0, length);
        invokeBundlePut(out, "put" + suffix + "Array", key, copy.getClass(), copy);
    }

    private static int primitiveBytes(Class<?> type, int length) {
        if (type == boolean.class || type == byte.class) return length;
        if (type == short.class || type == char.class) return length * 2;
        if (type == int.class || type == float.class) return length * 4;
        return length * 8;
    }

    private static void invokeBundlePut(Bundle target, String name, String key,
                                        Class<?> valueType, Object value) {
        try {
            Method method = Bundle.class.getMethod(name, String.class, valueType);
            method.invoke(target, key, value);
        } catch (ReflectiveOperationException error) {
            throw error("FRAMEWORK_BUNDLE_METHOD_UNAVAILABLE:" + name);
        }
    }

    private static IllegalArgumentException error(String reason) {
        return new IllegalArgumentException("PROVIDER_CALL_EXTRAS_" + reason);
    }

    private static final class Budget {
        private int entries;
        private int bytes;

        void entry(int estimatedBytes) {
            if (++entries > MAX_TOTAL_ENTRIES) throw error("TOTAL_ENTRY_LIMIT_EXCEEDED");
            bytes(estimatedBytes);
        }

        void string(String value) {
            if (value == null) return;
            if (value.length() > MAX_STRING_LENGTH) throw error("STRING_LIMIT_EXCEEDED");
            bytes(value.length() * 2 + 8);
        }

        void collection(int count, int estimatedBytes) {
            if (count > MAX_COLLECTION_LENGTH) throw error("COLLECTION_LIMIT_EXCEEDED");
            bytes(estimatedBytes + 8);
        }

        private void bytes(int value) {
            if (value < 0 || bytes > MAX_TOTAL_BYTES - value) {
                throw error("BYTE_LIMIT_EXCEEDED");
            }
            bytes += value;
        }
    }
}
