package com.warden.controlledsandbox.framework.core;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.framework.identity.IdentityContext;
import java.util.concurrent.atomic.AtomicInteger;

public final class FrameworkCallInterceptorSelfTest {
    private FrameworkCallInterceptorSelfTest() { }

    public static void main(String[] args) {
        ActivityManagerServiceImpl activityManager = new ActivityManagerServiceImpl();
        android.app.ActivityManager.setServiceForTest(activityManager);
        android.app.ActivityTaskManager.setServiceForTest(new ActivityTaskManagerServiceImpl());
        AtomicInteger intercepted = new AtomicInteger();
        FrameworkCallInterceptor interceptor = (service, method, arguments) -> {
            if ("activity-manager".equals(service)
                    && "finishReceiver".equals(method.getName())
                    && arguments != null && arguments.length >= 1) {
                intercepted.incrementAndGet();
                return FrameworkCallInterceptor.Interception.handled(null);
            }
            return FrameworkCallInterceptor.Interception.passThrough();
        };
        FrameworkProxyController controller = FrameworkProxyController.installDefault(
                context(), ProxyTelemetry.NO_OP, interceptor);
        check(controller.passed(), "framework interceptor proxy install failed");
        Object proxy = android.app.ActivityManager.getServiceForTest();
        check(proxy instanceof ActivityManagerService, "activity-manager proxy type");
        ((ActivityManagerService) proxy).finishReceiver(
                new Binder(), 1, "data", new Bundle(), true, 0);
        check(intercepted.get() == 1, "finishReceiver was not intercepted");
        check(activityManager.finishCalls == 0, "intercepted finishReceiver reached delegate");
        ((ActivityManagerService) proxy).ping();
        check(activityManager.pingCalls == 1, "pass-through call did not reach delegate");
        controller.rollbackAll();
        check(android.app.ActivityManager.getServiceForTest() == activityManager,
                "activity-manager rollback failed");
        System.out.println("PASS framework runtime-call interceptor self-test");
    }

    private static IdentityContext context() {
        return new IdentityContext("guest.example", 11234, "host.example", 10001,
                "guest.example:main", 2, 7);
    }

    public interface ActivityManagerService {
        void finishReceiver(IBinder token, int resultCode, String resultData,
                            Bundle resultExtras, boolean abort, int flags);
        void ping();
    }

    public static final class ActivityManagerServiceImpl implements ActivityManagerService {
        int finishCalls;
        int pingCalls;
        @Override public void finishReceiver(IBinder token, int resultCode, String resultData,
                                             Bundle resultExtras, boolean abort, int flags) {
            finishCalls++;
        }
        @Override public void ping() { pingCalls++; }
    }

    public interface ActivityTaskManagerService { void pingTask(); }
    public static final class ActivityTaskManagerServiceImpl implements ActivityTaskManagerService {
        @Override public void pingTask() { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
