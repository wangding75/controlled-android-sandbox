package com.warden.controlledsandbox.framework.core;
import com.warden.controlledsandbox.contract.VirtualAccessibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualAutofillProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualBiometricProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDevicePolicyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPowerProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorPrivacyProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
/** Source-side DevicePolicy/Accessibility/Autofill/Biometric/Privacy/Power virtualization. */ final class PolicyServicesInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final Set<Object> accessibilityClients = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Integer> accessibilityInteractionConnections = new IdentityHashMap<>();
    private final Set<Object> privacyListeners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer, Long> autofillSessions = new LinkedHashMap<>();
    private final Map<Object, Long> biometricSessions = new IdentityHashMap<>();
    private final Map<Object, WakeLockLease> wakeLocks = new IdentityHashMap<>();
    private final Map<Object, Long> vibrations = new IdentityHashMap<>();
    private final AtomicInteger nextAutofillSession = new AtomicInteger(1);
    private final AtomicInteger nextAccessibilityWindowId = new AtomicInteger(1);
    PolicyServicesInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
    }
    synchronized Decision before(Method method, Object[] arguments) {
        VirtualPolicyServicesProfileSnapshot profile;
        try {
            profile = identity.virtualServices().policyServicesProfile();
        } catch (IllegalStateException unavailable) {
            if ("VIRTUAL_POLICY_SERVICES_PROFILE_AUTHORITY_REQUIRED".equals(unavailable.getMessage()) || "VIRTUAL_POLICY_SERVICES_PROFILE_NOT_AVAILABLE".equals(unavailable.getMessage())) {
                return Decision.passThrough();
            }
            throw unavailable;
        }
        expireLeases(System.currentTimeMillis());
        return switch (service) {
            case "devicepolicy" -> devicePolicy(method, arguments, profile.devicePolicy());
            case "accessibility" -> accessibility(method, arguments, profile.accessibility());
            case "autofill" -> autofill(method, arguments, profile.autofill());
            case "biometric", "fingerprint" -> biometric(method, arguments, profile.biometric());
            case "sensorprivacy" -> sensorPrivacy(method, arguments, profile.sensorPrivacy());
            case "power", "vibrator" -> power(method, arguments, profile.power());
            default -> Decision.passThrough();
        };
    }
    synchronized int accessibilityClientCount() {
        return accessibilityClients.size() + accessibilityInteractionConnections.size();
    }
    synchronized int autofillSessionCount() {
        return autofillSessions.size();
    }
    synchronized int biometricSessionCount() {
        return biometricSessions.size();
    }
    synchronized int wakeLockCount() {
        expireLeases(System.currentTimeMillis());
        return wakeLocks.size();
    }
    synchronized int vibrationCount() {
        return vibrations.size();
    }
    private Decision devicePolicy(Method method, Object[] arguments, VirtualDevicePolicyProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (mutation(name)) {
            throw new SecurityException("VIRTUAL_DEVICE_POLICY_MUTATION_DENIED:" + method.getName());
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "isadminactive", "hasgrantedpolicy")) return handledBoolean(method, profile.adminActive());
        if (containsAny(name, "isprofileownerapp", "ismanagedprofile")) return handledBoolean(method, profile.profileOwner());
        if (containsAny(name, "isdeviceownerapp", "isdeviceowner")) return handledBoolean(method, profile.deviceOwner());
        if (containsAny(name, "getcameradisabled")) return handledBoolean(method, profile.cameraDisabled());
        if (containsAny(name, "getscreencapturedisabled")) return handledBoolean(method, profile.screenCaptureDisabled());
        if (containsAny(name, "getstorageencryptionstatus", "getstorageencryption")) {
            return Decision.handled(numeric(method.getReturnType(), profile.storageEncryptionEnabled() ? 3L : 1L));
        }
        if (containsAny(name, "getpasswordquality")) return Decision.handled(numeric(method.getReturnType(), profile.passwordQuality()));
        if (containsAny(name, "getpasswordminimumlength")) return Decision.handled(numeric(method.getReturnType(), profile.minimumPasswordLength()));
        if (containsAny(name, "getmaximumfailedpasswordsforwipe", "getcurrentfailedpasswordattempts")) {
            return Decision.handled(numeric(method.getReturnType(), profile.maximumFailedPasswords()));
        }
        if (containsAny(name, "getactiveadmins", "getactiveadminsasuser", "getownerinstalledca", "getinstalledca")) {
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "getprofileowner", "getdeviceowner", "getprofileownername", "getdeviceownername")) {
            return Decision.handled(nullValue(method.getReturnType()));
        }
        return failUnsupported("device_policy", method);
    }
    private Decision accessibility(Method method, Object[] arguments, VirtualAccessibilityProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "removeclient", "unregister", "removeaccessibilityservicesstatelistener", "removeaccessibilityinteractionconnection")) {
            remove(accessibilityClients, arguments);
            if (containsAny(name, "removeaccessibilityinteractionconnection")) {
                Object windowToken = callback(arguments);
                if (windowToken != null) accessibilityInteractionConnections.remove(windowToken);
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) {
            if (isCleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "addaccessibilityinteractionconnection")) {
            Object windowToken = callback(arguments);
            Object connection = lastCallback(arguments);
            if (windowToken == null || connection == null || windowToken == connection) {
                throw new IllegalArgumentException("VIRTUAL_ACCESSIBILITY_INTERACTION_CONNECTION_REQUIRED");
            }
            Integer windowId = accessibilityInteractionConnections.get(windowToken);
            if (windowId == null) {
                if (accessibilityInteractionConnections.size() >= profile.maximumClients()) {
                    throw new IllegalStateException("VIRTUAL_ACCESSIBILITY_INTERACTION_CONNECTION_LIMIT_EXCEEDED");
                }
                windowId = nextAccessibilityWindowId.getAndIncrement();
                accessibilityInteractionConnections.put(windowToken, windowId);
            }
            return Decision.handled(numeric(method.getReturnType(), windowId));
        }
        if (containsAny(name, "addclient", "register", "addaccessibilityservicesstatelistener")) {
            Object callback = callback(arguments);
            if (callback == null) throw new IllegalArgumentException("VIRTUAL_ACCESSIBILITY_CLIENT_REQUIRED");
            addBounded(accessibilityClients, callback, profile.maximumClients(), "VIRTUAL_ACCESSIBILITY_CLIENT_LIMIT_EXCEEDED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "isenabled", "isaccessibilityenabled")) return handledBoolean(method, profile.enabled());
        if (containsAny(name, "istouchexplorationenabled")) return handledBoolean(method, profile.touchExplorationEnabled());
        if (containsAny(name, "ishightextcontrastenabled")) return handledBoolean(method, profile.highTextContrastEnabled());
        if (containsAny(name, "getrecommendedtimeoutmillis", "getrecommendedtimeout")) {
            return Decision.handled(numeric(method.getReturnType(), profile.recommendedTimeoutMs()));
        }
        if (containsAny(name, "getenabledaccessibilityservicelist", "getinstalledaccessibilityservicelist", "getaccessibilityshortcuttargets")) {
            return Decision.handled(stringCollection(method.getReturnType(), profile.enabledServices()));
        }
        if (containsAny(name, "sendaccessibilityevent", "interrupt")) {
            if (!profile.allowEventDispatch()) throw new SecurityException("VIRTUAL_ACCESSIBILITY_EVENT_DENIED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (mutation(name)) throw new SecurityException("VIRTUAL_ACCESSIBILITY_MUTATION_DENIED:" + method.getName());
        return failUnsupported("accessibility", method);
    }
    private Decision autofill(Method method, Object[] arguments, VirtualAutofillProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "finishsession", "cancelsession", "destroysession")) {
            int sessionId = firstInt(arguments, -1);
            if (sessionId >= 0) autofillSessions.remove(sessionId);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode()) || !profile.enabled()) {
            if (isCleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            if (containsAny(name, "isenabled", "isserviceenabled", "hasservicedenabled")) return handledBoolean(method, false);
            if (containsAny(name, "startsession")) return Decision.handled(numeric(method.getReturnType(), -1));
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "isenabled", "isserviceenabled", "hasservicedenabled")) return handledBoolean(method, true);
        if (containsAny(name, "issaveui", "issaveenabled")) return handledBoolean(method, profile.saveEnabled());
        if (containsAny(name, "isaugmentedautofillserviceenabled", "isaugmentedautofill")) return handledBoolean(method, profile.augmentedAutofillEnabled());
        if (containsAny(name, "getautofillservicecomponentname", "getservicecomponent")) {
            return Decision.handled(componentValue(method.getReturnType(), profile.serviceComponent()));
        }
        if (containsAny(name, "startsession")) {
            if (autofillSessions.size() >= profile.maximumSessions()) {
                throw new IllegalStateException("VIRTUAL_AUTOFILL_SESSION_LIMIT_EXCEEDED");
            }
            int sessionId = nextAutofillSession.getAndIncrement();
            autofillSessions.put(sessionId, System.currentTimeMillis() + profile.sessionTimeoutMs());
            return Decision.handled(numeric(method.getReturnType(), sessionId));
        }
        if (containsAny(name, "updatesession", "sethassession", "setstate", "restoreSession")) {
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "setauthenticationresult", "setsaveresult", "requestsave")) {
            if (!profile.saveEnabled()) throw new SecurityException("VIRTUAL_AUTOFILL_SAVE_DENIED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (mutation(name)) throw new SecurityException("VIRTUAL_AUTOFILL_MUTATION_DENIED:" + method.getName());
        return failUnsupported("autofill", method);
    }
    private Decision biometric(Method method, Object[] arguments, VirtualBiometricProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "cancelauthentication", "cancelbiometric", "resetlockout")) {
            remove(biometricSessions.keySet(), arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "ishardwaredetected", "canauthenticatehardware")) {
            return handledBoolean(method, !blocked(profile.mode()) && profile.hardwareDetected());
        }
        if (containsAny(name, "hasenrolledbiometrics", "hasenrolledfingerprints", "isenrolled")) {
            return handledBoolean(method, !blocked(profile.mode()) && profile.enrolled());
        }
        if (containsAny(name, "getauthenticatorids", "getenrolled")) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "canauthenticate")) {
            int result = blocked(profile.mode()) || !profile.hardwareDetected() ? 12 : !profile.enrolled() ? 11 : !profile.allowAuthentication() ? 1 : 0;
            return Decision.handled(numeric(method.getReturnType(), result));
        }
        if (containsAny(name, "authenticate", "prepareforauthentication")) {
            if (blocked(profile.mode()) || !profile.allowAuthentication()) {
                throw new SecurityException("VIRTUAL_BIOMETRIC_AUTHENTICATION_DENIED");
            }
            Object callback = callback(arguments);
            if (callback == null) throw new IllegalArgumentException("VIRTUAL_BIOMETRIC_CALLBACK_REQUIRED");
            if (!biometricSessions.containsKey(callback) && biometricSessions.size() >= profile.maximumSessions()) {
                throw new IllegalStateException("VIRTUAL_BIOMETRIC_SESSION_LIMIT_EXCEEDED");
            }
            biometricSessions.put(callback, System.currentTimeMillis() + Math.max(1000L, profile.authenticationLatencyMs()+1000L));
            // Actual framework callback construction is version-specific; roll back before failing closed.
            biometricSessions.remove(callback);
            throw new IllegalStateException("VIRTUAL_BIOMETRIC_CALLBACK_ADAPTER_REQUIRED:" + profile.outcome());
        }
        if (mutation(name)) throw new SecurityException("VIRTUAL_BIOMETRIC_MUTATION_DENIED:" + method.getName());
        return failUnsupported("biometric", method);
    }
    private Decision sensorPrivacy(Method method, Object[] arguments, VirtualSensorPrivacyProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "removesensorprivacylistener", "removetogglesensorprivacylistener", "unregister")) {
            remove(privacyListeners, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) {
            if (isCleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "addsensorprivacylistener", "addtogglesensorprivacylistener", "register")) {
            Object callback=callback(arguments);
            if(callback==null)throw new IllegalArgumentException("VIRTUAL_SENSOR_PRIVACY_LISTENER_REQUIRED");
            addBounded(privacyListeners, callback, profile.maximumListeners(), "VIRTUAL_SENSOR_PRIVACY_LISTENER_LIMIT_EXCEEDED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "supportssensortoggle", "supportsprivacytoggle")) return handledBoolean(method, true);
        if (containsAny(name, "isallsensorprivacyenabled", "isallSensorPrivacyEnabled")) return handledBoolean(method, profile.allSensorsPrivacyEnabled());
        if (containsAny(name, "issensorprivacyenabled", "istogglesensorprivacyenabled")) {
            int sensor = firstInt(arguments, 0);
            boolean enabled = profile.allSensorsPrivacyEnabled() || (sensor == 1 ? profile.microphonePrivacyEnabled() : profile.cameraPrivacyEnabled());
            return handledBoolean(method, enabled);
        }
        if (containsAny(name, "setsensorprivacy", "setallsensorprivacy", "settoggle")) {
            if (!profile.allowChanges()) throw new SecurityException("VIRTUAL_SENSOR_PRIVACY_MUTATION_DENIED");
            // Durable profile changes must go through Package Service; runtime mutations remain isolated.
            return Decision.handled(successValue(method.getReturnType()));
        }
        return failUnsupported("sensor_privacy", method);
    }
    private Decision power(Method method, Object[] arguments, VirtualPowerProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (service.equals("vibrator")) return vibrator(method, arguments, profile);
        if (containsAny(name, "releasewakelock")) {
            Object token=token(arguments);
            if(token!=null)wakeLocks.remove(token);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) {
            if (isCleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "isinteractive", "isscreenon")) return handledBoolean(method, profile.interactive());
        if (containsAny(name, "ispowersavemode")) return handledBoolean(method, profile.powerSaveMode());
        if (containsAny(name, "isdeviceidlemode", "islightdeviceidlemode")) return handledBoolean(method, profile.deviceIdleMode());
        if (containsAny(name, "isignoringbatteryoptimizations")) return handledBoolean(method, profile.batteryOptimizationsIgnored());
        if (containsAny(name, "acquirewakelock")) {
            Object token=token(arguments);
            if(token==null)throw new IllegalArgumentException("VIRTUAL_WAKE_LOCK_TOKEN_REQUIRED");
            if(!wakeLocks.containsKey(token)&&wakeLocks.size()>=profile.maximumWakeLocks())throw new IllegalStateException("VIRTUAL_WAKE_LOCK_LIMIT_EXCEEDED");
            long requested=firstPositiveLong(arguments, profile.maximumWakeLockDurationMs());
            long bounded=Math.min(requested, profile.maximumWakeLockDurationMs());
            wakeLocks.put(token, new WakeLockLease(System.currentTimeMillis()+bounded));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "updatewakelock", "setwakelockworksource")) return Decision.handled(successValue(method.getReturnType()));
        if (containsAny(name, "useractivity")) return Decision.handled(successValue(method.getReturnType()));
        if (containsAny(name, "reboot", "shutdown", "got sleep", "gotosleep", "wakeup", "nap", "setpowersavemode")) {
            throw new SecurityException("VIRTUAL_POWER_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("power", method);
    }
    private Decision vibrator(Method method, Object[] arguments, VirtualPowerProfileSnapshot profile) {
        String name=normalize(method.getName());
        if (containsAny(name, "cancel", "off")) {
            Object token=token(arguments);
            if(token==null)vibrations.clear();
            else vibrations.remove(token);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "hasvibrator")) return handledBoolean(method, !blocked(profile.mode())&&profile.allowVibration());
        if (containsAny(name, "hasamplitudecontrol")) return handledBoolean(method, false);
        if (containsAny(name, "vibrate", "on")) {
            if(blocked(profile.mode())||!profile.allowVibration())throw new SecurityException("VIRTUAL_VIBRATION_DENIED");
            Object token=token(arguments);
            if(token==null)token=new SessionKey(vibrations.size()+1);
            if(!vibrations.containsKey(token)&&vibrations.size()>=profile.maximumVibrations())throw new IllegalStateException("VIRTUAL_VIBRATION_LIMIT_EXCEEDED");
            long duration=firstPositiveLong(arguments, profile.maximumVibrationDurationMs());
            if(duration>profile.maximumVibrationDurationMs())throw new IllegalArgumentException("VIRTUAL_VIBRATION_DURATION_EXCEEDED");
            vibrations.put(token, System.currentTimeMillis()+duration);
            return Decision.handled(successValue(method.getReturnType()));
        }
        return failUnsupported("vibrator", method);
    }
    private void expireLeases(long now) {
        wakeLocks.entrySet().removeIf(entry -> entry.getValue().expiresAtMs <= now);
        vibrations.entrySet().removeIf(entry -> entry.getValue() <= now);
        autofillSessions.entrySet().removeIf(entry -> entry.getValue() <= now);
        biometricSessions.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
    private static boolean mutation(String name) {
        return startsAny(name, "set", "add", "remove", "clear", "wipe", "lock", "reboot", "install", "uninstall", "create", "provision", "transfer");
    }
    private static boolean host(String mode){
        return VirtualLocationProfileSnapshot.MODE_HOST.equals(mode);
    }
    private static boolean blocked(String mode){
        return VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode);
    }
    private static Decision failUnsupported(String domain, Method method){
        throw new UnsupportedOperationException("VIRTUAL_"+domain.toUpperCase(Locale.ROOT)+"_OPERATION_UNSUPPORTED:"+method.getName());
    }
    private static Decision handledBoolean(Method method, boolean value){
        return Decision.handled(booleanValue(method.getReturnType(), value));
    }
    private static Object booleanValue(Class<?> type, boolean value){
        if(type==boolean.class||type==Boolean.class)return value;
        if(type==int.class||type==Integer.class)return value?1:0;
        if(type==long.class||type==Long.class)return value?1L:0L;
        return value;
    }
    private static Object numeric(Class<?> type, long value){
        if(type==int.class||type==Integer.class)return (int)value;
        if(type==long.class||type==Long.class)return value;
        if(type==short.class||type==Short.class)return (short)value;
        if(type==byte.class||type==Byte.class)return (byte)value;
        return value;
    }
    private static Object emptyValue(Class<?> type){
        if(type==void.class||type==Void.class)return null;
        if(type==boolean.class||type==Boolean.class)return false;
        if(type==int.class||type==Integer.class)return 0;
        if(type==long.class||type==Long.class)return 0L;
        if(type.isArray())return Array.newInstance(type.getComponentType(), 0);
        if(List.class.isAssignableFrom(type)||Iterable.class.isAssignableFrom(type)||type==Object.class)return List.of();
        return null;
    }
    private static Object successValue(Class<?> type){
        if(type==void.class||type==Void.class)return null;
        if(type==boolean.class||type==Boolean.class)return true;
        if(type==int.class||type==Integer.class)return 0;
        if(type==long.class||type==Long.class)return 0L;
        return null;
    }
    private static Object nullValue(Class<?> type){
        return type.isPrimitive()?emptyValue(type):null;
    }
    private static Object stringCollection(Class<?> type, List<String> values){
        if(type.isArray()&&type.getComponentType()==String.class)return values.toArray(new String[0]);
        if(List.class.isAssignableFrom(type)||Iterable.class.isAssignableFrom(type))return List.of();
        if(type==Object.class)return values;
        return emptyValue(type);
    }
    private static Object componentValue(Class<?> type, String flattened){
        if(flattened==null||flattened.isBlank())return nullValue(type);
        if(type==String.class||type==Object.class)return flattened;
        int slash=flattened.indexOf('/');
        if(slash<=0)return nullValue(type);
        try{
            Constructor<?> constructor=type.getDeclaredConstructor(String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(flattened.substring(0, slash), flattened.substring(slash+1));
        } catch(Throwable ignored){
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored);
            return nullValue(type);
        }
    }
    private static Object callback(Object[] arguments){
        if(arguments==null)return null;
        for(Object value:arguments){
            if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean||value.getClass().isEnum()||value.getClass().isArray()||value instanceof Iterable<?>)continue;
            return value;
        }
        return null;
    }
    private static Object lastCallback(Object[] arguments){
        if(arguments==null)return null;
        Object found=null;
        for(Object value:arguments){
            if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean||value.getClass().isEnum()||value.getClass().isArray()||value instanceof Iterable<?>)continue;
            found=value;
        }
        return found;
    }
    private static Object token(Object[] arguments){
        return callback(arguments);
    }
    private static void addBounded(Set<Object> values, Object value, int maximum, String error){
        if(!values.contains(value)&&values.size()>=maximum)throw new IllegalStateException(error);
        values.add(value);
    }
    private static void remove(Set<Object> values, Object[] arguments){
        Object value=callback(arguments);
        if(value!=null)values.remove(value);
    }
    private static int firstInt(Object[] arguments, int fallback){
        if(arguments!=null)for(Object value:arguments)if(value instanceof Integer number)return number;
        return fallback;
    }
    private static long firstPositiveLong(Object[] arguments, long fallback){
        if(arguments!=null)for(Object value:arguments)if(value instanceof Long number&&number>0L)return number;
        return fallback;
    }
    private static String normalize(String value){
        return value==null?"":value.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }
    private static boolean containsAny(String value, String...needles){
        for(String needle:needles)if(value.contains(normalize(needle)))return true;
        return false;
    }
    private static boolean startsAny(String value, String...prefixes){
        for(String prefix:prefixes)if(value.startsWith(normalize(prefix)))return true;
        return false;
    }
    private static boolean isCleanup(String name){
        return startsAny(name, "unregister", "remove", "close", "stop", "cancel", "release", "finish", "destroy");
    }
    private record WakeLockLease(long expiresAtMs) {
    }
    private record SessionKey(int value) {
    }
    record Decision(boolean handled, Object result){
        static Decision passThrough(){
            return new Decision(false, null);
        }
        static Decision handled(Object result){
            return new Decision(true, result);
        }
    }
}
