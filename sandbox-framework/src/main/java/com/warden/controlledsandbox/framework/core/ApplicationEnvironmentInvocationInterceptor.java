package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;

import static com.warden.controlledsandbox.framework.core.ApplicationEnvironmentInvocationValues.*;
import static com.warden.controlledsandbox.framework.core.ApplicationEnvironmentUsageValues.*;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWidgetSnapshot;
import com.warden.controlledsandbox.contract.VirtualLauncherProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import com.warden.controlledsandbox.contract.VirtualSettingsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualShortcutSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageEventSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsageStatsPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualUserProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Source-side User/Launcher/Shortcut/AppWidget/UsageStats/ContentService virtualization. */
final class ApplicationEnvironmentInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final Set<Object> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ApplicationEnvironmentContentObserverRegistry contentObservers;

    ApplicationEnvironmentInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
        this.contentObservers = new ApplicationEnvironmentContentObserverRegistry(identity);
    }

    Decision before(Method method, Object[] arguments) {
        ApplicationEnvironmentProfileSnapshot profile;
        try { profile = identity.virtualServices().applicationEnvironmentProfile(); }
        catch (IllegalStateException unavailable) {
            if ("VIRTUAL_APPLICATION_ENVIRONMENT_PROFILE_AUTHORITY_REQUIRED".equals(unavailable.getMessage())
                    || "VIRTUAL_APPLICATION_ENVIRONMENT_PROFILE_NOT_AVAILABLE".equals(unavailable.getMessage())) {
                return Decision.passThrough();
            }
            throw unavailable;
        }
        return switch (service) {
            case "usermanager" -> user(method, arguments, profile.user());
            case "restrictions" -> restrictions(method, arguments, profile.user());
            case "launcherapps" -> launcher(method, arguments, profile.launcher());
            case "shortcut" -> shortcut(method, arguments, profile.shortcut());
            case "appwidget" -> appWidget(method, arguments, profile.appWidget());
            case "usagestats" -> usageStats(method, arguments, profile.usageStats());
            case "content" -> content(method, arguments, profile.settings());
            default -> Decision.passThrough();
        };
    }

    private Decision user(Method method, Object[] arguments, VirtualUserProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())) {
            if (isCleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            throw new SecurityException("VIRTUAL_USER_MANAGER_BLOCKED:" + method.getName());
        }
        if (containsAny(name, "getuserhandle", "getmyuser", "getcredentialownerprofile")) {
            return Decision.handled(FrameworkApplicationEnvironmentObjectFactory.userHandle(
                    method.getReturnType(), profile.userId()));
        }
        if (containsAny(name, "getserialnumberforuser", "getuserserialnumber")) {
            return Decision.handled(numeric(method.getReturnType(), profile.serialNumber()));
        }
        if (containsAny(name, "getuserforserialnumber")) {
            long serial = firstLong(arguments, -1L);
            return Decision.handled(serial == profile.serialNumber()
                    ? FrameworkApplicationEnvironmentObjectFactory.userHandle(method.getReturnType(), profile.userId())
                    : nullValue(method.getReturnType()));
        }
        if (containsAny(name, "getusername")) return Decision.handled(profile.name());
        if (containsAny(name, "getuserinfo", "getprofileparent")) {
            int requested = firstInt(arguments, profile.userId());
            return Decision.handled(requested == profile.userId()
                    ? FrameworkApplicationEnvironmentObjectFactory.userInfo(method.getReturnType(), profile)
                    : nullValue(method.getReturnType()));
        }
        if (containsAny(name, "getusers", "getprofiles", "getenabledprofiles", "getuserprofiles")) {
            return Decision.handled(FrameworkApplicationEnvironmentObjectFactory.collectionResult(
                    method.getReturnType(), List.of(profile), (type, value) ->
                            type.getSimpleName().toLowerCase(Locale.ROOT).contains("handle")
                                    ? FrameworkApplicationEnvironmentObjectFactory.userHandle(type, profile.userId())
                                    : FrameworkApplicationEnvironmentObjectFactory.userInfo(type, profile)));
        }
        if (containsAny(name, "isuserrunning")) return Decision.handled(profile.running());
        if (containsAny(name, "isuserunlocked", "isunlocked")) return Decision.handled(profile.unlocked());
        if (containsAny(name, "isquietmodeenabled")) return Decision.handled(profile.quietMode());
        if (containsAny(name, "hasuserrestriction")) {
            return Decision.handled(profile.hasRestriction(firstRelevantString(arguments)));
        }
        if (containsAny(name, "getuserrestrictions")) return Decision.handled(restrictionsBundle(profile));
        if (containsAny(name, "getapplicationrestrictions")) {
            return Decision.handled(applicationRestrictionsBundle(profile));
        }
        if (startsAny(name, "createuser", "removeuser", "setuser", "setquietmode",
                "setapplicationrestrictions", "setuserrestriction", "markguest", "evictcredential")) {
            throw new SecurityException("VIRTUAL_USER_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("user", method);
    }

    private Decision restrictions(Method method, Object[] arguments, VirtualUserProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode())) {
            if (isCleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            if (startsAny(name, "get", "has", "is")) {
                return Decision.handled(emptyValue(method.getReturnType()));
            }
            throw new SecurityException("VIRTUAL_RESTRICTIONS_MANAGER_BLOCKED:" + method.getName());
        }
        if (containsAny(name, "getapplicationrestrictions", "getapplicationrestrictionsperadmin")) {
            return Decision.handled(applicationRestrictionsBundle(profile));
        }
        if (containsAny(name, "hasrestrictionsprovider")) return Decision.handled(falseValue(method.getReturnType()));
        if (containsAny(name, "createlocalapprovalintent")) {
            return Decision.handled(nullValue(method.getReturnType()));
        }
        if (startsAny(name, "requestpermission", "notifypermissionresponse", "set", "put", "remove", "clear")) {
            throw new SecurityException("VIRTUAL_RESTRICTIONS_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("restrictions", method);
    }

    private Decision launcher(Method method, Object[] arguments, VirtualLauncherProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode()) || !profile.enabled()) {
            if (isCleanup(name)) { removeListener(arguments); return Decision.handled(successValue(method.getReturnType())); }
            if (name.contains("list") || name.startsWith("get")) return Decision.handled(emptyValue(method.getReturnType()));
            return Decision.handled(falseValue(method.getReturnType()));
        }
        String requestedPackage = packageArgument(arguments);
        if (!requestedPackage.isEmpty() && !profile.visible(requestedPackage)) {
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return Decision.handled(false);
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "ispackageenabled", "isactivityenabled", "ispackagevisible")) {
            return Decision.handled(requestedPackage.isEmpty() || profile.visible(requestedPackage));
        }
        if (containsAny(name, "getactivitylist", "getlauncheractivities", "getshortcutconfigactivities")) {
            return Decision.handled(FrameworkApplicationEnvironmentObjectFactory.launcherActivities(
                    method.getReturnType(), identity));
        }
        if (containsAny(name, "resolveactivity", "getapplicationinfo")) {
            Object activity = FrameworkApplicationEnvironmentObjectFactory.launcherActivity(method.getReturnType(), identity);
            return Decision.handled(activity);
        }
        if (InvocationMethodMatcher.named(name, "unregisterCallback", "removeCallback",
                "unregisterPackageListener")
                || InvocationMethodMatcher.startsWith(name, "unregisterCallback",
                        "removeCallback", "unregisterPackageListener")) {
            removeListener(arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (InvocationMethodMatcher.named(name, "registerCallback", "addCallback",
                "registerPackageListener")
                || InvocationMethodMatcher.startsWith(name, "registerCallback",
                        "addCallback", "registerPackageListener")) {
            if (!profile.allowPackageCallbacks()) return Decision.handled(falseValue(method.getReturnType()));
            Object callback = callback(arguments);
            if (callback == null) throw new IllegalArgumentException("VIRTUAL_LAUNCHER_CALLBACK_REQUIRED");
            if (!listeners.contains(callback) && listeners.size() >= profile.maximumListeners()) {
                throw new IllegalStateException("VIRTUAL_LAUNCHER_LISTENER_LIMIT_EXCEEDED");
            }
            listeners.add(callback);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "startmainactivity", "startappdetailsactivity", "startsessiondetailsactivity")) {
            if (!profile.allowStartMainActivity()) {
                throw new SecurityException("VIRTUAL_LAUNCHER_START_DENIED");
            }
            return Decision.passThrough();
        }
        if (containsAny(name, "getshortcuts", "pinshortcuts", "startshortcut")) return Decision.passThrough();
        return failUnsupported("launcher", method);
    }

    private Decision shortcut(Method method, Object[] arguments, VirtualShortcutPolicySnapshot profile) {
        String name = normalize(method.getName());
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        requireStatic(profile.mode(), "shortcut", name);
        VirtualSystemServiceAuthority authority = identity.virtualServices().authority();
        if (!profile.enabled()) {
            if (startsAny(name, "get", "is", "has")) return Decision.handled(emptyValue(method.getReturnType()));
            return Decision.handled(falseValue(method.getReturnType()));
        }
        if (containsAny(name, "getdynamicshortcuts", "getmanifestshortcuts", "getpinnedshortcuts",
                "getshortcuts", "getsharetargets")) {
            List<VirtualShortcutSnapshot> selected = new ArrayList<>();
            for (VirtualShortcutSnapshot value : authority.shortcuts()) {
                if (name.contains("dynamic") && !value.dynamic()) continue;
                if (name.contains("manifest") && !value.manifest()) continue;
                if (name.contains("pinned") && !value.pinned()) continue;
                selected.add(value);
            }
            return Decision.handled(FrameworkApplicationEnvironmentObjectFactory.collectionResult(
                    method.getReturnType(), selected, (type, value) ->
                            FrameworkApplicationEnvironmentObjectFactory.shortcut(type,
                                    (VirtualShortcutSnapshot) value, identity)));
        }
        if (containsAny(name, "setdynamicshortcuts", "replacedynamicshortcuts")) {
            return Decision.handled(booleanResult(method.getReturnType(),
                    authority.replaceDynamicShortcuts(shortcuts(arguments, true))));
        }
        if (containsAny(name, "adddynamicshortcuts", "pushdynamicshortcut", "updateshortcuts")) {
            return Decision.handled(booleanResult(method.getReturnType(),
                    authority.addDynamicShortcuts(shortcuts(arguments, true))));
        }
        if (containsAny(name, "removealldynamicshortcuts")) {
            List<String> ids = authority.shortcuts().stream().filter(VirtualShortcutSnapshot::dynamic)
                    .map(VirtualShortcutSnapshot::id)
                    .collect(java.util.stream.Collectors.toList());
            authority.removeShortcuts(ids);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "removedynamicshortcuts", "removelonglivedshortcuts")) {
            authority.removeShortcuts(shortcutIds(arguments));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "disableshortcuts")) {
            authority.setShortcutsEnabled(shortcutIds(arguments), false, disabledMessage(arguments));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "enableshortcuts")) {
            authority.setShortcutsEnabled(shortcutIds(arguments), true, "");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "reportshortcutused")) {
            authority.reportShortcutUsed(shortcutId(arguments));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getmaxshortcutcountperactivity")) {
            return Decision.handled(profile.maximumShortcutsPerActivity());
        }
        if (containsAny(name, "getremainingcallcount")) return Decision.handled(profile.remainingCallCount());
        if (containsAny(name, "getratelimitresettime")) return Decision.handled(profile.rateLimitResetTimeMs());
        if (containsAny(name, "isratelimitingactive")) return Decision.handled(profile.remainingCallCount() == 0);
        if (containsAny(name, "requestpinshortcut", "ispinrequestsupported")) {
            return Decision.handled(booleanResult(method.getReturnType(), profile.allowPinRequests()));
        }
        if (containsAny(name, "hasshortcuthostpermission")) return Decision.handled(true);
        if (containsAny(name, "createshortcutresultintent")) return Decision.handled(nullValue(method.getReturnType()));
        return failUnsupported("shortcut", method);
    }

    private Decision appWidget(Method method, Object[] arguments,
            com.warden.controlledsandbox.contract.VirtualWidgetPolicySnapshot profile) {
        String name = normalize(method.getName());
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        requireStatic(profile.mode(), "appWidget", name);
        VirtualSystemServiceAuthority authority = identity.virtualServices().authority();
        if (!profile.enabled()) {
            if (startsAny(name, "get", "is", "has")) return Decision.handled(emptyValue(method.getReturnType()));
            return Decision.handled(falseValue(method.getReturnType()));
        }
        if (containsAny(name, "allocateappwidgetid")) {
            return Decision.handled(authority.allocateAppWidgetId(firstInt(arguments, 0)));
        }
        if (containsAny(name, "deleteappwidgetid")) {
            authority.deleteAppWidgetId(firstInt(arguments, -1));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "deletehost", "deleteallhosts")) {
            int hostId = firstInt(arguments, -1);
            for (VirtualWidgetSnapshot value : authority.appWidgets(hostId)) {
                authority.deleteAppWidgetId(value.appWidgetId());
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getappwidgetidsforhost", "getappwidgetids")) {
            List<VirtualWidgetSnapshot> widgets = authority.appWidgets(firstInt(arguments, -1));
            int[] ids = widgets.stream().mapToInt(VirtualWidgetSnapshot::appWidgetId).toArray();
            return Decision.handled(ids);
        }
        if (containsAny(name, "getappwidgetinfo")) {
            int id = firstInt(arguments, -1);
            VirtualWidgetSnapshot value = authority.appWidgets(-1).stream()
                    .filter(item -> item.appWidgetId() == id).findFirst().orElse(null);
            return Decision.handled(value == null ? nullValue(method.getReturnType())
                    : FrameworkApplicationEnvironmentObjectFactory.appWidgetInfo(method.getReturnType(), value));
        }
        if (containsAny(name, "getinstalledproviders", "getinstalledprovidersforprofile")) {
            if (!profile.exposeInstalledProviders()) return Decision.handled(emptyValue(method.getReturnType()));
            List<VirtualWidgetSnapshot> values = authority.appWidgets(-1).stream()
                    .filter(VirtualWidgetSnapshot::bound)
                    .collect(java.util.stream.Collectors.toList());
            return Decision.handled(FrameworkApplicationEnvironmentObjectFactory.collectionResult(
                    method.getReturnType(), values, (type, value) ->
                            FrameworkApplicationEnvironmentObjectFactory.appWidgetInfo(type,
                                    (VirtualWidgetSnapshot) value)));
        }
        if (containsAny(name, "bindappwidgetid")) {
            int id = firstInt(arguments, -1);
            String[] component = component(arguments);
            return Decision.handled(booleanResult(method.getReturnType(),
                    authority.bindAppWidgetId(id, component[0], component[1])));
        }
        if (containsAny(name, "updateappwidget", "partiallyupdateappwidget", "updateappwidgetoptions")) {
            int id = firstInt(arguments, -1);
            VirtualWidgetSnapshot current = authority.appWidgets(-1).stream()
                    .filter(item -> item.appWidgetId() == id).findFirst()
                    .orElseThrow(() -> new SecurityException("VIRTUAL_APP_WIDGET_NOT_OWNED"));
            authority.updateAppWidget(new VirtualWidgetSnapshot(current.appWidgetId(), current.hostId(),
                    current.providerPackage(), current.providerClass(), current.bound(),
                    current.optionKeys(), current.optionValues(), current.remoteViewsPayload(),
                    System.currentTimeMillis()));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getappwidgetoptions")) return Decision.handled(new Bundle());
        if (containsAny(name, "startlistening")) {
            return Decision.handled(FrameworkApplicationEnvironmentObjectFactory.collectionResult(
                    method.getReturnType(), authority.appWidgets(firstInt(arguments, -1)),
                    (type, value) -> FrameworkApplicationEnvironmentObjectFactory.appWidgetInfo(type,
                            (VirtualWidgetSnapshot) value)));
        }
        if (containsAny(name, "stoplistening", "notifyappwidgetviewdatachanged")) {
            return Decision.handled(successValue(method.getReturnType()));
        }
        return failUnsupported("appWidget", method);
    }

    private Decision usageStats(Method method, Object[] arguments, VirtualUsageStatsPolicySnapshot profile) {
        String name = normalize(method.getName());
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        requireStatic(profile.mode(), "usageStats", name);
        VirtualSystemServiceAuthority authority = identity.virtualServices().authority();
        if (!profile.enabled()) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "reportevent", "reportshortcutusage", "reportchooserselection")) {
            if (!profile.allowReportEvents()) return Decision.handled(successValue(method.getReturnType()));
            authority.reportUsageEvent(usageEvent(arguments, identity));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "queryevents", "queryeventsforself")) {
            long[] range = timeRange(arguments);
            List<VirtualUsageEventSnapshot> values = authority.usageEvents(range[0], range[1], profile.maximumEvents());
            return Decision.handled(FrameworkApplicationEnvironmentObjectFactory.collectionResult(
                    method.getReturnType(), values, (type, value) ->
                            FrameworkApplicationEnvironmentObjectFactory.usageEvent(type,
                                    (VirtualUsageEventSnapshot) value)));
        }
        if (containsAny(name, "queryusagestats", "queryconfigurations", "queryeventstats")) {
            long[] range = timeRange(arguments);
            List<VirtualUsageEventSnapshot> events = authority.usageEvents(range[0], range[1], profile.maximumEvents());
            return Decision.handled(usageSummary(method.getReturnType(), events));
        }
        if (containsAny(name, "getappstandbybucket")) return Decision.handled(10);
        if (containsAny(name, "isappinactive")) return Decision.handled(false);
        if (containsAny(name, "getappstandbybuckets")) return Decision.handled(emptyValue(method.getReturnType()));
        if (startsAny(name, "setappinactive", "setappstandbybucket", "setappstandbybuckets",
                "whitelistapptemporarily", "reportpastusage", "forceusagestats")) {
            throw new SecurityException("VIRTUAL_USAGE_STATS_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("usageStats", method);
    }

    private Decision content(Method method, Object[] arguments, VirtualSettingsProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        requireStatic(profile.mode(), "content", name);
        if (InvocationMethodMatcher.named(name, "unregisterContentObserver")
                || InvocationMethodMatcher.startsWith(name, "unregisterContentObserver")) {
            Object observer = contentObservers.observerArgument(arguments);
            if (observer != null) contentObservers.unregister(observer);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (InvocationMethodMatcher.named(name, "registerContentObserver")
                || InvocationMethodMatcher.startsWith(name, "registerContentObserver")) {
            Object observer = contentObservers.observerArgument(arguments);
            if (observer == null) throw new IllegalArgumentException("VIRTUAL_CONTENT_OBSERVER_REQUIRED");
            if (contentObservers.count() >= 256 && !contentObservers.contains(observer)) {
                throw new IllegalStateException("VIRTUAL_CONTENT_OBSERVER_LIMIT_EXCEEDED");
            }
            contentObservers.register(observer, contentObservers.observerUri(arguments),
                    contentObservers.firstBoolean(arguments, false));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "notifychange")) {
            contentObservers.notifyObservers(arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getsyncadaptertypes", "getcurrentsyncs", "getsyncstatus")) {
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "issyncactive", "issyncpending", "getmastersyncautomatically",
                "getsyncautomatically")) return Decision.handled(false);
        if (startsAny(name, "requestsync", "cancelsync", "setsync", "setmastersync",
                "addperiodicsync", "removeperiodicsync", "putcache", "resettodaystats")) {
            throw new SecurityException("VIRTUAL_CONTENT_SYNC_MUTATION_DENIED:" + method.getName());
        }
        if (containsAny(name, "getcache")) return Decision.handled(nullValue(method.getReturnType()));
        return failUnsupported("content", method);
    }

    private List<VirtualShortcutSnapshot> shortcuts(Object[] arguments, boolean dynamic) {
        List<VirtualShortcutSnapshot> out = new ArrayList<>();
        for (Object value : flatten(arguments)) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) continue;
            out.add(FrameworkApplicationEnvironmentObjectFactory.shortcutSnapshot(value, identity, dynamic));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("VIRTUAL_SHORTCUT_LIST_REQUIRED");
        return List.copyOf(out);
    }


    private void removeListener(Object[] arguments) {
        Object callback = callback(arguments);
        if (callback != null) listeners.remove(callback);
    }
    private List<String> shortcutIds(Object[] arguments) {
        List<String> out = new ArrayList<>();
        for (String value : stringList(arguments)) {
            if (value.equals(identity.packageName()) || value.equals(identity.hostPackageName())) continue;
            if (!out.contains(value)) out.add(value);
        }
        return List.copyOf(out);
    }
    private String shortcutId(Object[] arguments) {
        List<String> values = shortcutIds(arguments);
        return values.isEmpty() ? "" : values.get(0);
    }
    private String packageArgument(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) if (value instanceof String text) {
            if (text.equals(identity.hostPackageName())) return identity.packageName();
            if (text.contains(".")) return text;
        }
        return "";
    }

    private static Decision failUnsupported(String domain, Method method) {
        throw new UnsupportedOperationException("VIRTUAL_" + domain.toUpperCase(Locale.ROOT)
                + "_OPERATION_UNSUPPORTED:" + method.getName());
    }

    record Decision(boolean handled, Object result) {
        static Decision passThrough() { return new Decision(false, null); }
        static Decision handled(Object result) { return new Decision(true, result); }
    }
}
