package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Typed component metadata supplied by the Binder-owned package authority. */
public final class VirtualComponentSnapshot implements Parcelable {
    private final String type;
    private final String className;
    private final String processName;
    private final boolean exported;
    private final boolean enabled;
    private final boolean isolated;
    private final String authority;
    private final String permission;
    private final String readPermission;
    private final String writePermission;
    private final boolean grantUriPermissions;
    private final String enabledSetting;
    private final int themeResId;
    private final String launchMode;
    private final String taskAffinity;
    private final String documentLaunchMode;
    private final int configChanges;
    private final String screenOrientation;
    private final int windowSoftInputMode;
    private final int flags;
    private final boolean excludeFromRecents;
    private final boolean noHistory;
    private final boolean finishOnTaskLaunch;
    private final boolean clearTaskOnLaunch;
    private final boolean alwaysRetainTaskState;
    private final boolean allowTaskReparenting;
    private final String resizeMode;
    private final float maxAspectRatio;
    private final float minAspectRatio;
    private final boolean supportsPictureInPicture;
    private final ArrayList<String> actions;
    private final ArrayList<VirtualIntentFilterSnapshot> intentFilters;
    private final ArrayList<VirtualProviderPathRuleSnapshot> providerPathRules;

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, List<String> actions) {
        this(type, className, processName, exported, enabled, isolated, authority, permission,
                "DEFAULT", actions, List.of());
    }

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, String enabledSetting,
                                    List<String> actions,
                                    List<VirtualIntentFilterSnapshot> intentFilters) {
        this(type, className, processName, exported, enabled, isolated, authority, permission,
                permission, permission, false, enabledSetting, actions, intentFilters, List.of(), 0);
    }

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, String readPermission,
                                    String writePermission, boolean grantUriPermissions,
                                    String enabledSetting, List<String> actions,
                                    List<VirtualIntentFilterSnapshot> intentFilters,
                                    List<VirtualProviderPathRuleSnapshot> providerPathRules) {
        this(type, className, processName, exported, enabled, isolated, authority, permission,
                readPermission, writePermission, grantUriPermissions, enabledSetting, actions,
                intentFilters, providerPathRules, 0, "standard", "", "none", 0, "", 0, 0,
                false, false, false, false, false, false, "", 0f, 0f, false);
    }

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, String readPermission,
                                    String writePermission, boolean grantUriPermissions,
                                     String enabledSetting, List<String> actions,
                                     List<VirtualIntentFilterSnapshot> intentFilters,
                                     List<VirtualProviderPathRuleSnapshot> providerPathRules,
                                     int themeResId) {
        this(type, className, processName, exported, enabled, isolated, authority, permission,
                readPermission, writePermission, grantUriPermissions, enabledSetting, actions,
                intentFilters, providerPathRules, themeResId, "standard", "", "none", 0, "", 0, 0,
                false, false, false, false, false, false, "", 0f, 0f, false);
    }

    public VirtualComponentSnapshot(String type, String className, String processName,
                                    boolean exported, boolean enabled, boolean isolated,
                                    String authority, String permission, String readPermission,
                                    String writePermission, boolean grantUriPermissions,
                                    String enabledSetting, List<String> actions,
                                    List<VirtualIntentFilterSnapshot> intentFilters,
                                    List<VirtualProviderPathRuleSnapshot> providerPathRules,
                                    int themeResId, String launchMode, String taskAffinity,
                                    String documentLaunchMode, int configChanges,
                                    String screenOrientation, int windowSoftInputMode, int flags,
                                    boolean excludeFromRecents, boolean noHistory,
                                    boolean finishOnTaskLaunch, boolean clearTaskOnLaunch,
                                    boolean alwaysRetainTaskState, boolean allowTaskReparenting,
                                    String resizeMode, float maxAspectRatio, float minAspectRatio,
                                    boolean supportsPictureInPicture) {
        this.type = componentType(type);
        this.className = required(className, "className");
        this.processName = value(processName);
        this.exported = exported;
        this.enabled = enabled;
        this.isolated = isolated;
        this.authority = value(authority);
        this.permission = value(permission);
        this.readPermission = value(readPermission);
        this.writePermission = value(writePermission);
        this.grantUriPermissions = grantUriPermissions;
        this.enabledSetting = enabledSetting(enabledSetting);
        if (themeResId < 0) throw new IllegalArgumentException("themeResId must be non-negative");
        this.themeResId = themeResId;
        this.launchMode = enumValue(launchMode, "standard");
        this.taskAffinity = value(taskAffinity);
        this.documentLaunchMode = enumValue(documentLaunchMode, "none");
        if (configChanges < 0 || windowSoftInputMode < 0 || flags < 0
                || maxAspectRatio < 0f || minAspectRatio < 0f) {
            throw new IllegalArgumentException("activity task contract contains a negative value");
        }
        this.configChanges = configChanges;
        this.screenOrientation = value(screenOrientation);
        this.windowSoftInputMode = windowSoftInputMode;
        this.flags = flags;
        this.excludeFromRecents = excludeFromRecents;
        this.noHistory = noHistory;
        this.finishOnTaskLaunch = finishOnTaskLaunch;
        this.clearTaskOnLaunch = clearTaskOnLaunch;
        this.alwaysRetainTaskState = alwaysRetainTaskState;
        this.allowTaskReparenting = allowTaskReparenting;
        this.resizeMode = value(resizeMode);
        this.maxAspectRatio = maxAspectRatio;
        this.minAspectRatio = minAspectRatio;
        this.supportsPictureInPicture = supportsPictureInPicture;
        this.intentFilters = new ArrayList<>(intentFilters == null ? List.of() : intentFilters);
        if (this.intentFilters.size() > 256) throw new IllegalArgumentException("Too many intent filters");
        this.providerPathRules = new ArrayList<>(providerPathRules == null ? List.of() : providerPathRules);
        if (this.providerPathRules.size() > 256) throw new IllegalArgumentException("Too many Provider path rules");
        if (!"PROVIDER".equals(this.type) && (!this.readPermission.equals(this.permission)
                || !this.writePermission.equals(this.permission) || grantUriPermissions
                || !this.providerPathRules.isEmpty())) {
            throw new IllegalArgumentException("Provider-only metadata on " + this.type);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (actions != null) merged.addAll(actions);
        for (VirtualIntentFilterSnapshot filter : this.intentFilters) merged.addAll(filter.actions());
        this.actions = new ArrayList<>(merged);
    }

    private VirtualComponentSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readString(), in.readString(),
                in.readString(), in.readString(), in.readInt() != 0, in.readString(),
                in.createStringArrayList(), in.createTypedArrayList(VirtualIntentFilterSnapshot.CREATOR),
                in.createTypedArrayList(VirtualProviderPathRuleSnapshot.CREATOR), in.readInt(),
                in.readString(), in.readString(), in.readString(), in.readInt(), in.readString(),
                in.readInt(), in.readInt(), in.readInt() != 0, in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0, in.readInt() != 0,
                in.readString(), Float.intBitsToFloat(in.readInt()),
                Float.intBitsToFloat(in.readInt()), in.readInt() != 0);
    }

    public String type() { return type; }
    public String className() { return className; }
    public String processName() { return processName; }
    public boolean exported() { return exported; }
    public boolean enabled() { return enabled; }
    public boolean isolated() { return isolated; }
    public String authority() { return authority; }
    public String permission() { return permission; }
    public String readPermission() { return readPermission; }
    public String writePermission() { return writePermission; }
    public boolean grantUriPermissions() { return grantUriPermissions; }
    public String enabledSetting() { return enabledSetting; }
    public int themeResId() { return themeResId; }
    public String launchMode() { return launchMode; }
    public String taskAffinity() { return taskAffinity; }
    public String documentLaunchMode() { return documentLaunchMode; }
    public int configChanges() { return configChanges; }
    public String screenOrientation() { return screenOrientation; }
    public int windowSoftInputMode() { return windowSoftInputMode; }
    public int flags() { return flags; }
    public boolean excludeFromRecents() { return excludeFromRecents; }
    public boolean noHistory() { return noHistory; }
    public boolean finishOnTaskLaunch() { return finishOnTaskLaunch; }
    public boolean clearTaskOnLaunch() { return clearTaskOnLaunch; }
    public boolean alwaysRetainTaskState() { return alwaysRetainTaskState; }
    public boolean allowTaskReparenting() { return allowTaskReparenting; }
    public String resizeMode() { return resizeMode; }
    public float maxAspectRatio() { return maxAspectRatio; }
    public float minAspectRatio() { return minAspectRatio; }
    public boolean supportsPictureInPicture() { return supportsPictureInPicture; }
    public List<String> actions() { return Collections.unmodifiableList(actions); }
    public List<VirtualIntentFilterSnapshot> intentFilters() { return Collections.unmodifiableList(intentFilters); }
    public List<VirtualProviderPathRuleSnapshot> providerPathRules() {
        return Collections.unmodifiableList(providerPathRules);
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(type); out.writeString(className); out.writeString(processName);
        out.writeInt(exported ? 1 : 0); out.writeInt(enabled ? 1 : 0); out.writeInt(isolated ? 1 : 0);
        out.writeString(authority); out.writeString(permission); out.writeString(readPermission);
        out.writeString(writePermission); out.writeInt(grantUriPermissions ? 1 : 0);
         out.writeString(enabledSetting); out.writeStringList(actions); out.writeTypedList(intentFilters);
         out.writeTypedList(providerPathRules); out.writeInt(themeResId);
         out.writeString(launchMode); out.writeString(taskAffinity); out.writeString(documentLaunchMode);
         out.writeInt(configChanges); out.writeString(screenOrientation);
         out.writeInt(windowSoftInputMode); out.writeInt(flags);
         out.writeInt(excludeFromRecents ? 1 : 0); out.writeInt(noHistory ? 1 : 0);
         out.writeInt(finishOnTaskLaunch ? 1 : 0); out.writeInt(clearTaskOnLaunch ? 1 : 0);
         out.writeInt(alwaysRetainTaskState ? 1 : 0); out.writeInt(allowTaskReparenting ? 1 : 0);
         out.writeString(resizeMode); out.writeInt(Float.floatToIntBits(maxAspectRatio));
         out.writeInt(Float.floatToIntBits(minAspectRatio));
         out.writeInt(supportsPictureInPicture ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }

    public static final Creator<VirtualComponentSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualComponentSnapshot createFromParcel(Parcel in) { return new VirtualComponentSnapshot(in); }
        @Override public VirtualComponentSnapshot[] newArray(int size) { return new VirtualComponentSnapshot[size]; }
    };

    private static String componentType(String value) {
        String normalized = required(value, "type").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("ACTIVITY", "SERVICE", "RECEIVER", "PROVIDER").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported component type: " + value);
        }
        return normalized;
    }
    private static String enabledSetting(String value) {
        String normalized = value == null ? "DEFAULT" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("DEFAULT", "ENABLED", "DISABLED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported enabled setting: " + value);
        }
        return normalized;
    }
    private static String enumValue(String value, String fallback) {
        String normalized = value(value).toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? fallback : normalized;
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
