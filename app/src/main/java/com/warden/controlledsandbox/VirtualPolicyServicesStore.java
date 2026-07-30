package com.warden.controlledsandbox;
import com.warden.controlledsandbox.contract.VirtualPolicyServicesProfileSnapshot;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
/** Package-Service-owned durable framework policy-service profiles. */ final class VirtualPolicyServicesStore {
    private final VirtualPolicyServicesStorePersistence persistence;
    private final Map<VirtualSystemServiceStore.Scope, VirtualPolicyServicesProfileSnapshot> profiles = new LinkedHashMap<>();
    private String maintenanceWarning = "";
    VirtualPolicyServicesStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        persistence = new VirtualPolicyServicesStorePersistence(new File(new File(filesDir, "package-service"), "virtual-policy-services-v1.json"));
        load();
    }
    synchronized VirtualPolicyServicesProfileSnapshot getOrCreate(VirtualSystemServiceStore.Scope scope) {
        VirtualPolicyServicesProfileSnapshot value = profiles.get(scope);
        if (value != null) return value;
        value = VirtualPolicyServicesDefaults.create(scope.packageName(), scope.virtualUserId(), 1L, System.currentTimeMillis());
        profiles.put(scope, value);
        persistOrRestore(scope, null);
        return value;
    }
    synchronized VirtualPolicyServicesProfileSnapshot update(VirtualSystemServiceStore.Scope scope, VirtualPolicyServicesProfileSnapshot requested) {
        if (requested == null) throw new IllegalArgumentException("policy services profile is required");
        VirtualPolicyServicesProfileSnapshot current = getOrCreate(scope);
        if (requested.policyVersion() != current.policyVersion()) {
            throw new IllegalStateException("POLICY_SERVICES_PROFILE_VERSION_CONFLICT:expected=" + current.policyVersion() + ":actual=" + requested.policyVersion());
        }
        VirtualPolicyServicesProfileSnapshot updated = requested.withVersion(current.policyVersion()+1L, System.currentTimeMillis());
        profiles.put(scope, updated);
        persistOrRestore(scope, current);
        return updated;
    }
    synchronized VirtualPolicyServicesProfileSnapshot reset(VirtualSystemServiceStore.Scope scope) {
        VirtualPolicyServicesProfileSnapshot current = profiles.get(scope);
        long version = current == null ? 1L : current.policyVersion()+1L;
        VirtualPolicyServicesProfileSnapshot reset = VirtualPolicyServicesDefaults.create(scope.packageName(), scope.virtualUserId(), version, System.currentTimeMillis());
        profiles.put(scope, reset);
        persistOrRestore(scope, current);
        return reset;
    }
    synchronized void deleteScopeBestEffort(VirtualSystemServiceStore.Scope scope) {
        VirtualPolicyServicesProfileSnapshot removed = profiles.remove(scope);
        if (removed == null) return;
        try {
            persist();
        } catch (RuntimeException error) {
            profiles.put(scope, removed);
            maintenanceWarning="POLICY_SERVICES_DELETE_PERSIST_FAILED:"+error.getMessage();
        }
    }
    synchronized String maintenanceWarning(){
        return maintenanceWarning;
    }
    synchronized int scopeCount(){
        return profiles.size();
    }
    private void load(){
        try{
            String payload=persistence.readPayload();
            if(payload!=null)profiles.putAll(VirtualPolicyServicesStoreCodec.decode(payload));
        } catch(RuntimeException error){
            persistence.quarantine();
            maintenanceWarning=error.getMessage()==null?"POLICY_SERVICES_STORE_CORRUPT":error.getMessage();
            profiles.clear();
        }
    }
    private void persistOrRestore(VirtualSystemServiceStore.Scope scope, VirtualPolicyServicesProfileSnapshot previous){
        try{
            persist();
        } catch(RuntimeException error){
            if(previous==null)profiles.remove(scope);
            else profiles.put(scope, previous);
            throw error;
        }
    }
    private void persist(){
        if(profiles.size()>VirtualPolicyServicesStoreCodec.MAX_SCOPES)throw new IllegalStateException("Policy services scope limit exceeded");
        persistence.writePayload(VirtualPolicyServicesStoreCodec.encode(profiles));
    }
}
