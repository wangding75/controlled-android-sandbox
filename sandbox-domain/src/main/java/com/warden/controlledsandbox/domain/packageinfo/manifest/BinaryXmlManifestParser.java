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
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
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
                stack.push(new ElementContext(element.name, element.component, element.intentState,
                        element.queryIntent));
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
                model.versionCode(element.intAttr("versionCode", 0));
                model.versionName(element.stringAttr("versionName"));
                model.compileSdk(element.intAttr("compileSdkVersion", 0));
                model.sharedUserId(element.stringAttr("sharedUserId"));
                model.installLocation(element.stringAttr("installLocation"));
                model.isolatedSplits(element.boolAttr("isolatedSplits", false));
            }
            case "uses-sdk" -> {
                model.minSdk(element.intAttr("minSdkVersion", 0));
                model.targetSdk(element.intAttr("targetSdkVersion", 0));
            }
            case "uses-permission", "uses-permission-sdk-23" -> model.addPermission(element.stringAttr("name"));
            case "uses-feature" -> model.addUsesFeature(new ManifestModel.UsesFeature(
                    element.stringAttr("name"), element.intAttr("glEsVersion", 0),
                    element.boolAttr("required", true)));
            case "property" -> {
                if (stack.isEmpty() || "application".equals(stack.peek().name)
                        || "manifest".equals(stack.peek().name)) {
                    model.addProperty(new ManifestModel.ManifestProperty(
                            element.stringAttr("name"), element.stringAttr("value"),
                            element.intAttr("resource", 0)));
                }
            }
            case "permission", "permission-tree" -> model.addPermissionDeclaration(
                    new ManifestModel.PermissionDeclaration(
                            model.resolveClassName(element.stringAttr("name")),
                            model.resolveClassName(element.stringAttr("group")),
                            element.stringAttr("label"), element.stringAttr("description"),
                            element.intAttr("label", 0), element.intAttr("description", 0),
                            element.intAttr("icon", 0), element.intAttr("protectionLevel", 0),
                            element.intAttr("flags", 0), "permission-tree".equals(element.name)));
            case "permission-group" -> model.addPermissionGroup(
                    new ManifestModel.PermissionGroupDeclaration(
                            model.resolveClassName(element.stringAttr("name")),
                            element.stringAttr("label"), element.stringAttr("description"),
                            element.intAttr("label", 0), element.intAttr("description", 0),
                            element.intAttr("icon", 0), element.intAttr("request", 0),
                            element.intAttr("priority", 0), element.intAttr("flags", 0)));
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
            case "package" -> {
                if (insideQueries(stack)) model.addQueryPackage(element.stringAttr("name"));
            }
            case "queries" -> { }
            case "application" -> {
                model.applicationClass(element.stringAttr("name"));
                model.applicationPermission(element.stringAttr("permission"));
                model.applicationProcessName(element.stringAttr("process"));
                model.applicationComponentFactory(element.stringAttr("appComponentFactory"));
                model.applicationDebuggable(element.boolAttr("debuggable", false));
                model.applicationDirectBootAware(element.boolAttr("directBootAware", false));
                model.applicationExtractNativeLibs(element.boolAttr("extractNativeLibs", true));
                model.applicationUsesCleartextTraffic(element.boolAttr("usesCleartextTraffic", true));
                model.applicationLargeHeap(element.boolAttr("largeHeap", false));
                model.applicationHardwareAccelerated(element.boolAttr("hardwareAccelerated", true));
                model.applicationNetworkSecurityConfigResId(
                        element.intAttr("networkSecurityConfig", 0));
                model.applicationThemeResId(element.intAttr("theme", 0));
            }
            case "activity" -> {
                if (insideQueries(stack)) break;
                ManifestModel.Component component = component(model, element,
                        requireComponentName("activity", element.stringAttr("name")));
                component = model.addActivity(component);
                element.component = component;
            }
            case "activity-alias" -> {
                if (insideQueries(stack)) break;
                // Some OEM manifests reuse the target activity name for an alias.
                // Android exposes one component record with the union of filters;
                // ManifestModel.mergeFrom preserves that behavior.
                ManifestModel.Component component = component(model, element,
                        requireComponentName("activity-alias", element.stringAttr("name")));
                component = model.addActivity(component);
                component.targetActivity(model.resolveClassName(element.stringAttr("targetActivity")));
                element.component = component;
            }
            case "service" -> {
                if (insideQueries(stack)) break;
                ManifestModel.Component component = component(model, element,
                        requireComponentName("service", element.stringAttr("name")));
                component = model.addService(component);
                element.component = component;
            }
            case "receiver" -> {
                if (insideQueries(stack)) break;
                ManifestModel.Component component = component(model, element,
                        requireComponentName("receiver", element.stringAttr("name")));
                component = model.addReceiver(component);
                element.component = component;
            }
            case "provider" -> {
                // Android 11+ <queries><provider android:authorities="..."/></queries>
                // declares package visibility, not a ContentProvider component.
                if (insideQueries(stack)) {
                    model.addQueryProviderAuthority(element.stringAttr("authorities"));
                    break;
                }
                ManifestModel.Component component = providerComponent(model, element,
                        requireComponentName("provider", element.stringAttr("name")));
                component = model.addProvider(component);
                element.component = component;
            }
            case "path-permission" -> addProviderPathPermission(stack, element);
            case "grant-uri-permission" -> addProviderGrantRule(stack, element);
            case "action" -> {
                ManifestModel.Component component = nearestComponent(stack);
                IntentState state = nearestIntent(stack);
                ManifestModel.QueryIntent query = nearestQueryIntent(stack);
                String actionName = element.stringAttr("name");
                if (component != null) component.addAction(actionName);
                if (state != null && state.filter != null) state.filter.addAction(actionName);
                if (query != null) query.addAction(actionName);
                if (component != null && "android.intent.action.MAIN".equals(actionName)) {
                    if (state != null) state.mainAction = true;
                }
            }
            case "category" -> {
                ManifestModel.Component component = nearestComponent(stack);
                ManifestModel.QueryIntent query = nearestQueryIntent(stack);
                String value = element.stringAttr("name");
                if (query != null) query.addCategory(value);
                if (component != null) {
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
                ManifestModel.QueryIntent query = nearestQueryIntent(stack);
                ManifestModel.DataRule rule = new ManifestModel.DataRule(
                        element.stringAttr("scheme"), element.stringAttr("host"),
                        dataPort(element),
                        element.stringAttr("path"), element.stringAttr("pathPrefix"),
                        element.stringAttr("pathPattern"), element.stringAttr("pathSuffix"),
                        element.stringAttr("advancedPattern"), element.stringAttr("mimeType"),
                        element.stringAttr("mimeGroup"), element.stringAttr("ssp"),
                        element.stringAttr("sspPrefix"), element.stringAttr("sspPattern"));
                if (query != null) query.addDataRule(rule);
                if (state != null && state.filter != null) {
                    if (!rule.empty()) state.filter.addDataRule(rule);
                }
            }
            case "intent" -> {
                if (insideQueries(stack)) element.queryIntent = model.addQueryIntent();
            }
            case "intent-filter" -> {
                ManifestModel.Component component = nearestComponent(stack);
                ManifestModel.IntentFilter filter = component == null ? null
                        : component.addIntentFilter(element.intAttr("priority", 0),
                                element.intAttr("order", 0),
                                element.boolAttr("autoVerify", false));
                element.intentState = new IntentState(component, filter);
            }
            default -> { }
        }

    }

    private static boolean insideQueries(Deque<ElementContext> stack) {
        for (ElementContext context : stack) {
            if ("queries".equals(context.name)) return true;
        }
        return false;
    }

    private static int dataPort(Element element) {
        if (element == null || !element.hasAttr("port")) return -1;
        int port = element.intAttr("port", Integer.MIN_VALUE);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("INVALID_DATA_PORT:" + element.stringAttr("port"));
        }
        return port;
    }

    private static String requireComponentName(String tag, String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("MISSING_COMPONENT_NAME:" + tag);
        }
        return rawName;
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
        ManifestModel.Component component = new ManifestModel.Component(className, process, exported,
                exportedExplicit, enabled, isolated, element.stringAttr("authorities"), permission);
        boolean themeExplicit = element.hasAttr("theme");
        component.themeResId(themeExplicit ? element.intAttr("theme", 0)
                : model.applicationThemeResId());
        component.themeExplicit(themeExplicit);
        component.launchMode(element.stringAttr("launchMode"));
        component.taskAffinity(element.stringAttr("taskAffinity"));
        component.documentLaunchMode(element.stringAttr("documentLaunchMode"));
        component.persistableMode(element.stringAttr("persistableMode"));
        component.configChanges(element.intAttr("configChanges", 0));
        component.screenOrientation(element.stringAttr("screenOrientation"));
        component.windowSoftInputMode(element.intAttr("windowSoftInputMode", 0));
        component.flags(element.intAttr("flags", 0));
        component.excludeFromRecents(element.boolAttr("excludeFromRecents", false));
        component.noHistory(element.boolAttr("noHistory", false));
        component.finishOnTaskLaunch(element.boolAttr("finishOnTaskLaunch", false));
        component.clearTaskOnLaunch(element.boolAttr("clearTaskOnLaunch", false));
        component.alwaysRetainTaskState(element.boolAttr("alwaysRetainTaskState", false));
        component.allowTaskReparenting(element.boolAttr("allowTaskReparenting", false));
        component.resizeMode(element.stringAttr("resizeMode"));
        if (element.hasAttr("resizeableActivity")) {
            component.resizeMode(element.boolAttr("resizeableActivity", true)
                    ? "resizeable" : "unresizeable");
        }
        component.maxAspectRatio(element.floatAttr("maxAspectRatio", 0f));
        component.minAspectRatio(element.floatAttr("minAspectRatio", 0f));
        component.supportsPictureInPicture(element.boolAttr("supportsPictureInPicture", false));
        component.foregroundServiceType(element.intAttr("foregroundServiceType", 0));
        component.stopWithTask(element.boolAttr("stopWithTask", false));
        component.directBootAware(element.boolAttr("directBootAware", false));
        component.visibleToInstantApps(element.boolAttr("visibleToInstantApps", false));
        component.attributionTags(element.stringAttr("attributionTags"));
        component.lockTaskMode(element.stringAttr("lockTaskMode"));
        component.maxRecents(element.intAttr("maxRecents", 0));
        component.turnScreenOn(element.boolAttr("turnScreenOn", false));
        component.showWhenLocked(element.boolAttr("showWhenLocked", false));
        return component;
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
        ManifestModel.Component component = new ManifestModel.Component(className, process, exported,
                exportedExplicit, enabled, isolated,
                element.stringAttr("authorities"), permission, readPermission, writePermission,
                element.boolAttr("grantUriPermissions", false));
        component.multiprocess(element.boolAttr("multiprocess", false));
        component.initOrder(element.intAttr("initOrder", 0));
        component.syncable(element.boolAttr("syncable", false));
        component.directBootAware(element.boolAttr("directBootAware", false));
        return component;
    }

    private static ManifestModel.Component nearestComponent(Deque<ElementContext> stack) {
        for (ElementContext context : stack) if (context.component != null) return context.component;
        return null;
    }

    private static IntentState nearestIntent(Deque<ElementContext> stack) {
        for (ElementContext context : stack) if (context.intentState != null) return context.intentState;
        return null;
    }

    private static ManifestModel.QueryIntent nearestQueryIntent(Deque<ElementContext> stack) {
        for (ElementContext context : stack) if (context.queryIntent != null) return context.queryIntent;
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
            if (dataType == TYPE_REFERENCE || dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX
                    || dataType == TYPE_INT_BOOLEAN) return data;
            try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { return fallback; }
        }
        float asFloat(float fallback) {
            if (dataType == TYPE_FLOAT) return Float.intBitsToFloat(data);
            if (dataType == TYPE_INT_DEC) return data;
            try { return Float.parseFloat(text); } catch (NumberFormatException ignored) { return fallback; }
        }
        String enumText(String attribute) {
            if (dataType != TYPE_INT_DEC && dataType != TYPE_INT_BOOLEAN) return "";
            if ("launchMode".equals(attribute)) {
                return switch (data) {
                    case 0 -> "standard";
                    case 1 -> "singleTop";
                    case 2 -> "singleTask";
                    case 3 -> "singleInstance";
                    case 4 -> "singleInstancePerTask";
                    default -> "";
                };
            }
            if ("documentLaunchMode".equals(attribute)) {
                return switch (data) {
                    case 0 -> "none";
                    case 1 -> "intoExisting";
                    case 2 -> "always";
                    case 3 -> "never";
                    default -> "";
                };
            }
            if ("persistableMode".equals(attribute)) {
                return switch (data) {
                    case 0 -> "persistRootOnly";
                    case 1 -> "persistNever";
                    case 2 -> "persistAcrossReboots";
                    default -> "";
                };
            }
            if ("screenOrientation".equals(attribute)) {
                return switch (data) {
                    case -1 -> "unspecified";
                    case 0 -> "landscape";
                    case 1 -> "portrait";
                    case 2 -> "user";
                    case 3 -> "behind";
                    case 4 -> "sensor";
                    case 5 -> "nosensor";
                    case 6 -> "sensorLandscape";
                    case 7 -> "sensorPortrait";
                    case 8 -> "reverseLandscape";
                    case 9 -> "reversePortrait";
                    case 10 -> "fullSensor";
                    case 11 -> "userLandscape";
                    case 12 -> "userPortrait";
                    case 13 -> "fullUser";
                    default -> "";
                };
            }
            return "";
        }
    }

    private static final class Element {
        final String name;
        final Map<String, Value> attributes;
        ManifestModel.Component component;
        IntentState intentState;
        ManifestModel.QueryIntent queryIntent;
        Element(String name, Map<String, Value> attributes) { this.name = name; this.attributes = attributes; }
        boolean hasAttr(String name) { return attributes.containsKey(name); }
        String stringAttr(String name) {
            Value value = attributes.get(name);
            if (value == null) return "";
            if (value.text != null && !value.text.isEmpty()) return value.text;
            return value.enumText(name);
        }
        boolean boolAttr(String name, boolean fallback) { Value value = attributes.get(name); return value == null ? fallback : value.asBoolean(fallback); }
        int intAttr(String name, int fallback) { Value value = attributes.get(name); return value == null ? fallback : value.asInt(fallback); }
        float floatAttr(String name, float fallback) { Value value = attributes.get(name); return value == null ? fallback : value.asFloat(fallback); }
    }

    private static final class ElementContext {
        final String name;
        final ManifestModel.Component component;
        final IntentState intentState;
        final ManifestModel.QueryIntent queryIntent;
        ElementContext(String name, ManifestModel.Component component) {
            this(name, component, null, null);
        }
        ElementContext(String name, ManifestModel.Component component, IntentState intentState,
                       ManifestModel.QueryIntent queryIntent) {
            this.name = name;
            this.component = component;
            this.intentState = intentState;
            this.queryIntent = queryIntent;
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
