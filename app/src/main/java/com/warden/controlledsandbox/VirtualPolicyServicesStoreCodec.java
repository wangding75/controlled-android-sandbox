package com.warden.controlledsandbox;
import com.warden.controlledsandbox.contract.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
/** Bounded JSON codec for framework policy-service profiles. */ final class VirtualPolicyServicesStoreCodec {
    static final int SCHEMA=1, MAX_SCOPES=256;
    private VirtualPolicyServicesStoreCodec(){
    }
    static String encode(Map<VirtualSystemServiceStore.Scope, VirtualPolicyServicesProfileSnapshot> profiles){
        try{
            JSONArray scopes=new JSONArray();
            for(Map.Entry<VirtualSystemServiceStore.Scope, VirtualPolicyServicesProfileSnapshot> entry:profiles.entrySet()){
                VirtualPolicyServicesProfileSnapshot v=entry.getValue();
                scopes.put(new JSONObject().put("packageName", entry.getKey().packageName()).put("virtualUserId", entry.getKey().virtualUserId()).put("policyVersion", v.policyVersion()).put("updatedAtMs", v.updatedAtMs()).put("devicePolicy", devicePolicy(v.devicePolicy())).put("accessibility", accessibility(v.accessibility())).put("autofill", autofill(v.autofill())).put("biometric", biometric(v.biometric())).put("sensorPrivacy", privacy(v.sensorPrivacy())).put("power", power(v.power())));
            }
            return new JSONObject().put("schema", SCHEMA).put("scopes", scopes).toString();
        } catch(Exception error){
            throw new IllegalStateException("Cannot encode policy services profiles", error);
        }
    }
    static Map<VirtualSystemServiceStore.Scope, VirtualPolicyServicesProfileSnapshot> decode(String payload){
        try{
            JSONObject root=new JSONObject(payload);
            if(root.getInt("schema")!=SCHEMA)throw new IllegalStateException("POLICY_SERVICES_SCHEMA_UNSUPPORTED");
            JSONArray array=root.getJSONArray("scopes");
            if(array.length()>MAX_SCOPES)throw new IllegalStateException("Policy services scope limit exceeded");
            Map<VirtualSystemServiceStore.Scope, VirtualPolicyServicesProfileSnapshot> out=new LinkedHashMap<>();
            for(int i=0; i<array.length(); i++){
                JSONObject v=array.getJSONObject(i);
                VirtualSystemServiceStore.Scope scope=new VirtualSystemServiceStore.Scope(v.getString("packageName"), v.getInt("virtualUserId"));
                VirtualPolicyServicesProfileSnapshot previous=out.put(scope, new VirtualPolicyServicesProfileSnapshot(v.getLong("policyVersion"), v.optLong("updatedAtMs", 0L), devicePolicy(v.getJSONObject("devicePolicy")), accessibility(v.getJSONObject("accessibility")), autofill(v.getJSONObject("autofill")), biometric(v.getJSONObject("biometric")), privacy(v.getJSONObject("sensorPrivacy")), power(v.getJSONObject("power"))));
                if(previous!=null)throw new IllegalStateException("POLICY_SERVICES_SCOPE_DUPLICATE");
            }
            return out;
        } catch(RuntimeException error){
            throw error;
        } catch(Exception error){
            throw new IllegalStateException("POLICY_SERVICES_STORE_CORRUPT", error);
        }
    }
    private static JSONObject devicePolicy(VirtualDevicePolicyProfileSnapshot v)throws Exception{
        return new JSONObject().put("mode", v.mode()).put("adminActive", v.adminActive()).put("profileOwner", v.profileOwner()).put("deviceOwner", v.deviceOwner()).put("cameraDisabled", v.cameraDisabled()).put("screenCaptureDisabled", v.screenCaptureDisabled()).put("storageEncryptionEnabled", v.storageEncryptionEnabled()).put("passwordQuality", v.passwordQuality()).put("minimumPasswordLength", v.minimumPasswordLength()).put("maximumFailedPasswords", v.maximumFailedPasswords());
    }
    private static VirtualDevicePolicyProfileSnapshot devicePolicy(JSONObject v)throws Exception{
        return new VirtualDevicePolicyProfileSnapshot(v.getString("mode"), v.optBoolean("adminActive", false), v.optBoolean("profileOwner", false), v.optBoolean("deviceOwner", false), v.optBoolean("cameraDisabled", false), v.optBoolean("screenCaptureDisabled", false), v.optBoolean("storageEncryptionEnabled", false), v.optInt("passwordQuality", 0), v.optInt("minimumPasswordLength", 0), v.optInt("maximumFailedPasswords", 0));
    }
    private static JSONObject accessibility(VirtualAccessibilityProfileSnapshot v)throws Exception{
        return new JSONObject().put("mode", v.mode()).put("enabled", v.enabled()).put("touchExplorationEnabled", v.touchExplorationEnabled()).put("highTextContrastEnabled", v.highTextContrastEnabled()).put("allowEventDispatch", v.allowEventDispatch()).put("maximumClients", v.maximumClients()).put("recommendedTimeoutMs", v.recommendedTimeoutMs()).put("enabledServices", new JSONArray(v.enabledServices()));
    }
    private static VirtualAccessibilityProfileSnapshot accessibility(JSONObject v)throws Exception{
        return new VirtualAccessibilityProfileSnapshot(v.getString("mode"), v.optBoolean("enabled", false), v.optBoolean("touchExplorationEnabled", false), v.optBoolean("highTextContrastEnabled", false), v.optBoolean("allowEventDispatch", false), v.optInt("maximumClients", 8), v.optLong("recommendedTimeoutMs", 0L), strings(v.optJSONArray("enabledServices"), 128));
    }
    private static JSONObject autofill(VirtualAutofillProfileSnapshot v)throws Exception{
        return new JSONObject().put("mode", v.mode()).put("enabled", v.enabled()).put("serviceComponent", v.serviceComponent()).put("saveEnabled", v.saveEnabled()).put("augmentedAutofillEnabled", v.augmentedAutofillEnabled()).put("maximumSessions", v.maximumSessions()).put("sessionTimeoutMs", v.sessionTimeoutMs());
    }
    private static VirtualAutofillProfileSnapshot autofill(JSONObject v)throws Exception{
        return new VirtualAutofillProfileSnapshot(v.getString("mode"), v.optBoolean("enabled", false), v.optString("serviceComponent", ""), v.optBoolean("saveEnabled", false), v.optBoolean("augmentedAutofillEnabled", false), v.optInt("maximumSessions", 8), v.optLong("sessionTimeoutMs", 120000L));
    }
    private static JSONObject biometric(VirtualBiometricProfileSnapshot v)throws Exception{
        return new JSONObject().put("mode", v.mode()).put("hardwareDetected", v.hardwareDetected()).put("enrolled", v.enrolled()).put("authenticatorTypes", v.authenticatorTypes()).put("strength", v.strength()).put("allowAuthentication", v.allowAuthentication()).put("deviceCredentialAllowed", v.deviceCredentialAllowed()).put("outcome", v.outcome()).put("maximumSessions", v.maximumSessions()).put("authenticationLatencyMs", v.authenticationLatencyMs());
    }
    private static VirtualBiometricProfileSnapshot biometric(JSONObject v)throws Exception{
        return new VirtualBiometricProfileSnapshot(v.getString("mode"), v.optBoolean("hardwareDetected", false), v.optBoolean("enrolled", false), v.optInt("authenticatorTypes", 0), v.optInt("strength", 0), v.optBoolean("allowAuthentication", false), v.optBoolean("deviceCredentialAllowed", false), v.optString("outcome", VirtualBiometricProfileSnapshot.OUTCOME_FAILURE), v.optInt("maximumSessions", 4), v.optLong("authenticationLatencyMs", 0L));
    }
    private static JSONObject privacy(VirtualSensorPrivacyProfileSnapshot v)throws Exception{
        return new JSONObject().put("mode", v.mode()).put("allSensorsPrivacyEnabled", v.allSensorsPrivacyEnabled()).put("cameraPrivacyEnabled", v.cameraPrivacyEnabled()).put("microphonePrivacyEnabled", v.microphonePrivacyEnabled()).put("allowChanges", v.allowChanges()).put("maximumListeners", v.maximumListeners());
    }
    private static VirtualSensorPrivacyProfileSnapshot privacy(JSONObject v)throws Exception{
        return new VirtualSensorPrivacyProfileSnapshot(v.getString("mode"), v.optBoolean("allSensorsPrivacyEnabled", false), v.optBoolean("cameraPrivacyEnabled", false), v.optBoolean("microphonePrivacyEnabled", false), v.optBoolean("allowChanges", false), v.optInt("maximumListeners", 16));
    }
    private static JSONObject power(VirtualPowerProfileSnapshot v)throws Exception{
        return new JSONObject().put("mode", v.mode()).put("interactive", v.interactive()).put("powerSaveMode", v.powerSaveMode()).put("deviceIdleMode", v.deviceIdleMode()).put("batteryOptimizationsIgnored", v.batteryOptimizationsIgnored()).put("maximumWakeLocks", v.maximumWakeLocks()).put("maximumWakeLockDurationMs", v.maximumWakeLockDurationMs()).put("allowVibration", v.allowVibration()).put("maximumVibrations", v.maximumVibrations()).put("maximumVibrationDurationMs", v.maximumVibrationDurationMs());
    }
    private static VirtualPowerProfileSnapshot power(JSONObject v)throws Exception{
        return new VirtualPowerProfileSnapshot(v.getString("mode"), v.optBoolean("interactive", true), v.optBoolean("powerSaveMode", false), v.optBoolean("deviceIdleMode", false), v.optBoolean("batteryOptimizationsIgnored", false), v.optInt("maximumWakeLocks", 16), v.optLong("maximumWakeLockDurationMs", 300000L), v.optBoolean("allowVibration", true), v.optInt("maximumVibrations", 32), v.optLong("maximumVibrationDurationMs", 5000L));
    }
    private static java.util.List<String> strings(JSONArray a, int max)throws Exception{
        if(a==null)return java.util.List.of();
        if(a.length()>max)throw new IllegalStateException("String-list limit exceeded");
        java.util.ArrayList<String> out=new java.util.ArrayList<>();
        for(int i=0; i<a.length(); i++)out.add(a.getString(i));
        return out;
    }
}
