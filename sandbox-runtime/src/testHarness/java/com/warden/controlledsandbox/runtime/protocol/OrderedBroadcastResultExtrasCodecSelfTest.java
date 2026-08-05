package com.warden.controlledsandbox.runtime.protocol;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

public final class OrderedBroadcastResultExtrasCodecSelfTest {
    private OrderedBroadcastResultExtrasCodecSelfTest() { }

    public static void main(String[] args) {
        primitiveRoundTrip();
        collectionRoundTrip();
        invalidPayloadsAreRejected();
        System.out.println("PASS ordered broadcast result extras codec self-test");
    }

    private static void primitiveRoundTrip() {
        Bundle source = new Bundle();
        source.putString("string", "value");
        source.putString("nullable", null);
        source.putInt("int", 7);
        source.putLong("long", 9L);
        source.putBoolean("boolean", true);
        source.putFloat("float", 1.5f);
        source.putDouble("double", 2.5d);
        source.putByte("byte", (byte) 3);
        source.putShort("short", (short) 4);
        source.putChar("char", 'x');
        source.putByteArray("bytes", new byte[] {1, 2, 3});

        Bundle restored = OrderedBroadcastResultExtrasCodec.decode(
                OrderedBroadcastResultExtrasCodec.encode(source));
        require("value".equals(restored.get("string")), "string result extra");
        require(restored.keySet().contains("nullable") && restored.get("nullable") == null,
                "null result extra");
        require(Integer.valueOf(7).equals(restored.get("int")), "int result extra");
        require(Long.valueOf(9L).equals(restored.get("long")), "long result extra");
        require(Boolean.TRUE.equals(restored.get("boolean")), "boolean result extra");
        require(Float.valueOf(1.5f).equals(restored.get("float")), "float result extra");
        require(Double.valueOf(2.5d).equals(restored.get("double")), "double result extra");
        require(Byte.valueOf((byte) 3).equals(restored.get("byte")), "byte result extra");
        require(Short.valueOf((short) 4).equals(restored.get("short")), "short result extra");
        require(Character.valueOf('x').equals(restored.get("char")), "char result extra");
        require(Arrays.equals(new byte[] {1, 2, 3}, (byte[]) restored.get("bytes")),
                "byte array result extra");
    }

    private static void collectionRoundTrip() {
        Bundle source = new Bundle();
        source.putStringArray("array", new String[] {"a", null, "b"});
        ArrayList<String> list = new ArrayList<>();
        list.add("first");
        list.add(null);
        list.add("last");
        source.putStringArrayList("list", list);
        Bundle restored = OrderedBroadcastResultExtrasCodec.decode(
                OrderedBroadcastResultExtrasCodec.encode(source));
        require(Arrays.equals(new String[] {"a", null, "b"},
                        (String[]) restored.get("array")), "String array result extra");
        require(list.equals(restored.getStringArrayList("list")), "String list result extra");
    }

    private static void invalidPayloadsAreRejected() {
        Bundle unsupported = new Bundle();
        unsupported.putBundle("nested", new Bundle());
        boolean unsupportedDenied = false;
        try { OrderedBroadcastResultExtrasCodec.encode(unsupported); }
        catch (IllegalArgumentException expected) {
            unsupportedDenied = expected.getMessage().startsWith(
                    "BROADCAST_RESULT_EXTRA_TYPE_UNSUPPORTED");
        }
        require(unsupportedDenied, "unsupported ordered result extra accepted");

        boolean malformedDenied = false;
        try { OrderedBroadcastResultExtrasCodec.decode(Map.of("bad", "Zmaybe")); }
        catch (IllegalArgumentException expected) {
            malformedDenied = "BROADCAST_RESULT_EXTRA_WIRE_INVALID".equals(expected.getMessage());
        }
        require(malformedDenied, "malformed ordered result extra accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
