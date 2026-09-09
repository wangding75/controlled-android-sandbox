package com.warden.controlledsandbox.fixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline P1-14 probe for the normal Guest WebView Activity route. */
public final class P114WebViewProbeActivity extends Activity {
    private static final String TAG = "CS_P114_WEBVIEW";
    private static final String ORIGIN = "https://p114.fixture.invalid/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean rendererGone = new AtomicBoolean();
    private WebView webView;
    private String token;
    private String requestId;
    private boolean writeToken;
    private boolean crashRenderer;
    private boolean navigated;
    private boolean recovered;
    private boolean fileChooserObserved;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getStringExtra("p114.token");
        requestId = getIntent().getStringExtra("p114.requestId");
        writeToken = getIntent().getBooleanExtra("p114.writeToken", true);
        crashRenderer = getIntent().getBooleanExtra("p114.crashRenderer", false);
        if (token == null || !token.matches("[A-Za-z0-9_-]{1,80}")
                || requestId == null || requestId.isEmpty()) {
            throw new IllegalArgumentException("P114 token and requestId are required");
        }
        readProviderAsset();
        createWebView(false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView(boolean recovery) {
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                Log.i(TAG, "P114_TOUCH_UP request=" + requestId + " x=" + event.getX()
                        + " y=" + event.getY());
            }
            return false;
        });
        webView.setWebViewClient(new ProbeClient());
        webView.setWebChromeClient(new ProbeChromeClient());
        if (writeToken) {
            CookieManager cookies = CookieManager.getInstance();
            cookies.setCookie(ORIGIN, "p114_token=" + token + "; Path=/; SameSite=Lax");
            cookies.flush();
        }
        setContentView(webView);
        if (recovery) {
            webView.loadDataWithBaseURL(ORIGIN + "recovered", recoveredPage(), "text/html", "UTF-8", null);
        } else {
            webView.loadDataWithBaseURL(ORIGIN, initialPage(), "text/html", "UTF-8", null);
        }
    }

    private void readProviderAsset() {
        // API36's installed Google provider exposes this actual immutable provider asset with
        // its shared-library package suffix.  This is intentionally not an assumption that
        // every WebView provider ships Trichrome's icudtl.dat.
        try (InputStream input = getAssets().open("chrome_100_percent.pak+com.google.android.webview+")) {
            byte[] head = new byte[16];
            int count = input.read(head);
            Log.i(TAG, "P114_PROVIDER_ASSET_READ request=" + requestId + " bytes=" + Math.max(count, 0));
        } catch (Exception error) {
            // This marker is deliberately a real failure phase, not a synthetic asset success.
            Log.e(TAG, "P114_PROVIDER_ASSET_FAILED request=" + requestId + " type="
                    + error.getClass().getSimpleName() + " message=" + error.getMessage());
        }
    }

    private final class ProbeClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Log.i(TAG, "P114_NAVIGATION_REQUEST request=" + requestId + " url=" + request.getUrl());
            return false;
        }

        @Override public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (recovered) {
                evaluateRecovered(view);
            } else if (!navigated) {
                evaluateInitial(view);
            } else {
                evaluateNavigation(view);
            }
        }

        @Override public boolean onRenderProcessGone(WebView view,
                android.webkit.RenderProcessGoneDetail detail) {
            if (!rendererGone.compareAndSet(false, true)) return true;
            Log.w(TAG, "P114_RENDERER_GONE request=" + requestId + " didCrash=" + detail.didCrash());
            if (view == webView) {
                view.destroy();
                recovered = true;
                createWebView(true);
            }
            return true;
        }
    }

    private final class ProbeChromeClient extends WebChromeClient {
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                WebChromeClient.FileChooserParams params) {
            fileChooserObserved = true;
            Log.i(TAG, "P114_FILE_CHOOSER_REQUEST request=" + requestId + " mode=" + params.getMode());
            // The offline fixture validates the controlled request route, then cancels it.  It
            // must not silently select or expose a Host file as Guest content.
            callback.onReceiveValue(null);
            navigated = true;
            view.loadDataWithBaseURL(ORIGIN + "next", navigationPage(), "text/html", "UTF-8", null);
            return true;
        }
    }

    private void evaluateInitial(WebView view) {
        view.evaluateJavascript("(function(){var i=document.getElementById('input');"
                + "i.focus();i.value='input-" + token + "';if (" + writeToken
                + ") localStorage.setItem('p114', '" + token
                + "');return JSON.stringify({js:'ok',input:i.value,storage:localStorage.getItem('p114')});})()",
                value -> {
                    Log.i(TAG, "P114_JS_INITIAL request=" + requestId + " value=" + value);
                });
    }

    private void evaluateNavigation(WebView view) {
        view.evaluateJavascript("JSON.stringify({navigation:location.pathname,storage:localStorage.getItem('p114'),cookie:document.cookie})",
                value -> {
                    Log.i(TAG, "P114_JS_NAVIGATION request=" + requestId + " value=" + value);
                    if (!crashRenderer) return;
                    handler.postDelayed(() -> view.loadUrl("chrome://crash"), 100L);
                    handler.postDelayed(() -> {
                        if (!rendererGone.get()) {
                            Log.e(TAG, "P114_RENDERER_TIMEOUT request=" + requestId);
                        }
                    }, 12_000L);
                });
    }

    private void evaluateRecovered(WebView view) {
        view.evaluateJavascript("JSON.stringify({recovered:'ok',storage:localStorage.getItem('p114')})",
                value -> Log.i(TAG, "P114_JS_RECOVERED request=" + requestId + " value=" + value));
    }

    private static String initialPage() {
        // Chromium only permits a file chooser from a user activation.  Route the actual
        // `input[type=file]` click through this visible button so the runner can supply one
        // physical tap; a programmatic click is correctly refused by the platform.
        return "<html><body style='margin:48px'><input id='input' style='display:block;width:640px;height:96px'>"
                + "<button id='choose' onclick=\"document.getElementById('file').click()\""
                + " style='display:block;margin-top:72px;width:360px;height:112px;font-size:32px'>Choose fixture file</button>"
                + "<input id='file' type='file' style='display:none'></body></html>";
    }

    private static String navigationPage() {
        return "<html><body><p id='next'>next</p></body></html>";
    }

    private static String recoveredPage() {
        return "<html><body><p id='recovered'>recovered</p></body></html>";
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
