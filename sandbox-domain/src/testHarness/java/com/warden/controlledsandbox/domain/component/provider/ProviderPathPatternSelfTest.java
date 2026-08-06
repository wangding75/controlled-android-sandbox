package com.warden.controlledsandbox.domain.component.provider;

public final class ProviderPathPatternSelfTest {
    public static void main(String[] args) {
        require(AndroidSimpleGlobMatcher.matches("/items/.*", "/items/alpha"), ".* sequence");
        require(AndroidSimpleGlobMatcher.matches("/ab*c", "/ac"), "zero repeated literal");
        require(AndroidSimpleGlobMatcher.matches("/ab*c", "/abbbc"), "many repeated literal");
        require(!AndroidSimpleGlobMatcher.matches("/ab*c", "/abXc"), "star is not wildcard by itself");
        require(AndroidSimpleGlobMatcher.matches("/a\\*b", "/a*b"), "escaped star is literal");
        require(AndroidSimpleGlobMatcher.matches("/file.", "/file1"), "dot matches one character");
        require(!AndroidSimpleGlobMatcher.matches("/file.", "/file12"), "dot matches exactly one");
        System.out.println("PASS Provider path simple-glob matcher self-test");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
