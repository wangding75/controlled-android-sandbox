package com.warden.controlledsandbox.runtime.protocol;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, type-preserving wire codec for ordered-broadcast result extras. */
public final class OrderedBroadcastResultExtrasCodec {
    private static final char NULL = 'N';
    private static final char STRING = 'S';
    private static final char INTEGER = 'I';
    private static final char LONG = 'L';
    private static final char BOOLEAN = 'Z';
    private static final char FLOAT = 'F';
    private static final char DOUBLE = 'D';
    private static final char BYTE = 'Y';
    private static final char SHORT = 'H';
    private static final char CHARACTER = 'C';
    private static final char BYTES = 'B';
    private static final char STRING_ARRAY = 'A';
    private static final char STRING_LIST = 'R';
    private static final Map<Class<?>, ValueEncoder> ENCODERS = encoders();
    private static final Map<Character, ValueDecoder> DECODERS = decoders();

    private OrderedBroadcastResultExtrasCodec() { }

    public static Map<String, String> encode(Bundle extras) {
        if (extras == null || extras.keySet().isEmpty()) return Collections.emptyMap();
        if (extras.keySet().size() > OrderedBroadcastState.MAX_EXTRA_ENTRIES) {
            throw new IllegalArgumentException("BROADCAST_RESULT_EXTRAS_TOO_MANY");
        }
        LinkedHashMap<String, String> encoded = new LinkedHashMap<>();
        for (String key : extras.keySet()) {
            validateKey(key);
            String value = encodeValue(extras.get(key));
            if (value.length() > OrderedBroadcastState.MAX_EXTRA_VALUE_CHARS) {
                throw new IllegalArgumentException("BROADCAST_RESULT_EXTRA_VALUE_TOO_LARGE");
            }
            encoded.put(key, value);
        }
        return Collections.unmodifiableMap(encoded);
    }

    public static Bundle decode(Map<String, String> encoded) {
        Bundle extras = new Bundle();
        if (encoded == null || encoded.isEmpty()) return extras;
        if (encoded.size() > OrderedBroadcastState.MAX_EXTRA_ENTRIES) {
            throw new IllegalArgumentException("BROADCAST_RESULT_EXTRAS_TOO_MANY");
        }
        for (Map.Entry<String, String> entry : encoded.entrySet()) {
            String key = entry.getKey();
            validateKey(key);
            decodeValue(extras, key, entry.getValue());
        }
        return extras;
    }

    public static void validate(Bundle extras) { encode(extras); }

    private static String encodeValue(Object value) {
        if (value == null) return String.valueOf(NULL);
        ValueEncoder encoder = ENCODERS.get(value.getClass());
        if (encoder == null) throw unsupported(value);
        return encoder.encode(value);
    }

    private static void decodeValue(Bundle target, String key, String encoded) {
        if (encoded == null || encoded.isEmpty()) throw invalid();
        ValueDecoder decoder = DECODERS.get(encoded.charAt(0));
        if (decoder == null) throw invalid();
        try {
            decoder.decode(target, key, encoded.substring(1));
        } catch (IllegalArgumentException error) {
            if ("BROADCAST_RESULT_EXTRA_WIRE_INVALID".equals(error.getMessage())) throw error;
            throw new IllegalArgumentException("BROADCAST_RESULT_EXTRA_WIRE_INVALID", error);
        }
    }

    private static Map<Class<?>, ValueEncoder> encoders() {
        LinkedHashMap<Class<?>, ValueEncoder> values = new LinkedHashMap<>();
        values.put(String.class, value -> STRING + (String) value);
        values.put(Integer.class, value -> INTEGER + value.toString());
        values.put(Long.class, value -> LONG + value.toString());
        values.put(Boolean.class, value -> BOOLEAN + ((Boolean) value ? "1" : "0"));
        values.put(Float.class, value -> FLOAT + value.toString());
        values.put(Double.class, value -> DOUBLE + value.toString());
        values.put(Byte.class, value -> BYTE + value.toString());
        values.put(Short.class, value -> SHORT + value.toString());
        values.put(Character.class, value -> CHARACTER + Integer.toString((Character) value));
        values.put(byte[].class, value -> BYTES
                + Base64.getEncoder().encodeToString((byte[]) value));
        values.put(String[].class, value -> STRING_ARRAY
                + encodeStrings(java.util.Arrays.asList((String[]) value)));
        values.put(ArrayList.class, OrderedBroadcastResultExtrasCodec::encodeStringList);
        return Collections.unmodifiableMap(values);
    }

    private static Map<Character, ValueDecoder> decoders() {
        LinkedHashMap<Character, ValueDecoder> values = new LinkedHashMap<>();
        values.put(NULL, OrderedBroadcastResultExtrasCodec::decodeNull);
        values.put(STRING, Bundle::putString);
        values.put(INTEGER, (target, key, payload) -> target.putInt(key, Integer.parseInt(payload)));
        values.put(LONG, (target, key, payload) -> target.putLong(key, Long.parseLong(payload)));
        values.put(BOOLEAN, OrderedBroadcastResultExtrasCodec::decodeBoolean);
        values.put(FLOAT, (target, key, payload) -> target.putFloat(key, Float.parseFloat(payload)));
        values.put(DOUBLE, (target, key, payload) -> target.putDouble(key, Double.parseDouble(payload)));
        values.put(BYTE, (target, key, payload) -> target.putByte(key, Byte.parseByte(payload)));
        values.put(SHORT, (target, key, payload) -> target.putShort(key, Short.parseShort(payload)));
        values.put(CHARACTER, OrderedBroadcastResultExtrasCodec::decodeCharacter);
        values.put(BYTES, (target, key, payload) -> target.putByteArray(
                key, Base64.getDecoder().decode(payload)));
        values.put(STRING_ARRAY, (target, key, payload) -> target.putStringArray(
                key, decodeStrings(payload).toArray(new String[0])));
        values.put(STRING_LIST, (target, key, payload) -> target.putStringArrayList(
                key, decodeStrings(payload)));
        return Collections.unmodifiableMap(values);
    }

    private static String encodeStringList(Object value) {
        ArrayList<String> strings = new ArrayList<>();
        for (Object item : (ArrayList<?>) value) {
            if (item != null && !(item instanceof String)) throw unsupported(value);
            strings.add((String) item);
        }
        return STRING_LIST + encodeStrings(strings);
    }

    private static void decodeNull(Bundle target, String key, String payload) {
        if (!payload.isEmpty()) throw invalid();
        target.putString(key, null);
    }

    private static void decodeBoolean(Bundle target, String key, String payload) {
        if (!"0".equals(payload) && !"1".equals(payload)) throw invalid();
        target.putBoolean(key, "1".equals(payload));
    }

    private static void decodeCharacter(Bundle target, String key, String payload) {
        int codePoint = Integer.parseInt(payload);
        if (codePoint < Character.MIN_VALUE || codePoint > Character.MAX_VALUE) throw invalid();
        target.putChar(key, (char) codePoint);
    }

    private static String encodeStrings(java.util.List<String> values) {
        StringBuilder out = new StringBuilder();
        out.append(values.size()).append(';');
        Base64.Encoder encoder = Base64.getEncoder();
        for (String value : values) {
            if (value == null) out.append('-');
            else out.append(encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
            out.append(';');
        }
        return out.toString();
    }

    private static ArrayList<String> decodeStrings(String payload) {
        int separator = payload.indexOf(';');
        if (separator <= 0) throw invalid();
        int count = Integer.parseInt(payload.substring(0, separator));
        if (count < 0 || count > OrderedBroadcastState.MAX_EXTRA_ENTRIES) throw invalid();
        ArrayList<String> values = new ArrayList<>(count);
        int cursor = separator + 1;
        Base64.Decoder decoder = Base64.getDecoder();
        for (int index = 0; index < count; index++) {
            int end = payload.indexOf(';', cursor);
            if (end < 0) throw invalid();
            String item = payload.substring(cursor, end);
            values.add("-".equals(item) ? null
                    : new String(decoder.decode(item), StandardCharsets.UTF_8));
            cursor = end + 1;
        }
        if (cursor != payload.length()) throw invalid();
        return values;
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty() || key.length() > OrderedBroadcastState.MAX_EXTRA_KEY_CHARS) {
            throw new IllegalArgumentException("BROADCAST_RESULT_EXTRA_KEY_INVALID");
        }
    }

    private static IllegalArgumentException unsupported(Object value) {
        return new IllegalArgumentException("BROADCAST_RESULT_EXTRA_TYPE_UNSUPPORTED:"
                + value.getClass().getName());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("BROADCAST_RESULT_EXTRA_WIRE_INVALID");
    }

    @FunctionalInterface private interface ValueEncoder { String encode(Object value); }
    @FunctionalInterface private interface ValueDecoder {
        void decode(Bundle target, String key, String payload);
    }
}
