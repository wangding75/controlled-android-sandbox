package com.warden.controlledsandbox.runtime.guest;

import java.io.File;

public final class WebViewProfileSelfTest {
    public static void main(String[] args) throws Exception {
        File root = new File("build/webview-profile-self-test/u3/app").getCanonicalFile();
        WebViewProfileManager.Profile main = WebViewProfileManager.plan(
                "com.example.fixture", 3, "com.example.fixture", 0, root);
        WebViewProfileManager.Profile remote = WebViewProfileManager.plan(
                "com.example.fixture", 3, "com.example.fixture:remote", 1, root);
        WebViewProfileManager.Profile otherUser = WebViewProfileManager.plan(
                "com.example.fixture", 4, "com.example.fixture", 0, root);
        require(!main.suffix.equals(remote.suffix), "per-process suffix");
        require(!main.suffix.equals(otherUser.suffix), "per-user suffix");
        require(main.root.toPath().startsWith(root.toPath()), "profile stays in instance");
        require(remote.root.getPath().contains("com.example.fixture_remote"), "safe process path");
        System.out.println("PASS WebView profile isolation self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
