package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.ArgumentRewriteRule;
import com.warden.controlledsandbox.framework.identity.IdentityArgumentRewriter;
import com.warden.controlledsandbox.framework.identity.IdentityContext;
import com.warden.controlledsandbox.framework.identity.MethodIdentityPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FrameworkProxySelfTest {
    private FrameworkProxySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        testExactIdentityRewriteAndRollback();
        testNonAllowlistedMethodUntouched();
        testDelegateExceptionUnwrapped();
        testAlreadyInstalled();
        testInterfaceArrayConversionPreservesOrderAndDuplicates();
        testAttributionSourceChainRewrite();
        testSignaturePolicyDoesNotRewriteCoincidentalUid();
        testUnknownOverloadFailsInstallationAudit();
        testSameCountWrongTypeFailsInstallationAudit();
        testOutboundListPreservesNull();
        testOutboundProcessRecordUsesGuestProcessName();
        testFailClosedWithoutInterfaces();
        System.out.println("PASS FrameworkProxySelfTest");
    }

    private static void testExactIdentityRewriteAndRollback() throws Exception {
        FakeOwner.SINGLETON.mInstance = new FakeServiceImpl();
        List<ProxyEvent> events = new ArrayList<>();
        FrameworkServiceSpec spec = fakeSpec();
        IdentityContext context = context();
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(spec, context, events::add);

        check(outcome.report().installed(), "proxy should install");
        check(outcome.installedProxy() != null, "install handle required");
        check(outcome.report().interfaces().size() == 1,
                "single-interface report must contain exactly one interface");
        check(outcome.report().interfaces().get(0).equals(FakeService.class.getName()),
                "single-interface report must preserve interface content");
        FakeService service = (FakeService) FakeOwner.SINGLETON.mInstance;
        String inbound = service.startActivity("guest.example", 11234, new String[] {"guest.example", "mime/type"});
        check(
                inbound.equals("host.example|10001|host.example,mime/type"),
                "inbound identity should be rewritten exactly: " + inbound);

        List<Object> outbound = service.getRunningAppProcesses();
        check(outbound.get(0).equals("guest.example"), "host package should project to Guest");
        check(outbound.get(1).equals(11234), "host UID should project to Guest");
        check(outbound.get(2).equals("guest.example:remote"), "process prefix should project to Guest");
        check(!events.isEmpty(), "telemetry should record rewrites");

        boolean rolledBack = outcome.installedProxy().rollback();
        check(rolledBack, "rollback should restore original");
        check(FakeOwner.SINGLETON.mInstance instanceof FakeServiceImpl, "original delegate should be restored");
    }

    private static void testNonAllowlistedMethodUntouched() {
        FakeOwner.SINGLETON.mInstance = new FakeServiceImpl();
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(fakeSpec(), context(), ProxyTelemetry.NO_OP);
        FakeService service = (FakeService) FakeOwner.SINGLETON.mInstance;
        String result = service.unrelated("guest.example", 11234);
        check(result.equals("guest.example|11234"), "non-allowlisted method must stay untouched");
        check(outcome.installedProxy() != null, "install handle required");
        try {
            outcome.installedProxy().rollback();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void testDelegateExceptionUnwrapped() {
        FakeOwner.SINGLETON.mInstance = new FakeServiceImpl();
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(fakeSpec(), context(), ProxyTelemetry.NO_OP);
        FakeService service = (FakeService) FakeOwner.SINGLETON.mInstance;
        try {
            service.fail("guest.example");
            throw new AssertionError("delegate exception should escape");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("host.example"), "rewritten argument should reach delegate");
        }
        rollback(outcome);
    }

    private static void testAlreadyInstalled() {
        FakeOwner.SINGLETON.mInstance = new FakeServiceImpl();
        FrameworkProxyInstaller installer = new FrameworkProxyInstaller();
        FrameworkProxyInstaller.InstallOutcome first = installer.install(fakeSpec(), context(), ProxyTelemetry.NO_OP);
        FrameworkProxyInstaller.InstallOutcome second = installer.install(fakeSpec(), context(), ProxyTelemetry.NO_OP);
        check(second.report().alreadyInstalled(), "second install must be idempotent");
        rollback(first);
    }

    private static void testInterfaceArrayConversionPreservesOrderAndDuplicates() {
        MultipleOwner.SINGLETON.mInstance = new MultipleServiceImpl();
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(fakeSpec(MultipleOwner.class), context(), ProxyTelemetry.NO_OP);
        check(outcome.report().installed(), "multiple-interface proxy should install");

        Class<?>[] interfaces = MultipleOwner.SINGLETON.mInstance.getClass().getInterfaces();
        check(interfaces.length == 3, "interface conversion must retain unique interfaces");
        check(interfaces[0] == MultipleService.class, "primary interface order must be preserved");
        check(interfaces[1] == FakeService.class, "first inherited interface order must be preserved");
        check(interfaces[2] == SecondaryService.class, "second inherited interface order must be preserved");
        check(outcome.report().interfaces().size() == interfaces.length,
                "reported interface count must match proxy interface count");
        check(outcome.report().interfaces().contains(MultipleService.class.getName()),
                "reported interfaces must contain the primary interface");
        check(outcome.report().interfaces().contains(FakeService.class.getName()),
                "reported interfaces must contain inherited interface");
        check(outcome.report().interfaces().contains(SecondaryService.class.getName()),
                "reported interfaces must contain secondary interface");
        rollback(outcome);
    }


    private static void testAttributionSourceChainRewrite() {
        android.content.AttributionSourceState tail = new android.content.AttributionSourceState();
        tail.uid = 11234;
        tail.pid = 77;
        tail.packageName = "guest.example";
        tail.attributionTag = "tail";

        android.content.AttributionSourceState root = new android.content.AttributionSourceState();
        root.uid = 11234;
        root.pid = 66;
        root.packageName = "guest.example";
        root.attributionTag = "root";
        root.next = new android.content.AttributionSourceState[] {tail, null};

        android.content.AttributionSource original = new android.content.AttributionSource(root);
        IdentityArgumentRewriter rewriter = new IdentityArgumentRewriter(context());
        MethodIdentityPolicy policy = MethodIdentityPolicy.of(
                "call", 1, ArgumentRewriteRule.attributionSource(0));
        Object[] rewrittenArguments = rewriter.rewriteInbound(new Object[] {original}, policy);
        android.content.AttributionSource rewritten =
                (android.content.AttributionSource) rewrittenArguments[0];
        android.content.AttributionSourceState rewrittenRoot = rewritten.getStateForTest();

        check(rewritten != original, "AttributionSource must be reconstructed");
        check(rewrittenRoot != root, "AttributionSourceState must be cloned");
        check(rewrittenRoot.uid == 10001, "root UID should be rewritten");
        check(rewrittenRoot.packageName.equals("host.example"), "root package should be rewritten");
        check(rewrittenRoot.pid == 66, "unrelated root fields must be preserved");
        check(rewrittenRoot.next != root.next, "next array must be cloned");
        check(rewrittenRoot.next[0] != tail, "nested state must be cloned");
        check(rewrittenRoot.next[0].uid == 10001, "nested UID should be rewritten");
        check(rewrittenRoot.next[0].packageName.equals("host.example"),
                "nested package should be rewritten");
        check(rewrittenRoot.next[0].pid == 77, "unrelated nested fields must be preserved");
        check(rewrittenRoot.next[1] == null, "null chain entries must be preserved");
        check(root.uid == 11234 && root.packageName.equals("guest.example"),
                "original root must remain unchanged");
        check(tail.uid == 11234 && tail.packageName.equals("guest.example"),
                "original nested state must remain unchanged");
    }


    private static void testSignaturePolicyDoesNotRewriteCoincidentalUid() {
        FakeOwner.SINGLETON.mInstance = new FakeServiceImpl();
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(fakeSpec(), context(), ProxyTelemetry.NO_OP);
        FakeService service = (FakeService) FakeOwner.SINGLETON.mInstance;
        String result = service.startOfficialLike(
                new Object(), "guest.example", new Object(), "type", new Object(), "who",
                11234, 11234, new Object(), new Object());
        check(result.equals("host.example|11234|11234"),
                "requestCode and flags must not be mistaken for UID: " + result);
        rollback(outcome);
    }

    private static void testUnknownOverloadFailsInstallationAudit() {
        UnsupportedOwner.SINGLETON.mInstance = new UnsupportedServiceImpl();
        FrameworkServiceSpec spec = new FrameworkServiceSpec(
                "unsupported-signature",
                UnsupportedOwner.class.getName(),
                "SINGLETON",
                List.of(MethodIdentityPolicy.of(
                        "startActivity", 3, ArgumentRewriteRule.packageName(0))),
                Set.of());
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(spec, context(), ProxyTelemetry.NO_OP);
        check(!outcome.report().passed(), "unknown overload should fail installation audit");
        check(outcome.report().unsupportedProtectedSignatures().size() == 1,
                "unsupported signature should be listed");
        check(outcome.report().failure().contains("startActivity(java.lang.String)"),
                "failure should identify the unsupported overload");
        check(UnsupportedOwner.SINGLETON.mInstance instanceof UnsupportedServiceImpl,
                "failed audit must leave the original delegate installed");
    }

    private static void testSameCountWrongTypeFailsInstallationAudit() {
        WrongTypeOwner.SINGLETON.mInstance = new WrongTypeServiceImpl();
        FrameworkServiceSpec spec = new FrameworkServiceSpec(
                "wrong-type-signature",
                WrongTypeOwner.class.getName(),
                "SINGLETON",
                List.of(MethodIdentityPolicy.of(
                        "startActivity", 3,
                        ArgumentRewriteRule.packageName(0),
                        ArgumentRewriteRule.uid(1),
                        ArgumentRewriteRule.packageNameArray(2))),
                Set.of());
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(spec, context(), ProxyTelemetry.NO_OP);
        check(!outcome.report().passed(), "same-count overload with wrong parameter type must fail audit");
        check(outcome.report().unsupportedProtectedSignatures().stream()
                        .anyMatch(value -> value.contains("startActivity(java.lang.Object,int,java.lang.String[])")),
                "wrong-type signature should be reported");
        check(WrongTypeOwner.SINGLETON.mInstance instanceof WrongTypeServiceImpl,
                "failed type audit must preserve original delegate");
    }

    private static void testOutboundListPreservesNull() {
        IdentityArgumentRewriter rewriter = new IdentityArgumentRewriter(context());
        List<Object> original = new ArrayList<>();
        original.add("host.example");
        original.add(null);
        original.add(10001);
        @SuppressWarnings("unchecked")
        List<Object> rewritten = (List<Object>) rewriter.rewriteOutbound(original);
        check(rewritten.get(0).equals("guest.example"), "list package should project to Guest");
        check(rewritten.get(1) == null, "null list element should be preserved");
        check(rewritten.get(2).equals(11234), "list UID should project to Guest");
        try {
            rewritten.add("forbidden");
            throw new AssertionError("rewritten list should be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void testOutboundProcessRecordUsesGuestProcessName() {
        IdentityArgumentRewriter rewriter = new IdentityArgumentRewriter(context());
        ProcessRecord current = new ProcessRecord();
        current.pid = android.os.Process.myPid();
        current.uid = 10001;
        current.processName = "host.example:guest2";
        current.pkgList = new String[] {"host.example"};
        ProcessRecord otherSlot = new ProcessRecord();
        otherSlot.pid = current.pid + 1;
        otherSlot.uid = 10001;
        otherSlot.processName = "host.example:sandbox_server";
        otherSlot.pkgList = new String[] {"host.example"};
        List<Object> original = new ArrayList<>();
        original.add(current);
        original.add(otherSlot);
        @SuppressWarnings("unchecked")
        List<Object> rewritten = (List<Object>) rewriter.rewriteOutbound(original);
        check(rewritten.size() == 1, "other host slots must not appear as guest processes");
        ProcessRecord projected = (ProcessRecord) rewritten.get(0);
        check(projected != current, "process records must be copied");
        check(projected.processName.equals("guest.example:main"),
                "current slot must project to the guest process name: " + projected.processName);
        check(projected.pkgList[0].equals("guest.example"), "pkgList must project to guest");
        check("host.example:guest2".equals(current.processName), "original record must stay host");
        ProcessRecord filled = new ProcessRecord();
        filled.pid = android.os.Process.myPid();
        filled.processName = "host.example:guest2";
        rewriter.rewriteOutboundInPlace(filled);
        check(filled.processName.equals("guest.example:main"),
                "out-param process name must be overwritten in place");
    }

    private static final class ProcessRecord {
        public int pid;
        public int uid;
        public String processName;
        public String[] pkgList;
    }

    private static void testFailClosedWithoutInterfaces() {
        NoInterfaceOwner.SINGLETON.mInstance = new NoInterfaceService();
        FrameworkServiceSpec spec = new FrameworkServiceSpec(
                "no-interface",
                NoInterfaceOwner.class.getName(),
                "SINGLETON",
                List.of(MethodIdentityPolicy.of("call", 0)),
                Set.of());
        FrameworkProxyInstaller.InstallOutcome outcome = new FrameworkProxyInstaller()
                .install(spec, context(), ProxyTelemetry.NO_OP);
        check(!outcome.report().passed(), "proxy without interfaces must fail closed");
        check(outcome.report().interfaces().isEmpty(),
                "empty-interface report must remain empty");
        check(!outcome.report().failure().isBlank(), "failure detail required");
    }

    private static FrameworkServiceSpec fakeSpec() {
        return fakeSpec(FakeOwner.class);
    }

    private static FrameworkServiceSpec fakeSpec(Class<?> owner) {
        return new FrameworkServiceSpec(
                "fake-activity",
                owner.getName(),
                "SINGLETON",
                List.of(
                        MethodIdentityPolicy.of(
                                "startActivity",
                                3,
                                ArgumentRewriteRule.packageName(0),
                                ArgumentRewriteRule.uid(1),
                                ArgumentRewriteRule.packageNameArray(2)),
                        MethodIdentityPolicy.of(
                                "startOfficialLike",
                                10,
                                ArgumentRewriteRule.packageName(1)),
                        MethodIdentityPolicy.of(
                                "fail",
                                1,
                                ArgumentRewriteRule.packageName(0))),
                Set.of("getRunningAppProcesses"));
    }

    private static IdentityContext context() {
        return new IdentityContext(
                "guest.example",
                11234,
                "host.example",
                10001,
                "guest.example:main",
                2,
                7);
    }

    private static void rollback(FrameworkProxyInstaller.InstallOutcome outcome) {
        check(outcome.installedProxy() != null, "install handle required");
        try {
            outcome.installedProxy().rollback();
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public interface FakeService {
        String startActivity(String packageName, int uid, String[] packages);

        String startOfficialLike(
                Object caller,
                String packageName,
                Object intent,
                String resolvedType,
                Object resultTo,
                String resultWho,
                int requestCode,
                int flags,
                Object profiler,
                Object options);

        String unrelated(String packageName, int uid);

        List<Object> getRunningAppProcesses();

        void fail(String packageName);
    }

    public static class FakeServiceImpl implements FakeService {
        @Override
        public String startActivity(String packageName, int uid, String[] packages) {
            return packageName + "|" + uid + "|" + String.join(",", packages);
        }

        @Override
        public String startOfficialLike(
                Object caller,
                String packageName,
                Object intent,
                String resolvedType,
                Object resultTo,
                String resultWho,
                int requestCode,
                int flags,
                Object profiler,
                Object options) {
            return packageName + "|" + requestCode + "|" + flags;
        }

        @Override
        public String unrelated(String packageName, int uid) {
            return packageName + "|" + uid;
        }

        @Override
        public List<Object> getRunningAppProcesses() {
            return List.of("host.example", 10001, "host.example:remote");
        }

        @Override
        public void fail(String packageName) {
            throw new IllegalStateException(packageName);
        }
    }

    public interface SecondaryService extends FakeService {
    }

    public interface MultipleService extends FakeService, SecondaryService {
    }

    public static final class MultipleServiceImpl extends FakeServiceImpl
            implements MultipleService, FakeService {
    }

    public static final class MultipleOwner {
        public static final FakeSingleton SINGLETON = new FakeSingleton();

        private MultipleOwner() {
        }
    }

    public static final class FakeSingleton {
        public Object mInstance;

        public Object get() {
            return mInstance;
        }
    }

    public static final class FakeOwner {
        public static final FakeSingleton SINGLETON = new FakeSingleton();

        private FakeOwner() {
        }
    }

    public interface UnsupportedService {
        String startActivity(String packageName);
    }

    public static final class UnsupportedServiceImpl implements UnsupportedService {
        @Override
        public String startActivity(String packageName) {
            return packageName;
        }
    }

    public static final class UnsupportedOwner {
        public static final FakeSingleton SINGLETON = new FakeSingleton();

        private UnsupportedOwner() {
        }
    }

    public interface WrongTypeService {
        String startActivity(Object packageName, int uid, String[] packages);
    }

    public static final class WrongTypeServiceImpl implements WrongTypeService {
        @Override public String startActivity(Object packageName, int uid, String[] packages) {
            return String.valueOf(packageName);
        }
    }

    public static final class WrongTypeOwner {
        public static final FakeSingleton SINGLETON = new FakeSingleton();
        private WrongTypeOwner() { }
    }

    public static final class NoInterfaceService {
        public void call() {
        }
    }

    public static final class NoInterfaceOwner {
        public static final FakeSingleton SINGLETON = new FakeSingleton();

        private NoInterfaceOwner() {
        }
    }
}
