package com.warden.controlledsandbox.runtime.guest;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage for WebView provider callbacks crossing generation teardown. */
public final class GuestWebViewProviderServiceBridgeSelfTest {
    private GuestWebViewProviderServiceBridgeSelfTest() { }

    public static void main(String[] args) {
        ComponentName component = new ComponentName("com.google.android.webview",
                "org.chromium.content.app.SandboxedProcessService0");
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        Executor queued = queue::add;
        AtomicInteger connected = new AtomicInteger();
        ServiceConnection guest = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                connected.incrementAndGet();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };

        GuestWebViewProviderServiceBridge.ProviderServiceRelay relay =
                new GuestWebViewProviderServiceBridge.ProviderServiceRelay(guest, queued);
        relay.onServiceConnected(component, new Binder());
        relay.close();
        drain(queue);
        require(connected.get() == 0,
                "queued WebView callback crossed provider generation teardown");

        GuestWebViewProviderServiceBridge.ProviderServiceRelay live =
                new GuestWebViewProviderServiceBridge.ProviderServiceRelay(guest, queued);
        live.onServiceConnected(component, new Binder());
        drain(queue);
        require(connected.get() == 1, "live WebView callback was dropped");
        live.close();
        live.onServiceDisconnected(component);
        drain(queue);
        require(connected.get() == 1,
                "late WebView callback was delivered after relay close");
        System.out.println("PASS WebView provider callback fence self-test");
    }

    private static void drain(ArrayDeque<Runnable> queue) {
        Runnable callback;
        while ((callback = queue.poll()) != null) callback.run();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
