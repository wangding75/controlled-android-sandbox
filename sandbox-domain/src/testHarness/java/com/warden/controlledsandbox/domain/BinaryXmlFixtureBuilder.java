package com.warden.controlledsandbox.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BinaryXmlFixtureBuilder {
    private static final int NO_INDEX = 0xFFFFFFFF;
    private final List<String> strings = new ArrayList<>();
    private final Map<String, Integer> indexes = new LinkedHashMap<>();
    private final List<byte[]> nodes = new ArrayList<>();

    int string(String value) { return indexes.computeIfAbsent(value, key -> { strings.add(key); return strings.size() - 1; }); }

    BinaryXmlFixtureBuilder start(String name, Attr... attrs) {
        int nameIndex = string(name);
        int size = 36 + attrs.length * 20;
        ByteBuffer b = buffer(size);
        chunkHeader(b, 0x0102, 16, size);
        b.putInt(1).putInt(NO_INDEX);
        b.putInt(NO_INDEX).putInt(nameIndex);
        b.putShort((short) 20).putShort((short) 20).putShort((short) attrs.length);
        b.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (Attr attr : attrs) {
            b.putInt(NO_INDEX).putInt(string(attr.name)).putInt(attr.raw == null ? NO_INDEX : string(attr.raw));
            b.putShort((short) 8).put((byte) 0).put((byte) attr.type).putInt(attr.data(this));
        }
        nodes.add(b.array());
        return this;
    }

    BinaryXmlFixtureBuilder end(String name) {
        ByteBuffer b = buffer(24);
        chunkHeader(b, 0x0103, 16, 24);
        b.putInt(1).putInt(NO_INDEX).putInt(NO_INDEX).putInt(string(name));
        nodes.add(b.array());
        return this;
    }

    byte[] build() throws IOException {
        byte[] pool = stringPool();
        int total = 8 + pool.length;
        for (byte[] node : nodes) total += node.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        ByteBuffer header = buffer(8);
        chunkHeader(header, 0x0003, 8, total);
        out.write(header.array()); out.write(pool);
        for (byte[] node : nodes) out.write(node);
        return out.toByteArray();
    }

    private byte[] stringPool() throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size();
            byte[] utf8 = strings.get(i).getBytes(StandardCharsets.UTF_8);
            writeLength8(data, strings.get(i).length());
            writeLength8(data, utf8.length);
            data.write(utf8); data.write(0);
        }
        while ((data.size() & 3) != 0) data.write(0);
        int stringsStart = 28 + strings.size() * 4;
        int size = stringsStart + data.size();
        ByteBuffer b = buffer(size);
        chunkHeader(b, 0x0001, 28, size);
        b.putInt(strings.size()).putInt(0).putInt(0x100).putInt(stringsStart).putInt(0);
        for (int offset : offsets) b.putInt(offset);
        b.put(data.toByteArray());
        return b.array();
    }

    private static void writeLength8(ByteArrayOutputStream out, int value) {
        if (value < 0x80) out.write(value);
        else { out.write(((value >> 8) & 0x7F) | 0x80); out.write(value & 0xFF); }
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static void chunkHeader(ByteBuffer b, int type, int headerSize, int size) { b.putShort((short) type).putShort((short) headerSize).putInt(size); }

    static Attr text(String name, String value) { return new Attr(name, value, 0x03, 0); }
    static Attr bool(String name, boolean value) { return new Attr(name, null, 0x12, value ? 1 : 0); }
    static Attr integer(String name, int value) { return new Attr(name, null, 0x10, value); }

    static final class Attr {
        final String name; final String raw; final int type; final int literalData;
        Attr(String name, String raw, int type, int literalData) { this.name = name; this.raw = raw; this.type = type; this.literalData = literalData; }
        int data(BinaryXmlFixtureBuilder owner) { return type == 0x03 ? owner.string(raw) : literalData; }
    }
}
