package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
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
        VirtualWebViewProfileSnapshot policy = new VirtualWebViewProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, "com.android.webview", "virtual",
                "fixture", "renderer_u3", true, true, false, 2);
        WebViewProfileManager.Profile governed = WebViewProfileManager.plan(
                "com.example.fixture", 3, "com.example.fixture", 0, root, policy);
        String first = governed.renderers.reserve("renderer-1");
        require(first.startsWith("renderer_u3:"), "renderer process prefix");
        require(first.equals(governed.renderers.reserve("renderer-1")), "renderer reservation idempotent");
        governed.renderers.reserve("renderer-2");
        boolean limited = false;
        try { governed.renderers.reserve("renderer-3"); }
        catch (IllegalStateException expected) { limited = expected.getMessage().contains("RENDERER_LIMIT"); }
        require(limited, "renderer quota");
        require(governed.renderers.release("renderer-1") && governed.renderers.activeCount() == 1,
                "renderer release");
        governed.storage.putCookie("https://guest.example", "sid=guest");
        governed.storage.putWebStorage("https://guest.example", "theme", "dark");
        require("sid=guest".equals(governed.storage.cookie("https://guest.example"))
                        && "dark".equals(governed.storage.webStorage("https://guest.example", "theme")),
                "WebView cookie and web-storage state must stay in the Guest profile");
        governed.storage.requireGuestPath(governed.fileChooser);
        boolean hostPathRejected = false;
        try { governed.storage.requireGuestPath(new File(root, "../host-webview-data")); }
        catch (SecurityException expected) { hostPathRejected = true; }
        require(hostPathRejected, "WebView storage must reject paths outside the Guest root");
        governed.renderers.close();
        require(governed.renderers.activeCount() == 0, "renderer shutdown cleanup");
        governed.storage.close();
        System.out.println("PASS WebView profile isolation and renderer ownership self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
