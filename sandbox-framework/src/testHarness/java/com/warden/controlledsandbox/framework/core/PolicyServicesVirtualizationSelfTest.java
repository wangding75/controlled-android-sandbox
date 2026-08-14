package com.warden.controlledsandbox.framework.core;
import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.*;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
/** Host-side DevicePolicy/Accessibility/Autofill/Biometric/Privacy/Power behavior tests. */ public final class PolicyServicesVirtualizationSelfTest {
    public static void main(String[] args) {
        GuestIdentity identity = identity("STATIC");
        DevicePolicyApi devicePolicy = proxy(DevicePolicyApi.class, new DevicePolicyDelegate(), identity, "devicepolicy");
        require(devicePolicy.isAdminActive(null) && devicePolicy.getCameraDisabled(null, 0), "device policy projected");
        require(devicePolicy.getPasswordMinimumLength(null, 0) == 8, "password policy projected");
        boolean denied = false;
        try {
            devicePolicy.wipeData(0);
        } catch (SecurityException expected) {
            denied = true;
        }
        require(denied, "device policy mutation denied");
        AccessibilityApi accessibility = proxy(AccessibilityApi.class, new AccessibilityDelegate(), identity, "accessibility");
        Object client = new Object();
        require(accessibility.isEnabled() && accessibility.isTouchExplorationEnabled(), "accessibility state projected");
        accessibility.addClient(client, 0);
        accessibility.removeClient(client, 0);
        Object windowToken = new Object();
        Object interactionConnection = new Object();
        int windowId = accessibility.addAccessibilityInteractionConnection(windowToken, "guest.pkg", interactionConnection);
        require(windowId > 0, "accessibility interaction connection is locally owned");
        accessibility.removeAccessibilityInteractionConnection(windowToken);
        require(accessibility.getRecommendedTimeoutMillis(10L, 0) == 5000L, "accessibility timeout projected");
        require(AccessibilityInvocationClassifier.classify("sendAccessibilityEvent")
                        == AccessibilityInvocationClass.APP_LOCAL_EVENT,
                "sendAccessibilityEvent is APP_LOCAL_EVENT");
        require(AccessibilityInvocationClassifier.classify("setAccessibilityWindowAttributes")
                        == AccessibilityInvocationClass.APP_LOCAL_WINDOW_METADATA,
                "window attributes are APP_LOCAL_WINDOW_METADATA");
        require(AccessibilityInvocationClassifier.classify("interrupt")
                        == AccessibilityInvocationClass.HOST_ACCESSIBILITY_STATE_MUTATION,
                "interrupt is host accessibility mutation");
        require(AccessibilityInvocationClassifier.classify("getWindows")
                        == AccessibilityInvocationClass.CROSS_APP_ACCESSIBILITY_DATA,
                "getWindows is cross-app data");
        require(AccessibilityInvocationClassifier.classify("setSecureSetting")
                        == AccessibilityInvocationClass.SECURE_SETTING_MUTATION,
                "secure setting write is SECURE_SETTING_MUTATION");
        accessibility.sendAccessibilityEvent(new AccessibilityEvent("guest.pkg"));
        accessibility.setAccessibilityWindowAttributes(windowToken, new Object());
        boolean interruptDenied = false;
        try { accessibility.interrupt(0); }
        catch (SecurityException expected) {
            interruptDenied = expected.getMessage().contains("MUTATION_DENIED");
        }
        require(interruptDenied, "interrupt is denied, not treated as ViewRoot bookkeeping");
        boolean crossAppDenied = false;
        try { accessibility.sendAccessibilityEvent(new AccessibilityEvent("other.pkg")); }
        catch (SecurityException expected) {
            crossAppDenied = expected.getMessage().contains("CROSS_APP_DENIED");
        }
        require(crossAppDenied, "cross-app accessibility events are denied");
        boolean windowsDenied = false;
        try { accessibility.getWindows(); }
        catch (SecurityException expected) {
            windowsDenied = expected.getMessage().contains("CROSS_APP_DENIED");
        }
        require(windowsDenied, "cross-app window trees are denied");
        boolean secureDenied = false;
        try { accessibility.setSecureSetting("enabled_accessibility_services", "x"); }
        catch (SecurityException expected) {
            secureDenied = expected.getMessage().contains("MUTATION_DENIED");
        }
        require(secureDenied, "secure settings mutation is denied");
        AutofillApi autofill = proxy(AutofillApi.class, new AutofillDelegate(), identity, "autofill");
        Object autofillClient = new Object();
        int session = autofill.startSession(autofillClient, "guest.pkg");
        require(session > 0 && autofill.isServiceEnabled(), "autofill session reserved");
        autofill.finishSession(session, 0);
        require(autofill.startSession(new Object(), "guest.pkg") > 0 && autofill.startSession(new Object(), "guest.pkg") > 0, "finished Autofill session releases quota");
        BiometricApi biometric = proxy(BiometricApi.class, new BiometricDelegate(), identity, "biometric");
        require(biometric.isHardwareDetected("guest.pkg") && biometric.hasEnrolledBiometrics(0, "guest.pkg"), "biometric capabilities projected");
        require(biometric.canAuthenticate("guest.pkg", 0, 15) == 0, "biometric availability projected");
        boolean adapterRequired = false;
        try {
            biometric.authenticate(new Object(), 1L, 0, new Object(), "guest.pkg");
        } catch (IllegalStateException expected) {
            adapterRequired = expected.getMessage().contains("CALLBACK_ADAPTER_REQUIRED");
        }
        require(adapterRequired, "biometric authentication fails closed at callback adapter boundary");
        SensorPrivacyApi privacy = proxy(SensorPrivacyApi.class, new SensorPrivacyDelegate(), identity, "sensorprivacy");
        require(privacy.supportsSensorToggle(2) && privacy.isSensorPrivacyEnabled(0, 2), "camera privacy projected");
        Object listener = new Object();
        privacy.addSensorPrivacyListener(listener);
        privacy.removeSensorPrivacyListener(listener);
        PowerApi power = proxy(PowerApi.class, new PowerDelegate(), identity, "power");
        require(!power.isInteractive() && power.isPowerSaveMode() && power.isDeviceIdleMode(), "power state projected");
        Object wakeToken = new Object();
        power.acquireWakeLock(wakeToken, 1, "tag", "guest.pkg", null, null, 5000L);
        power.releaseWakeLock(wakeToken, 0);
        boolean rebootDenied = false;
        try {
            power.reboot(false, "test", false);
        } catch (SecurityException expected) {
            rebootDenied = true;
        }
        require(rebootDenied, "power mutation denied");
        VibratorApi vibrator = proxy(VibratorApi.class, new VibratorDelegate(), identity, "vibrator");
        require(!vibrator.hasVibrator(), "vibration disabled by profile");
        boolean vibrationDenied = false;
        try {
            vibrator.vibrate(1000L, "guest.pkg");
        } catch (SecurityException expected) {
            vibrationDenied = true;
        }
        require(vibrationDenied, "vibration denied");
        GuestIdentity host = identity("HOST");
        DevicePolicyDelegate hostDelegate = new DevicePolicyDelegate();
        proxy(DevicePolicyApi.class, hostDelegate, host, "devicepolicy").isAdminActive(null);
        require(hostDelegate.calls == 1, "HOST policy passes through");
        System.out.println("PASS M5-T13 policy-services virtualization self-test");
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{
            type
        }
        , new SystemServiceInvocationHandler(delegate, identity, service));
    }
    private static GuestIdentity identity(String mode) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName="guest.pkg";
        app.uid=12001;
        VirtualDeviceServiceProfileSnapshot device = new VirtualDeviceServiceProfileSnapshot(1L, 0L, new VirtualLocationProfileSnapshot("BLOCKED", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, ""), new VirtualDeviceIdentitySnapshot("STATIC", "0123456789abcdef", "serial", "11111111-2222-3333-4444-555555555555", true, "install", "b", "m", "model", "d", "p", "fp", "board", "hw"), new VirtualTelephonyProfileSnapshot("BLOCKED", -1, -1, false, false, false, List.of()), new VirtualWifiProfileSnapshot("BLOCKED", false, "", "", "", 0, -1, 0, -127, 0, false, false, List.of()), new VirtualBluetoothProfileSnapshot("BLOCKED", false, 10, "", "", false, List.of(), List.of()), new VirtualSensorProfileSnapshot("BLOCKED", 1, List.of()));
        VirtualPolicyServicesProfileSnapshot policy = new VirtualPolicyServicesProfileSnapshot(1L, 0L, new VirtualDevicePolicyProfileSnapshot(mode, true, true, false, true, true, true, 65536, 8, 5), new VirtualAccessibilityProfileSnapshot(mode, true, true, true, true, 4, 5000L, List.of("guest/.Accessibility")), new VirtualAutofillProfileSnapshot(mode, true, "guest/.Autofill", true, false, 2, 60000L), new VirtualBiometricProfileSnapshot(mode, true, true, 15, 2, true, true, "SUCCESS", 2, 100L), new VirtualSensorPrivacyProfileSnapshot(mode, false, true, false, false, 4), new VirtualPowerProfileSnapshot(mode, false, true, true, false, 2, 10000L, false, 0, 0L));
        return new GuestIdentity("guest.pkg", 12001, app, Set.of(), "host.pkg", 10001, new VirtualPackageMetadata("guest.pkg", "", app, List.of()), "guest.pkg", 0, 1, new VirtualPermissionPolicy(Set.of(), Map.of()), new SandboxAppOpsPolicy(Map.of()), event->{
        }
        , new CapabilityLeaseRegistry(), new VirtualSystemServiceState(device, null, null, null, null, policy), "rev");
    }
    interface DevicePolicyApi {
        boolean isAdminActive(Object admin);
        boolean getCameraDisabled(Object admin, int userId);
        int getPasswordMinimumLength(Object admin, int userId);
        void wipeData(int flags);
    }
    interface AccessibilityApi {
        boolean isEnabled();
        boolean isTouchExplorationEnabled();
        long getRecommendedTimeoutMillis(long original, int flags);
        void addClient(Object client, int userId);
        void removeClient(Object client, int userId);
        int addAccessibilityInteractionConnection(Object windowToken, String packageName, Object connection);
        void removeAccessibilityInteractionConnection(Object windowToken);
        void sendAccessibilityEvent(Object event);
        void setAccessibilityWindowAttributes(Object windowToken, Object attributes);
        void interrupt(int userId);
        Object getWindows();
        void setSecureSetting(String name, String value);
    }

    static final class AccessibilityEvent {
        private final String packageName;
        AccessibilityEvent(String packageName) { this.packageName = packageName; }
        public String getPackageName() { return packageName; }
    }
    interface AutofillApi {
        int startSession(Object client, String packageName);
        void finishSession(int sessionId, int userId);
        boolean isServiceEnabled();
    }
    interface BiometricApi {
        boolean isHardwareDetected(String packageName);
        boolean hasEnrolledBiometrics(int userId, String packageName);
        int canAuthenticate(String packageName, int userId, int authenticators);
        void authenticate(Object token, long operationId, int userId, Object callback, String packageName);
    }
    interface SensorPrivacyApi {
        boolean supportsSensorToggle(int sensor);
        boolean isSensorPrivacyEnabled(int userId, int sensor);
        void addSensorPrivacyListener(Object listener);
        void removeSensorPrivacyListener(Object listener);
    }
    interface PowerApi {
        boolean isInteractive();
        boolean isPowerSaveMode();
        boolean isDeviceIdleMode();
        void acquireWakeLock(Object token, int flags, String tag, String packageName, Object workSource, Object historyTag, long timeout);
        void releaseWakeLock(Object token, int flags);
        void reboot(boolean confirm, String reason, boolean wait);
    }
    interface VibratorApi {
        boolean hasVibrator();
        void vibrate(long duration, String packageName);
    }
    static final class DevicePolicyDelegate implements DevicePolicyApi {
        int calls;
        public boolean isAdminActive(Object a){
            calls++;
            return false;
        }
        public boolean getCameraDisabled(Object a, int u){
            calls++;
            return false;
        }
        public int getPasswordMinimumLength(Object a, int u){
            calls++;
            return 0;
        }
        public void wipeData(int f){
            calls++;
        }
    }
    static final class AccessibilityDelegate implements AccessibilityApi {
        public boolean isEnabled(){
            return false;
        }
        public boolean isTouchExplorationEnabled(){
            return false;
        }
        public long getRecommendedTimeoutMillis(long o, int f){
            return o;
        }
        public void addClient(Object c, int u){
        }
        public void removeClient(Object c, int u){
        }
        public int addAccessibilityInteractionConnection(Object w, String p, Object c){
            return -1;
        }
        public void removeAccessibilityInteractionConnection(Object w){
        }
        public void sendAccessibilityEvent(Object event){
        }
        public void setAccessibilityWindowAttributes(Object windowToken, Object attributes){
        }
        public void interrupt(int userId){
        }
        public Object getWindows(){
            return null;
        }
        public void setSecureSetting(String name, String value){
        }
    }
    static final class AutofillDelegate implements AutofillApi {
        public int startSession(Object c, String p){
            return -1;
        }
        public void finishSession(int s, int u){
        }
        public boolean isServiceEnabled(){
            return false;
        }
    }
    static final class BiometricDelegate implements BiometricApi {
        public boolean isHardwareDetected(String p){
            return false;
        }
        public boolean hasEnrolledBiometrics(int u, String p){
            return false;
        }
        public int canAuthenticate(String p, int u, int a){
            return 12;
        }
        public void authenticate(Object t, long o, int u, Object c, String p){
        }
    }
    static final class SensorPrivacyDelegate implements SensorPrivacyApi {
        public boolean supportsSensorToggle(int s){
            return false;
        }
        public boolean isSensorPrivacyEnabled(int u, int s){
            return false;
        }
        public void addSensorPrivacyListener(Object l){
        }
        public void removeSensorPrivacyListener(Object l){
        }
    }
    static final class PowerDelegate implements PowerApi {
        public boolean isInteractive(){
            return true;
        }
        public boolean isPowerSaveMode(){
            return false;
        }
        public boolean isDeviceIdleMode(){
            return false;
        }
        public void acquireWakeLock(Object t, int f, String g, String p, Object w, Object h, long x){
        }
        public void releaseWakeLock(Object t, int f){
        }
        public void reboot(boolean c, String r, boolean w){
        }
    }
    static final class VibratorDelegate implements VibratorApi {
        public boolean hasVibrator(){
            return true;
        }
        public void vibrate(long d, String p){
        }
    }
    private static void require(boolean value, String message){
        if(!value)throw new AssertionError(message);
    }
}
