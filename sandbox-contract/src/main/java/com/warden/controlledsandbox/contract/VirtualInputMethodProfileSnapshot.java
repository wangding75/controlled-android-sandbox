package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Input/IME policy for one package and virtual user. */
public final class VirtualInputMethodProfileSnapshot implements Parcelable {
    private final String mode;
    private final String selectedInputMethodId;
    private final List<String> enabledInputMethodIds;
    private final boolean allowPicker;
    private final boolean allowInlineSuggestions;
    private final boolean allowFullscreen;
    private final boolean showSoftInputOnFocus;
    private final int maximumSessions;

    public VirtualInputMethodProfileSnapshot(String mode, String selectedInputMethodId,
            List<String> enabledInputMethodIds, boolean allowPicker,
            boolean allowInlineSuggestions, boolean allowFullscreen,
            boolean showSoftInputOnFocus, int maximumSessions) {
        this.mode = normalizeMode(mode);
        this.selectedInputMethodId = ContractChecks.optionalText(
                selectedInputMethodId, "selectedInputMethodId", 256);
        if (enabledInputMethodIds == null || enabledInputMethodIds.size() > 64) {
            throw new IllegalArgumentException("enabledInputMethodIds exceeds limit");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : enabledInputMethodIds) {
            normalized.add(ContractChecks.requiredText(id, "inputMethodId", 256));
        }
        if (!this.selectedInputMethodId.isEmpty() && !normalized.contains(this.selectedInputMethodId)) {
            throw new IllegalArgumentException("selected input method must be enabled");
        }
        this.enabledInputMethodIds = Collections.unmodifiableList(new ArrayList<>(normalized));
        this.allowPicker = allowPicker;
        this.allowInlineSuggestions = allowInlineSuggestions;
        this.allowFullscreen = allowFullscreen;
        this.showSoftInputOnFocus = showSoftInputOnFocus;
        if (maximumSessions < 1 || maximumSessions > 64) {
            throw new IllegalArgumentException("maximumSessions must be in [1,64]");
        }
        this.maximumSessions = maximumSessions;
    }

    private VirtualInputMethodProfileSnapshot(Parcel in) {
        this(in.readString(), in.readString(), readStrings(in), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readInt() != 0, in.readInt());
    }

    public String mode() { return mode; }
    public String selectedInputMethodId() { return selectedInputMethodId; }
    public List<String> enabledInputMethodIds() { return enabledInputMethodIds; }
    public boolean allowPicker() { return allowPicker; }
    public boolean allowInlineSuggestions() { return allowInlineSuggestions; }
    public boolean allowFullscreen() { return allowFullscreen; }
    public boolean showSoftInputOnFocus() { return showSoftInputOnFocus; }
    public int maximumSessions() { return maximumSessions; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode); out.writeString(selectedInputMethodId);
        out.writeStringList(enabledInputMethodIds); out.writeInt(allowPicker ? 1 : 0);
        out.writeInt(allowInlineSuggestions ? 1 : 0); out.writeInt(allowFullscreen ? 1 : 0);
        out.writeInt(showSoftInputOnFocus ? 1 : 0); out.writeInt(maximumSessions);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualInputMethodProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualInputMethodProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualInputMethodProfileSnapshot(in);
        }
        @Override public VirtualInputMethodProfileSnapshot[] newArray(int size) {
            return new VirtualInputMethodProfileSnapshot[size];
        }
    };

    private static List<String> readStrings(Parcel in) {
        ArrayList<String> values = in.createStringArrayList();
        return values == null ? List.of() : values;
    }
    private static String normalizeMode(String value) {
        String normalized = ContractChecks.requiredText(value, "mode", 16).toUpperCase(java.util.Locale.ROOT);
        if (!VirtualWindowPolicySnapshot.MODE_BLOCKED.equals(normalized)
                && !VirtualWindowPolicySnapshot.MODE_STATIC.equals(normalized)
                && !VirtualWindowPolicySnapshot.MODE_HOST.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported input-method mode: " + value);
        }
        return normalized;
    }
}
