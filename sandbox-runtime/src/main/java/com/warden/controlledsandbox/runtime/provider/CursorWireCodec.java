package com.warden.controlledsandbox.runtime.provider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Stable string encoding used inside Bundle rows without relying on Parcelable application classes. */
public final class CursorWireCodec {
    private CursorWireCodec() { }

    static String nullValue() { return "N"; }
    static String integer(long value) { return "I:" + value; }
    static String floating(double value) { return "F:" + Double.toString(value); }
    static String text(String value) {
        if (value == null) return nullValue();
        return "S:" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    static String blob(byte[] value) {
        if (value == null) return nullValue();
        return "B:" + Base64.getEncoder().encodeToString(value);
    }

    static Object decode(String encoded) {
        if (encoded == null || encoded.equals("N")) return null;
        if (encoded.startsWith("I:")) return Long.parseLong(encoded.substring(2));
        if (encoded.startsWith("F:")) return Double.parseDouble(encoded.substring(2));
        if (encoded.startsWith("S:")) {
            return new String(Base64.getDecoder().decode(encoded.substring(2)), StandardCharsets.UTF_8);
        }
        if (encoded.startsWith("B:")) return Base64.getDecoder().decode(encoded.substring(2));
        throw new IllegalArgumentException("UNKNOWN_CURSOR_WIRE_TYPE");
    }
}
