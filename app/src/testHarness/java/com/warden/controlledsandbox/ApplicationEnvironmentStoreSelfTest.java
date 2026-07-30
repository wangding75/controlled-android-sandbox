package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class ApplicationEnvironmentStoreSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("application-environment-store").toFile();
        VirtualSystemServiceStore.Scope alpha = new VirtualSystemServiceStore.Scope("alpha.pkg", 1);
        VirtualSystemServiceStore.Scope beta = new VirtualSystemServiceStore.Scope("beta.pkg", 2);
        ApplicationEnvironmentStore store = new ApplicationEnvironmentStore(root);

        ApplicationEnvironmentProfileSnapshot profile = store.getOrCreate(alpha);
        require(profile.policyVersion() == 1L, "default profile version");
        require(profile.user().userId() == 1, "virtual user id");
        require(store.getOrCreate(beta).user().userId() == 2, "scope isolation");

        VirtualShortcutSnapshot shortcut = new VirtualShortcutSnapshot("compose", ".MainActivity",
                "Compose", "Compose message", "", List.of("intent:#Intent;end"), 0,
                true, true, false, false, true, System.currentTimeMillis(), 0);
        require(store.addDynamicShortcuts(alpha, List.of(shortcut)), "shortcut add");
        store.reportShortcutUsed(alpha, "compose");
        require(store.shortcuts(alpha).get(0).usageCount() == 1, "shortcut usage");
        require(store.shortcuts(beta).isEmpty(), "shortcut scope isolation");

        int widgetId = store.allocateAppWidgetId(alpha, 7);
        require(widgetId >= 11000, "deterministic widget namespace");
        require(store.appWidgets(alpha, 7).size() == 1, "widget allocation");
        require(store.appWidgets(beta, -1).isEmpty(), "widget scope isolation");

        long now = System.currentTimeMillis();
        store.reportUsageEvent(alpha, new VirtualUsageEventSnapshot(now, 1, "alpha.pkg",
                ".MainActivity", "alpha.pkg", "", "compose", 1));
        require(store.usageEvents(alpha, now - 1L, now + 1L, 10).size() == 1, "usage event query");
        boolean crossPackageDenied = false;
        try { store.reportUsageEvent(alpha, new VirtualUsageEventSnapshot(now, 1, "other.pkg",
                "", "", "", "", 0)); }
        catch (SecurityException expected) { crossPackageDenied = true; }
        require(crossPackageDenied, "cross-package usage must be denied");

        store.putSetting(alpha, new VirtualSettingSnapshot("secure", "theme_mode", "dark", now));
        require("dark".equals(store.setting(alpha, "secure", "theme_mode").value()), "settings read");
        boolean blockedSettingDenied = false;
        try { store.putSetting(alpha, new VirtualSettingSnapshot("secure", "adb_enabled", "1", now)); }
        catch (SecurityException expected) { blockedSettingDenied = true; }
        require(blockedSettingDenied, "blocked setting write");

        ApplicationEnvironmentStore reloaded = new ApplicationEnvironmentStore(root);
        require(reloaded.shortcuts(alpha).size() == 1, "shortcut persistence");
        require(reloaded.appWidgets(alpha, -1).size() == 1, "widget persistence");
        require(reloaded.usageEvents(alpha, 0L, Long.MAX_VALUE, 10).size() == 1, "usage persistence");
        require("dark".equals(reloaded.setting(alpha, "secure", "theme_mode").value()), "settings persistence");

        ApplicationEnvironmentProfileSnapshot current = reloaded.getOrCreate(alpha);
        ApplicationEnvironmentProfileSnapshot updated = reloaded.update(alpha, current);
        require(updated.policyVersion() == current.policyVersion() + 1L, "policy version increment");
        boolean conflict = false;
        try { reloaded.update(alpha, current); }
        catch (IllegalStateException expected) { conflict = expected.getMessage().contains("VERSION_CONFLICT"); }
        require(conflict, "optimistic version conflict");

        File storeFile = new File(new File(root, "package-service"),
                "virtual-application-environment-v1.json");
        Files.writeString(storeFile.toPath(), "{broken", StandardCharsets.UTF_8);
        ApplicationEnvironmentStore recovered = new ApplicationEnvironmentStore(root);
        require(!recovered.maintenanceWarning().isBlank(), "corruption warning");
        require(recovered.scopeCount() == 0, "corrupt store must fail closed");
        require(new File(storeFile.getParentFile(), storeFile.getName() + ".corrupt").isFile(),
                "corrupt store quarantine");

        System.out.println("PASS M5-T11 application-environment store self-test");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
