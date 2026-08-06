package com.warden.controlledsandbox.domain.packageinfo.manifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal, bounds-checked Android binary XML parser for manifest metadata. */
public final class BinaryXmlManifestParser {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int UTF8_FLAG = 0x00000100;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int NO_INDEX = 0xFFFFFFFF;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;

    public ManifestModel parse(InputStream input) throws IOException {
        byte[] bytes = readLimited(input, MAX_MANIFEST_BYTES);
        return parse(bytes);
    }

    public ManifestModel parse(byte[] bytes) throws IOException {
        if (bytes.length < 8) throw new IOException("Manifest is too small");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int rootType = u16(buffer, 0);
        int rootSize = i32(buffer, 4);
        if (rootType != RES_XML_TYPE || rootSize < 8 || rootSize > bytes.length) {
            throw new IOException("Not a supported Android binary XML document");
        }

        StringPool strings = null;
        ManifestModel model = new ManifestModel();
        Deque<ElementContext> stack = new ArrayDeque<>();
        int offset = u16(buffer, 2);
        if (offset < 8) offset = 8;
        while (offset + 8 <= rootSize) {
            int type = u16(buffer, offset);
            int headerSize = u16(buffer, offset + 2);
            int size = i32(buffer, offset + 4);
            if (headerSize < 8 || size < headerSize || offset + size > rootSize) {
                throw new IOException("Invalid XML chunk at offset " + offset);
            }
            if (type == RES_STRING_POOL_TYPE) {
                strings = parseStringPool(buffer, offset, headerSize, size);
            } else if (type == RES_XML_START_ELEMENT_TYPE) {
                if (strings == null) throw new IOException("Start element encountered before string pool");
                Element element = parseStartElement(buffer, offset, headerSize, size, strings);
                onStart(model, stack, element);
                stack.push(new ElementContext(element.name, element.component, element.intentState));
                finalizeVisibleIntentStates(stack);
            } else if (type == RES_XML_END_ELEMENT_TYPE) {
                finalizeVisibleIntentStates(stack);
                if (!stack.isEmpty()) stack.pop();
            }
            offset += size;
        }
        if (model.packageName().trim().isEmpty()) throw new IOException("Manifest package name is missing");
        return model;
    }

    private static void onStart(ManifestModel model, Deque<ElementContext> stack, Element element) {
        switch (element.name) {
            case "manifest" -> {
                model.packageName(element.stringAttr("package"));
                model.splitName(element.stringAttr("split"));
                model.configForSplit(element.stringAttr("configForSplit"));
                model.usesSplit(element.stringAttr("usesSplit"));
                model.featureSplit(element.boolAttr("isFeatureSplit", false));
            }
            case "uses-sdk" -> {
                model.minSdk(element.intAttr("minSdkVersion", 0));
                model.targetSdk(element.intAttr("targetSdkVersion", 0));
            }
            case "uses-permission", "uses-permission-sdk-23" -> model.addPermission(element.stringAttr("name"));
            case "uses-library" -> model.addSharedLibrary(new ManifestModel.SharedLibraryDependency(
                    ManifestModel.SharedLibraryDependency.Kind.JAVA, element.stringAttr("name"),
                    element.boolAttr("required", true), 0L, ""));
            case "uses-native-library" -> model.addSharedLibrary(new ManifestModel.SharedLibraryDependency(
                    ManifestModel.SharedLibraryDependency.Kind.NATIVE, element.stringAttr("name"),
                    element.boolAttr("required", true), 0L, ""));
            case "uses-sdk-library" -> model.addSharedLibrary(new ManifestModel.SharedLibraryDependency(
                    ManifestModel.SharedLibraryDependency.Kind.SDK, element.stringAttr("name"), true,
                    element.intAttr("versionMajor", 0), element.stringAttr("certDigest")));
            case "uses-static-library" -> model.addSharedLibrary(new ManifestModel.SharedLibraryDependency(
                    ManifestModel.SharedLibraryDependency.Kind.STATIC, element.stringAttr("name"), true,
                    element.intAttr("version", 0), element.stringAttr("certDigest")));
            case "library" -> model.addProvidedSharedLibrary(element.stringAttr("name"));
            case "instrumentation" -> model.addInstrumentation(new ManifestModel.Instrumentation(
                    model.resolveClassName(element.stringAttr("name")),
                    element.stringAttr("targetPackage"), element.stringAttr("targetProcesses"),
                    element.boolAttr("handleProfiling", false),
                    element.boolAttr("functionalTest", false), element.boolAttr("enabled", true)));
            case "application" -> {
                model.applicationClass(element.stringAttr("name"));
                model.applicationPermission(element.stringAttr("permission"));
            }
            case "activity" -> {
                ManifestModel.Component component = component(model, element, element.stringAttr("name"));
                element.component = component;
                model.addActivity(component);
            }
            case "activity-alias" -> {
                String target = element.stringAttr("targetActivity");
                ManifestModel.Component component = component(model, element, target.trim().isEmpty() ? element.stringAttr("name") : target);
                element.component = component;
                model.addActivity(component);
            }
            case "service" -> {
                ManifestModel.Component component = component(model, element, element.stringAttr("name"));
                element.component = component;
                model.addService(component);
            }
            case "receiver" -> {
                ManifestModel.Component component = component(model, element, element.stringAttr("name"));
                element.component = component;
                model.addReceiver(component);
            }
            case "provider" -> {
                ManifestModel.Component component = providerComponent(model, element, element.stringAttr("name"));
                element.component = component;
                model.addProvider(component);
            }
            case "path-permission" -> addProviderPathPermission(stack, element);
            case "grant-uri-permission" -> addProviderGrantRule(stack, element);
            case "action" -> {
                ManifestModel.Component component = nearestComponent(stack);
                IntentState state = nearestIntent(stack);
                String actionName = element.stringAttr("name");
                if (component != null) component.addAction(actionName);
                if (state != null && state.filter != null) state.filter.addAction(actionName);
                if (component != null && "android.intent.action.MAIN".equals(actionName)) {
                    if (state != null) state.mainAction = true;
                }
            }
            case "category" -> {
                ManifestModel.Component component = nearestComponent(stack);
                if (component != null) {
                    String value = element.stringAttr("name");
                    IntentState state = nearestIntent(stack);
                    if (state != null && state.filter != null) state.filter.addCategory(value);
                    if (state != null && ("android.intent.category.LAUNCHER".equals(value)
                            || "android.intent.category.LEANBACK_LAUNCHER".equals(value))) {
                        state.launcherCategory = true;
                    }
                }
            }
            case "data" -> {
                IntentState state = nearestIntent(stack);
                if (state != null && state.filter != null) {
                    ManifestModel.DataRule rule = new ManifestModel.DataRule(
                            element.stringAttr("scheme"), element.stringAttr("host"),
                            element.stringAttr("path"), element.stringAttr("pathPrefix"),
                            element.stringAttr("pathPattern"), element.stringAttr("mimeType"));
                    if (!rule.empty()) state.filter.addDataRule(rule);
                }
            }
            case "intent-filter" -> {
                ManifestModel.Component component = nearestComponent(stack);
                ManifestModel.IntentFilter filter = component == null ? null
                        : component.addIntentFilter(element.intAttr("priority", 0));
                element.intentState = new IntentState(component, filter);
            }
            default -> { }
        }

    }

    private static void finalizeVisibleIntentStates(Deque<ElementContext> stack) {
        for (ElementContext context : stack) {
            if (context.intentState != null && context.intentState.mainAction
                    && context.intentState.launcherCategory && context.intentState.component != null) {
                context.intentState.component.launcher(true);
            }
        }
    }

    private static void addProviderPathPermission(Deque<ElementContext> stack, Element element) {
        ManifestModel.Component component = nearestProvider(stack);
        if (component == null) return;
        String readPermission = element.stringAttr("readPermission");
        String writePermission = element.stringAttr("writePermission");
        if (readPermission.trim().isEmpty()) readPermission = component.readPermission();
        if (writePermission.trim().isEmpty()) writePermission = component.writePermission();
        component.addProviderPathRule(new ManifestModel.ProviderPathRule(
                element.stringAttr("path"), element.stringAttr("pathPrefix"),
                element.stringAttr("pathPattern"), readPermission, writePermission, false));
    }

    private static void addProviderGrantRule(Deque<ElementContext> stack, Element element) {
        ManifestModel.Component component = nearestProvider(stack);
        if (component == null) return;
        component.addProviderPathRule(new ManifestModel.ProviderPathRule(
                element.stringAttr("path"), element.stringAttr("pathPrefix"),
                element.stringAttr("pathPattern"), "", "", true));
    }

    private static ManifestModel.Component nearestProvider(Deque<ElementContext> stack) {
        ManifestModel.Component component = nearestComponent(stack);
        return component != null && !component.authorities().isEmpty() ? component : null;
    }

    private static ManifestModel.Component component(ManifestModel model, Element element, String rawClassName) {
        String className = model.resolveClassName(rawClassName);
        String process = element.stringAttr("process");
        boolean exported = element.boolAttr("exported", false);
        boolean exportedExplicit = element.hasAttr("exported");
        boolean enabled = element.boolAttr("enabled", true);
        boolean isolated = element.boolAttr("isolatedProcess", false);
        String permission = element.stringAttr("permission");
        if (permission.trim().isEmpty()) permission = model.applicationPermission();
        return new ManifestModel.Component(className, process, exported, exportedExplicit, enabled, isolated,
                element.stringAttr("authorities"), permission);
    }


    private static ManifestModel.Component providerComponent(ManifestModel model, Element element,
                                                             String rawClassName) {
        String className = model.resolveClassName(rawClassName);
        String process = element.stringAttr("process");
        boolean exported = element.boolAttr("exported", false);
        boolean exportedExplicit = element.hasAttr("exported");
        boolean enabled = element.boolAttr("enabled", true);
        boolean isolated = element.boolAttr("isolatedProcess", false);
        String permission = element.stringAttr("permission");
        if (permission.trim().isEmpty()) permission = model.applicationPermission();
        String readPermission = element.stringAttr("readPermission");
        String writePermission = element.stringAttr("writePermission");
        if (readPermission.trim().isEmpty()) readPermission = permission;
        if (writePermission.trim().isEmpty()) writePermission = permission;
        return new ManifestModel.Component(className, process, exported, exportedExplicit, enabled, isolated,
                element.stringAttr("authorities"), permission, readPermission, writePermission,
                element.boolAttr("grantUriPermissions", false));
    }

    private static ManifestModel.Component nearestComponent(Deque<ElementContext> stack) {
        for (ElementContext context : stack) if (context.component != null) return context.component;
        return null;
    }

    private static IntentState nearestIntent(Deque<ElementContext> stack) {
        for (ElementContext context : stack) if (context.intentState != null) return context.intentState;
        return null;
    }

    private static Element parseStartElement(ByteBuffer buffer, int chunkOffset, int headerSize,
                                             int chunkSize, StringPool pool) throws IOException {
        if (headerSize < 16 || chunkSize < 36) throw new IOException("Invalid start element chunk");
        int extension = chunkOffset + 16;
        int nameIndex = i32(buffer, extension + 4);
        String name = pool.get(nameIndex);
        int attributeStart = u16(buffer, extension + 8);
        int attributeSize = u16(buffer, extension + 10);
        int attributeCount = u16(buffer, extension + 12);
        if (attributeSize < 20 || attributeCount > 4096) throw new IOException("Invalid attribute table");
        int attributesOffset = extension + attributeStart;
        long end = (long) attributesOffset + (long) attributeCount * attributeSize;
        if (attributesOffset < chunkOffset || end > (long) chunkOffset + chunkSize) {
            throw new IOException("Attribute table is outside element chunk");
        }
        Map<String, Value> attributes = new HashMap<>();
        for (int index = 0; index < attributeCount; index++) {
            int attributeOffset = attributesOffset + index * attributeSize;
            int attributeName = i32(buffer, attributeOffset + 4);
            int rawValue = i32(buffer, attributeOffset + 8);
            int valueSize = u16(buffer, attributeOffset + 12);
            if (valueSize < 8) throw new IOException("Invalid typed value");
            int dataType = buffer.get(attributeOffset + 15) & 0xFF;
            int data = i32(buffer, attributeOffset + 16);
            String key = pool.get(attributeName);
            String text = rawValue == NO_INDEX ? "" : pool.get(rawValue);
            if (text.isEmpty() && dataType == TYPE_STRING) text = pool.get(data);
            attributes.put(key, new Value(text, dataType, data));
        }
        return new Element(name, attributes);
    }

    private static StringPool parseStringPool(ByteBuffer buffer, int offset, int headerSize, int size)
            throws IOException {
        if (headerSize < 28 || size < headerSize) throw new IOException("Invalid string pool");
        int stringCount = i32(buffer, offset + 8);
        int flags = i32(buffer, offset + 16);
        int stringsStart = i32(buffer, offset + 20);
        if (stringCount < 0 || stringCount > 200_000) throw new IOException("Invalid string count");
        int offsetsStart = offset + headerSize;
        if ((long) offsetsStart + (long) stringCount * 4 > (long) offset + size) {
            throw new IOException("String offsets exceed pool");
        }
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        List<String> strings = new ArrayList<>(stringCount);
        for (int index = 0; index < stringCount; index++) {
            int relative = i32(buffer, offsetsStart + index * 4);
            int stringOffset = offset + stringsStart + relative;
            if (stringOffset < offset || stringOffset >= offset + size) throw new IOException("Invalid string offset");
            strings.add(utf8 ? decodeUtf8(buffer, stringOffset, offset + size)
                    : decodeUtf16(buffer, stringOffset, offset + size));
        }
        return new StringPool(strings);
    }

    private static String decodeUtf8(ByteBuffer buffer, int offset, int limit) throws IOException {
        Length first = readLength8(buffer, offset, limit);
        Length second = readLength8(buffer, first.next, limit);
        int byteLength = second.value;
        if (byteLength < 0 || second.next + byteLength >= limit) throw new IOException("Invalid UTF-8 string length");
        byte[] data = new byte[byteLength];
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(second.next);
        duplicate.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String decodeUtf16(ByteBuffer buffer, int offset, int limit) throws IOException {
        if (offset + 2 > limit) throw new IOException("Truncated UTF-16 string");
        int first = u16(buffer, offset);
        int length;
        int cursor;
        if ((first & 0x8000) != 0) {
            if (offset + 4 > limit) throw new IOException("Truncated UTF-16 length");
            length = ((first & 0x7FFF) << 16) | u16(buffer, offset + 2);
            cursor = offset + 4;
        } else {
            length = first;
            cursor = offset + 2;
        }
        long byteLength = (long) length * 2;
        if (byteLength < 0 || cursor + byteLength + 2 > limit) throw new IOException("Invalid UTF-16 string length");
        byte[] data = new byte[(int) byteLength];
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(cursor);
        duplicate.get(data);
        return new String(data, StandardCharsets.UTF_16LE);
    }

    private static Length readLength8(ByteBuffer buffer, int offset, int limit) throws IOException {
        if (offset >= limit) throw new IOException("Truncated UTF-8 length");
        int first = buffer.get(offset) & 0xFF;
        if ((first & 0x80) == 0) return new Length(first, offset + 1);
        if (offset + 1 >= limit) throw new IOException("Truncated UTF-8 length");
        return new Length(((first & 0x7F) << 8) | (buffer.get(offset + 1) & 0xFF), offset + 2);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Manifest exceeds " + limit + " bytes");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static int u16(ByteBuffer buffer, int offset) { return buffer.getShort(offset) & 0xFFFF; }
    private static int i32(ByteBuffer buffer, int offset) { return buffer.getInt(offset); }

    private static final class Length {
        final int value; final int next;
        Length(int value, int next) { this.value = value; this.next = next; }
    }
    private static final class Value {
        final String text; final int dataType; final int data;
        Value(String text, int dataType, int data) { this.text = text; this.dataType = dataType; this.data = data; }
        boolean asBoolean(boolean fallback) {
            if (dataType == TYPE_INT_BOOLEAN) return data != 0;
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
            return fallback;
        }
        int asInt(int fallback) {
            if (dataType == TYPE_INT_DEC || dataType == TYPE_INT_BOOLEAN) return data;
            try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { return fallback; }
        }
    }

    private static final class Element {
        final String name;
        final Map<String, Value> attributes;
        ManifestModel.Component component;
        IntentState intentState;
        Element(String name, Map<String, Value> attributes) { this.name = name; this.attributes = attributes; }
        boolean hasAttr(String name) { return attributes.containsKey(name); }
        String stringAttr(String name) { Value value = attributes.get(name); return value == null ? "" : value.text; }
        boolean boolAttr(String name, boolean fallback) { Value value = attributes.get(name); return value == null ? fallback : value.asBoolean(fallback); }
        int intAttr(String name, int fallback) { Value value = attributes.get(name); return value == null ? fallback : value.asInt(fallback); }
    }

    private static final class ElementContext {
        final String name;
        final ManifestModel.Component component;
        final IntentState intentState;
        ElementContext(String name, ManifestModel.Component component) {
            this.name = name;
            this.component = component;
            this.intentState = null;
        }
        ElementContext(String name, ManifestModel.Component component, IntentState intentState) {
            this.name = name;
            this.component = component;
            this.intentState = intentState;
        }
    }

    private static final class IntentState {
        final ManifestModel.Component component;
        final ManifestModel.IntentFilter filter;
        boolean mainAction;
        boolean launcherCategory;
        IntentState(ManifestModel.Component component, ManifestModel.IntentFilter filter) {
            this.component = component;
            this.filter = filter;
        }
    }

    private static final class StringPool {
        final List<String> values;
        StringPool(List<String> values) { this.values = values; }
        String get(int index) {
            if (index == NO_INDEX || index < 0 || index >= values.size()) return "";
            return values.get(index);
        }
    }
}
