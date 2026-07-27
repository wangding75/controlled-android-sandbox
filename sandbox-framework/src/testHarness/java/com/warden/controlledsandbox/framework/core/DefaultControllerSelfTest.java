package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.IdentityContext;

import java.lang.reflect.Proxy;
import java.util.List;

public final class DefaultControllerSelfTest {
    private DefaultControllerSelfTest() {
    }

    public static void main(String[] args) {
        testDefaultInstallAndRollback();
        testPartialFailureRollsBackEarlierProxy();
        System.out.println("PASS DefaultControllerSelfTest");
    }

    private static void testDefaultInstallAndRollback() {
        ActivityManagerService activityManager = new ActivityManagerServiceImpl();
        ActivityTaskManagerService activityTaskManager = new ActivityTaskManagerServiceImpl();
        android.app.ActivityManager.setServiceForTest(activityManager);
        android.app.ActivityTaskManager.setServiceForTest(activityTaskManager);

        FrameworkProxyController controller = FrameworkProxyController.installDefault(
                context(), ProxyTelemetry.NO_OP);
        check(controller.passed(), "both default proxies should install");
        check(controller.reports().size() == 2, "two reports required");
        check(Proxy.isProxyClass(android.app.ActivityManager.getServiceForTest().getClass()),
                "ActivityManager service should be proxied");
        check(Proxy.isProxyClass(android.app.ActivityTaskManager.getServiceForTest().getClass()),
                "ActivityTaskManager service should be proxied");

        ActivityManagerService proxiedAm =
                (ActivityManagerService) android.app.ActivityManager.getServiceForTest();
        String receiver = proxiedAm.registerReceiver(
                new Object(), "guest.example", new Object(), new Object(), null, 2, 0);
        check(receiver.equals("host.example|2"), "AMS package should be rewritten by signature");

        ActivityTaskManagerService proxiedAtm =
                (ActivityTaskManagerService) android.app.ActivityTaskManager.getServiceForTest();
        String activity = proxiedAtm.startActivity(
                new Object(), "guest.example", new Object(), "type", new Object(), "who",
                11234, 11234, new Object(), new Object());
        check(activity.equals("host.example|11234|11234"),
                "ATMS package should change without corrupting requestCode or flags");

        List<String> rolledBack = controller.rollbackAll();
        check(rolledBack.equals(List.of("activity-task-manager", "activity-manager")),
                "rollback should run in reverse install order: " + rolledBack);
        check(android.app.ActivityManager.getServiceForTest() == activityManager,
                "ActivityManager delegate should be restored");
        check(android.app.ActivityTaskManager.getServiceForTest() == activityTaskManager,
                "ActivityTaskManager delegate should be restored");
    }

    private static void testPartialFailureRollsBackEarlierProxy() {
        ActivityManagerService activityManager = new ActivityManagerServiceImpl();
        android.app.ActivityManager.setServiceForTest(activityManager);
        android.app.ActivityTaskManager.setServiceForTest(new NoInterfaceTaskManager());

        FrameworkProxyController controller = FrameworkProxyController.installDefault(
                context(), ProxyTelemetry.NO_OP);
        check(!controller.passed(), "partial install must fail the controller");
        check(android.app.ActivityManager.getServiceForTest() == activityManager,
                "earlier proxy must be rolled back after later failure");
        check(!Proxy.isProxyClass(android.app.ActivityManager.getServiceForTest().getClass()),
                "no partial proxy may remain installed");
    }

    private static IdentityContext context() {
        return new IdentityContext(
                "guest.example", 11234, "host.example", 10001,
                "guest.example:main", 2, 7);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public interface ActivityManagerService {
        String registerReceiver(
                Object caller,
                String callerPackage,
                Object receiver,
                Object filter,
                String permission,
                int userId,
                int flags);
    }

    public static final class ActivityManagerServiceImpl implements ActivityManagerService {
        @Override
        public String registerReceiver(
                Object caller,
                String callerPackage,
                Object receiver,
                Object filter,
                String permission,
                int userId,
                int flags) {
            return callerPackage + "|" + userId;
        }
    }

    public interface ActivityTaskManagerService {
        String startActivity(
                Object caller,
                String callerPackage,
                Object intent,
                String resolvedType,
                Object resultTo,
                String resultWho,
                int requestCode,
                int flags,
                Object profiler,
                Object options);
    }

    public static final class ActivityTaskManagerServiceImpl implements ActivityTaskManagerService {
        @Override
        public String startActivity(
                Object caller,
                String callerPackage,
                Object intent,
                String resolvedType,
                Object resultTo,
                String resultWho,
                int requestCode,
                int flags,
                Object profiler,
                Object options) {
            return callerPackage + "|" + requestCode + "|" + flags;
        }
    }

    public static final class NoInterfaceTaskManager {
    }
}
