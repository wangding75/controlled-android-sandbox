package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Durable compatibility profile isolation, versioning and corruption tests. */
public final class VirtualCompatibilityStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = new File("build/compatibility-store-self-test").getCanonicalFile(); delete(root); root.mkdirs();
        VirtualCompatibilityStore store = new VirtualCompatibilityStore(root);
        VirtualSystemServiceStore.Scope a = new VirtualSystemServiceStore.Scope("guest.one", 0);
        VirtualSystemServiceStore.Scope b = new VirtualSystemServiceStore.Scope("guest.one", 1);
        VirtualCompatibilityProfileSnapshot first = store.getOrCreate(a);
        VirtualCompatibilityProfileSnapshot other = store.getOrCreate(b);
        require(!first.googleServices().advertisingId().equals(other.googleServices().advertisingId()), "per-user Google identity isolation");
        VirtualCompatibilityProfileSnapshot requested = new VirtualCompatibilityProfileSnapshot(first.policyVersion(), first.updatedAtMs(),
                new VirtualWebViewProfileSnapshot("STATIC", "com.google.android.webview", "126", "custom", "renderer", false, true, false, 2),
                first.googleServices(), first.oem(), first.detection());
        VirtualCompatibilityProfileSnapshot updated = store.update(a, requested);
        require(updated.policyVersion() == 2L && updated.webView().dataDirectorySuffix().equals("custom"), "optimistic profile update");
        boolean conflict=false; try { store.update(a, requested); } catch(IllegalStateException expected){conflict=expected.getMessage().contains("VERSION_CONFLICT");}
        require(conflict,"stale update rejected");
        VirtualCompatibilityStore reloaded = new VirtualCompatibilityStore(root);
        require(reloaded.getOrCreate(a).webView().providerPackage().equals("com.google.android.webview"), "profile persisted");
        File file=new File(new File(root,"package-service"),"virtual-compatibility-v1.json");
        Files.writeString(file.toPath(),"corrupt"); VirtualCompatibilityStore corrupted=new VirtualCompatibilityStore(root);
        require(!corrupted.maintenanceWarning().isEmpty() && new File(file.getParentFile(),file.getName()+".corrupt").isFile(),"corrupt store quarantined");
        System.out.println("PASS M5-T12 compatibility profile store self-test");
    }
    private static void delete(File f){if(!f.exists())return;if(f.isDirectory())for(File c:f.listFiles())delete(c);f.delete();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
