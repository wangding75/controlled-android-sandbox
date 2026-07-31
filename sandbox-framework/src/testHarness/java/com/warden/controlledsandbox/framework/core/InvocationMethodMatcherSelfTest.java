package com.warden.controlledsandbox.framework.core;

/** Regression tests for centralized exact/prefix/fragment method routing. */
public final class InvocationMethodMatcherSelfTest {
    private InvocationMethodMatcherSelfTest() { }

    public static void main(String[] args) {
        require(InvocationMethodMatcher.named("waitForAndGetProvider", "wait_for_and_get_provider"),
                "exact matching normalizes both inputs");
        require(InvocationMethodMatcher.startsWith("setWebViewProvider", "set"),
                "prefix matching normalizes mixed-case method names");
        require(InvocationMethodMatcher.containsAny("getCurrentWebViewPackage", "web_view_package"),
                "fragment matching normalizes separators");
        require(!InvocationMethodMatcher.named("unregisterListener", "registerListener"),
                "inverse operation names must not match exactly");
        require(!InvocationMethodMatcher.startsWith("disassociate", "associate"),
                "inverse operation prefixes must not overlap");
        System.out.println("PASS centralized invocation method matcher self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
