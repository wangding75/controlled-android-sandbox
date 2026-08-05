package com.warden.controlledsandbox.runtime.guest;

import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PatternMatcher;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;

/** Bounded wire encoding for dynamically registered Android IntentFilter metadata. */
final class GuestIntentFilterWireCodec {
    private static final int MAX_VALUES = 128;
    private static final int MAX_DATA_RULES = 256;

    private GuestIntentFilterWireCodec() { }

    static void encode(Bundle request, IntentFilter filter) {
        if (request == null || filter == null) {
            throw new IllegalArgumentException("request and filter are required");
        }
        request.putInt(RuntimeKeys.RECEIVER_PRIORITY, filter.getPriority());
        request.putStringArrayList(RuntimeKeys.RECEIVER_ACTIONS,
                values(filter.countActions(), filter::getAction, "action"));
        request.putStringArrayList(RuntimeKeys.RECEIVER_CATEGORIES,
                values(filter.countCategories(), filter::getCategory, "category"));

        ArrayList<Bundle> rules = new ArrayList<>();
        ArrayList<String> schemes = values(filter.countDataSchemes(), filter::getDataScheme, "scheme");
        ArrayList<String> mimeTypes = values(filter.countDataTypes(), filter::getDataType, "mimeType");
        for (String scheme : schemes) rules.add(rule(scheme, "", "", "", "", ""));
        for (int index = 0; index < filter.countDataAuthorities(); index++) {
            IntentFilter.AuthorityEntry authority = filter.getDataAuthority(index);
            if (authority == null) continue;
            rules.add(rule("", value(authority.getHost()), "", "", "", ""));
        }
        for (int index = 0; index < filter.countDataPaths(); index++) {
            PatternMatcher path = filter.getDataPath(index);
            if (path == null) continue;
            String exact = "";
            String prefix = "";
            String pattern = "";
            if (path.getType() == PatternMatcher.PATTERN_LITERAL) exact = value(path.getPath());
            else if (path.getType() == PatternMatcher.PATTERN_PREFIX) prefix = value(path.getPath());
            else pattern = value(path.getPath());
            rules.add(rule("", "", exact, prefix, pattern, ""));
        }
        for (String mimeType : mimeTypes) rules.add(rule("", "", "", "", "", mimeType));
        if (rules.size() > MAX_DATA_RULES) {
            throw new IllegalArgumentException("IntentFilter data rule limit exceeded");
        }
        request.putInt(RuntimeKeys.RECEIVER_DATA_RULE_COUNT, rules.size());
        for (int index = 0; index < rules.size(); index++) {
            request.putBundle(RuntimeKeys.RECEIVER_DATA_RULE_PREFIX + index, rules.get(index));
        }
    }

    private static Bundle rule(String scheme, String host, String path,
            String pathPrefix, String pathPattern, String mimeType) {
        Bundle rule = new Bundle();
        rule.putString(RuntimeKeys.BROADCAST_SCHEME, value(scheme));
        rule.putString(RuntimeKeys.BROADCAST_HOST, value(host));
        rule.putString(RuntimeKeys.BROADCAST_PATH, value(path));
        rule.putString(RuntimeKeys.RECEIVER_DATA_PATH_PREFIX, value(pathPrefix));
        rule.putString(RuntimeKeys.RECEIVER_DATA_PATH_PATTERN, value(pathPattern));
        rule.putString(RuntimeKeys.BROADCAST_MIME_TYPE, value(mimeType));
        return rule;
    }

    private static ArrayList<String> values(int count, ValueReader reader, String label) {
        if (count < 0 || count > MAX_VALUES) {
            throw new IllegalArgumentException("IntentFilter " + label + " limit exceeded");
        }
        ArrayList<String> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String value = value(reader.value(index));
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }
    @FunctionalInterface private interface ValueReader { String value(int index); }
}
