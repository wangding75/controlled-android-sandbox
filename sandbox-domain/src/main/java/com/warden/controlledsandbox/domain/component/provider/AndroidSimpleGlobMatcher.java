package com.warden.controlledsandbox.domain.component.provider;

import java.util.ArrayList;
import java.util.List;

/** Bounded matcher for Android PatternMatcher.PATTERN_SIMPLE_GLOB semantics. */
final class AndroidSimpleGlobMatcher {
    private static final int MAX_PATTERN_CHARS = 4096;
    private static final int MAX_VALUE_CHARS = 16384;

    private AndroidSimpleGlobMatcher() { }

    static boolean matches(String pattern, String value) {
        String normalizedPattern = pattern == null ? "" : pattern;
        String normalizedValue = value == null ? "" : value;
        if (normalizedPattern.length() > MAX_PATTERN_CHARS
                || normalizedValue.length() > MAX_VALUE_CHARS) return false;
        List<Token> tokens = tokenize(normalizedPattern);
        Boolean[][] memo = new Boolean[tokens.size() + 1][normalizedValue.length() + 1];
        return match(tokens, 0, normalizedValue, 0, memo);
    }

    private static boolean match(List<Token> tokens, int tokenIndex, String value, int valueIndex,
                                 Boolean[][] memo) {
        Boolean cached = memo[tokenIndex][valueIndex];
        if (cached != null) return cached;
        boolean result;
        if (tokenIndex == tokens.size()) {
            result = valueIndex == value.length();
        } else {
            Token token = tokens.get(tokenIndex);
            if (!token.repeated) {
                result = valueIndex < value.length() && token.matches(value.charAt(valueIndex))
                        && match(tokens, tokenIndex + 1, value, valueIndex + 1, memo);
            } else {
                result = match(tokens, tokenIndex + 1, value, valueIndex, memo);
                int cursor = valueIndex;
                while (!result && cursor < value.length() && token.matches(value.charAt(cursor))) {
                    cursor++;
                    result = match(tokens, tokenIndex + 1, value, cursor, memo);
                }
            }
        }
        memo[tokenIndex][valueIndex] = result;
        return result;
    }

    private static List<Token> tokenize(String pattern) {
        List<Token> tokens = new ArrayList<>();
        for (int index = 0; index < pattern.length(); index++) {
            char value = pattern.charAt(index);
            boolean escaped = false;
            if (value == '\\' && index + 1 < pattern.length()) {
                value = pattern.charAt(++index);
                escaped = true;
            }
            boolean repeated = index + 1 < pattern.length() && pattern.charAt(index + 1) == '*';
            if (repeated) index++;
            tokens.add(new Token(value, !escaped && value == '.', repeated));
        }
        return tokens;
    }

    private record Token(char value, boolean anyCharacter, boolean repeated) {
        boolean matches(char candidate) { return anyCharacter || value == candidate; }
    }
}
