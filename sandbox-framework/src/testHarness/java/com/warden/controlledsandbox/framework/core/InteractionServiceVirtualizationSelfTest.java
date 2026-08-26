package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side deterministic tests for M5-T9 Window/Input/Display source virtualization. */
public final class InteractionServiceVirtualizationSelfTest {
    public static void main(String[] args) {
        GuestIdentity identity = identity(profile(VirtualWindowPolicySnapshot.MODE_STATIC));
        testWindow(identity);
        testActivityClient(identity);
        testInputMethod(identity);
        testDisplay(identity);
        testFailureRollback();
        testHostMode();
        identity.interactions().close();
        require(identity.interactions().windows().windowCount() == 0
                && identity.interactions().inputMethods().size() == 0,
                "interaction state closes deterministically");
        System.out.println("PASS M5-T9 interaction-service virtualization self-test");
    }

    private static void testWindow(GuestIdentity identity) {
        FakeWindowDelegate delegate = new FakeWindowDelegate();
        WindowApi api = proxy(WindowApi.class, delegate, identity, "window");
        WindowSessionApi session = api.openSession();
        require(session != delegate.session, "window session is wrapped");
        require(identity.interactions().windows().sessionCount() == 1,
                "window session ownership is recorded");
        require(api.getDefaultDisplayRotation() == 1 && delegate.calls == 1,
                "rotation is projected without host query");
        FakePoint point = new FakePoint();
        api.getInitialDisplaySize(7, point);
        require(point.x == 1440 && point.y == 2560,
                "window display dimensions are projected");

        Object token = new WindowToken();
        FakeLayoutParams params = new FakeLayoutParams("guest.pkg", 1, 0x2000);
        session.addToDisplay(token, params, 7);
        require("host.pkg".equals(delegate.session.seenPackage)
                        && delegate.session.seenFlags == 0,
                "window delegate sees rewritten package and secure flag");
        require("guest.pkg".equals(params.packageName) && params.flags == 0x2000,
                "window arguments are restored after delegate call");
        require(identity.interactions().windows().owns(delegate.session, token),
                "window token is owned by its session");
        session.relayout(token, params, 7);
        require(identity.interactions().windows().owns(delegate.session, token),
                "window token remains owned across relayout");
        session.remove(token);
        require(identity.interactions().windows().windowCount() == 0,
                "window removal releases ownership");

        boolean alertDenied = false;
        try {
            session.addToDisplay(new WindowToken(), new FakeLayoutParams("guest.pkg", 2003, 0), 7);
        } catch (SecurityException expected) {
            alertDenied = expected.getMessage().contains("SYSTEM_ALERT_WINDOW_DENIED");
        }
        require(alertDenied, "system-alert window fails closed");
    }

    private static void testActivityClient(GuestIdentity identity) {
        FakeActivityClientDelegate delegate = new FakeActivityClientDelegate();
        ActivityClientApi api = proxy(ActivityClientApi.class, delegate, identity, "activityClient");
        Object token = new ActivityToken();
        api.activityResumed(token, "guest.pkg");
        require("RESUMED".equals(identity.interactions().activities().state(token)),
                "activity resumed state is tracked");
        api.setTaskDescription(token, new FakeTaskDescription("Virtual task"));
        api.activityDestroyed(token);
        require(identity.interactions().activities().size() == 0 && delegate.calls == 3,
                "activity destruction clears process-local lifecycle state");
    }

    private static void testInputMethod(GuestIdentity identity) {
        FakeInputMethodDelegate delegate = new FakeInputMethodDelegate();
        InputMethodApi api = proxy(InputMethodApi.class, delegate, identity, "inputMethod");
        require(api.getInputMethodList().isEmpty() && delegate.calls == 0,
                "host input-method catalog is hidden");
        Object client = new InputClient();
        Object focus = new WindowToken();
        FakeEditorInfo info = new FakeEditorInfo("guest.pkg", 4, 11);
        require(api.startInput(client, focus, info), "virtual input session starts");
        require("host.pkg".equals(delegate.seenPackage) && delegate.seenUser == 0,
                "IME delegate sees host identity");
        require("guest.pkg".equals(info.packageName) && info.targetInputMethodUserId == 11,
                "EditorInfo identity is restored");
        require(identity.interactions().inputMethods().active(client),
                "input session ownership is recorded");
        require(api.hideSoftInput(new WindowToken()),
                "normal input hide delegates successfully");
        api.showInputMethodPicker();
        require(delegate.pickerCalls == 0, "IME picker is denied by policy");
        api.finishInput(client);
        require(identity.interactions().inputMethods().size() == 0,
                "input session is released");
    }

    private static void testDisplay(GuestIdentity identity) {
        FakeDisplayDelegate delegate = new FakeDisplayDelegate();
        DisplayApi api = proxy(DisplayApi.class, delegate, identity, "display");
        int[] ids = api.getDisplayIds();
        require(ids.length == 1 && ids[0] == 7 && delegate.calls == 0,
                "display IDs are virtualized");
        FakeDisplayInfo info = api.getDisplayInfo(7);
        require(info.displayId == 7 && info.logicalWidth == 1440
                        && info.logicalHeight == 2560 && info.rotation == 1,
                "display metrics are projected");
        FakePoint point = api.getStableDisplaySize();
        require(point.x == 1440 && point.y == 2560,
                "stable display size is virtualized");
        boolean denied = false;
        try { api.createVirtualDisplay("guest-display", new DisplayCallback()); }
        catch (SecurityException expected) {
            denied = expected.getMessage().contains("VIRTUAL_DISPLAY_CREATE_DENIED");
        }
        require(denied && identity.interactions().displays().size() == 0,
                "virtual display creation fails closed by default");
    }


    private static void testFailureRollback() {
        GuestIdentity identity = identity(profile(VirtualWindowPolicySnapshot.MODE_STATIC));
        FailingWindowSession failing = new FailingWindowSession();
        WindowSessionApi session = proxy(WindowSessionApi.class, failing, identity, "windowSession");
        Object token = new WindowToken();
        boolean failed = false;
        try { session.addToDisplay(token, new FakeLayoutParams("guest.pkg", 1, 0), 7); }
        catch (IllegalStateException expected) { failed = true; }
        require(failed && identity.interactions().windows().windowCount() == 0,
                "failed window add rolls back ownership");

        FailingInputMethodDelegate imeDelegate = new FailingInputMethodDelegate();
        InputMethodApi ime = proxy(InputMethodApi.class, imeDelegate, identity, "inputMethod");
        Object client = new InputClient();
        failed = false;
        try { ime.startInput(client, new WindowToken(), new FakeEditorInfo("guest.pkg", 1, 3)); }
        catch (IllegalStateException expected) { failed = true; }
        require(failed && identity.interactions().inputMethods().size() == 0,
                "failed input start rolls back ownership");

        InputMethodApi stale = proxy(InputMethodApi.class,
                new StaleInputMethodDelegate(), identity, "inputMethod");
        require(!stale.hideSoftInput(new WindowToken()),
                "stale input hide returns a safe default instead of crashing the Guest");
    }

    private static void testHostMode() {
        GuestIdentity identity = identity(profile(VirtualWindowPolicySnapshot.MODE_HOST));
        FakeDisplayDelegate delegate = new FakeDisplayDelegate();
        DisplayApi api = proxy(DisplayApi.class, delegate, identity, "display");
        require(api.getDisplayIds()[0] == 99 && delegate.calls == 1,
                "HOST display mode passes through");
        FakeInputMethodDelegate ime = new FakeInputMethodDelegate();
        InputMethodApi input = proxy(InputMethodApi.class, ime, identity, "inputMethod");
        require(input.getInputMethodList().equals(List.of("host.ime")) && ime.calls == 1,
                "HOST input-method mode passes through");
        FakeActivityClientDelegate activityDelegate = new FakeActivityClientDelegate();
        ActivityClientApi activity = proxy(ActivityClientApi.class, activityDelegate, identity, "activityClient");
        Object token = new ActivityToken();
        activity.activityResumed(token, "guest.pkg");
        require(activityDelegate.calls == 1 && identity.interactions().activities().size() == 0,
                "HOST ActivityClient mode passes through without virtual state");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(InteractionServiceVirtualizationSelfTest.class.getClassLoader(),
                new Class<?>[]{type}, new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(VirtualInteractionProfileSnapshot interaction) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "guest.pkg"; info.uid = 12001;
        Set<String> permissions = Set.of();
        return new GuestIdentity("guest.pkg", 12001, info, permissions, "host.pkg", 10001,
                new VirtualPackageMetadata("guest.pkg", "", info, List.of()), "guest.pkg", 0, 9L,
                new VirtualPermissionPolicy(permissions, Map.of(), permissions),
                new SandboxAppOpsPolicy(Map.of()), event -> { }, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(deviceProfile(), interaction), "revision-m5-t9");
    }

    private static VirtualInteractionProfileSnapshot profile(String mode) {
        VirtualWindowPolicySnapshot window = new VirtualWindowPolicySnapshot(mode, 2,
                true, false, false, false);
        VirtualInputMethodProfileSnapshot input = new VirtualInputMethodProfileSnapshot(mode,
                "", List.of(), false, false, false, true, 2);
        VirtualDisplaySnapshot display = new VirtualDisplaySnapshot(7, "Virtual display",
                1440, 2560, 560, 560f, 560f, 60f, 1, 2, 0, true);
        VirtualDisplayProfileSnapshot displays = new VirtualDisplayProfileSnapshot(mode, 7,
                false, 0, List.of(display));
        return new VirtualInteractionProfileSnapshot(1L, 1L, window, input, displays);
    }

    private static VirtualDeviceServiceProfileSnapshot deviceProfile() {
        String mode = VirtualLocationProfileSnapshot.MODE_HOST;
        return new VirtualDeviceServiceProfileSnapshot(1L, 1L,
                new VirtualLocationProfileSnapshot(mode, "gps", false, 0, 0, 0, 1f,
                        0, 0, 0, 0, 1000, false, 0, 0, ""),
                new VirtualDeviceIdentitySnapshot(mode, "", "", "", false, "", "", "",
                        "", "", "", "", "", ""),
                new VirtualTelephonyProfileSnapshot(mode, -1, -1, false, false, false, List.of()),
                new VirtualWifiProfileSnapshot(mode, false, "", "", "", 0, -1, 0,
                        -127, 0, false, false, List.of()),
                new VirtualBluetoothProfileSnapshot(mode, false, 10, "", "", false,
                        List.of(), List.of()),
                new VirtualSensorProfileSnapshot(mode, 60, List.of()));
    }

    interface WindowApi {
        WindowSessionApi openSession();
        int getDefaultDisplayRotation();
        void getInitialDisplaySize(int displayId, FakePoint out);
    }
    interface WindowSessionApi {
        int addToDisplay(Object token, FakeLayoutParams params, int displayId);
        void relayout(Object token, FakeLayoutParams params, int displayId);
        void remove(Object token);
    }
    static final class FakeWindowDelegate implements WindowApi {
        int calls; final FakeWindowSession session = new FakeWindowSession();
        public WindowSessionApi openSession() { calls++; return session; }
        public int getDefaultDisplayRotation() { calls++; return 3; }
        public void getInitialDisplaySize(int displayId, FakePoint out) { calls++; out.x = 1; out.y = 1; }
    }
    static final class FailingWindowSession implements WindowSessionApi {
        public int addToDisplay(Object token, FakeLayoutParams params, int displayId) {
            throw new IllegalStateException("window failure");
        }
        public void relayout(Object token, FakeLayoutParams params, int displayId) { }
        public void remove(Object token) { }
    }

    static final class FakeWindowSession implements WindowSessionApi {
        String seenPackage; int seenFlags;
        public int addToDisplay(Object token, FakeLayoutParams params, int displayId) {
            seenPackage = params.packageName; seenFlags = params.flags; return 1;
        }
        public void relayout(Object token, FakeLayoutParams params, int displayId) { }
        public void remove(Object token) { }
    }
    public static final class FakeLayoutParams {
        public String packageName; public int type; public int flags;
        FakeLayoutParams(String packageName, int type, int flags) {
            this.packageName = packageName; this.type = type; this.flags = flags;
        }
    }
    public static final class FakePoint { public int x; public int y; }
    static final class WindowToken { }

    interface ActivityClientApi {
        void activityResumed(Object token, String packageName);
        void setTaskDescription(Object token, FakeTaskDescription description);
        void activityDestroyed(Object token);
    }
    static final class FakeActivityClientDelegate implements ActivityClientApi {
        int calls;
        public void activityResumed(Object token, String packageName) { calls++; }
        public void setTaskDescription(Object token, FakeTaskDescription description) { calls++; }
        public void activityDestroyed(Object token) { calls++; }
    }
    static final class ActivityToken { }
    public static final class FakeTaskDescription {
        private final String label;
        FakeTaskDescription(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    interface InputMethodApi {
        List<String> getInputMethodList();
        boolean startInput(Object client, Object focus, FakeEditorInfo info);
        boolean hideSoftInput(Object token);
        void finishInput(Object client);
        void showInputMethodPicker();
    }
    static final class FakeInputMethodDelegate implements InputMethodApi {
        int calls; int pickerCalls; String seenPackage; int seenUser;
        public List<String> getInputMethodList() { calls++; return List.of("host.ime"); }
        public boolean startInput(Object client, Object focus, FakeEditorInfo info) {
            calls++; seenPackage = info.packageName; seenUser = info.targetInputMethodUserId; return true;
        }
        public boolean hideSoftInput(Object token) { calls++; return true; }
        public void finishInput(Object client) { calls++; }
        public void showInputMethodPicker() { calls++; pickerCalls++; }
    }
    static final class FailingInputMethodDelegate implements InputMethodApi {
        public List<String> getInputMethodList() { return List.of(); }
        public boolean startInput(Object client, Object focus, FakeEditorInfo info) {
            throw new IllegalStateException("input failure");
        }
        public boolean hideSoftInput(Object token) { throw new IllegalArgumentException("unknown client stale"); }
        public void finishInput(Object client) { }
        public void showInputMethodPicker() { }
    }
    static final class StaleInputMethodDelegate implements InputMethodApi {
        public List<String> getInputMethodList() { return List.of(); }
        public boolean startInput(Object client, Object focus, FakeEditorInfo info) { return true; }
        public boolean hideSoftInput(Object token) { throw new IllegalArgumentException("unknown client stale"); }
        public void finishInput(Object client) { }
        public void showInputMethodPicker() { }
    }

    public static final class FakeEditorInfo {
        public String packageName; public int fieldId; public int targetInputMethodUserId;
        FakeEditorInfo(String packageName, int fieldId, int targetInputMethodUserId) {
            this.packageName = packageName; this.fieldId = fieldId;
            this.targetInputMethodUserId = targetInputMethodUserId;
        }
    }
    static final class InputClient { }

    interface DisplayApi {
        int[] getDisplayIds();
        FakeDisplayInfo getDisplayInfo(int displayId);
        FakePoint getStableDisplaySize();
        Object createVirtualDisplay(String name, Object callback);
    }
    static final class FakeDisplayDelegate implements DisplayApi {
        int calls;
        public int[] getDisplayIds() { calls++; return new int[]{99}; }
        public FakeDisplayInfo getDisplayInfo(int displayId) { calls++; return new FakeDisplayInfo(); }
        public FakePoint getStableDisplaySize() { calls++; FakePoint point = new FakePoint(); point.x=9; point.y=9; return point; }
        public Object createVirtualDisplay(String name, Object callback) { calls++; return callback; }
    }
    public static final class FakeDisplayInfo {
        public int displayId; public String name; public int logicalWidth; public int logicalHeight;
        public int logicalDensityDpi; public float physicalXDpi; public float physicalYDpi;
        public float refreshRate; public int rotation; public int state; public int flags;
    }
    static final class DisplayCallback { }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
