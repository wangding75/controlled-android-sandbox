package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Typed deterministic SearchManager policy for one guest scope. */
public final class VirtualSearchProfileSnapshot implements Parcelable {
    private final String mode;
    private final boolean globalSearchEnabled;
    private final boolean webSearchEnabled;
    private final String globalSearchComponent;
    private final String webSearchComponent;
    private final List<String> searchableComponents;
    private final List<String> suggestionAuthorities;
    private final int maximumSuggestionResults;

    public VirtualSearchProfileSnapshot(String mode, boolean globalSearchEnabled,
            boolean webSearchEnabled, String globalSearchComponent, String webSearchComponent,
            List<String> searchableComponents, List<String> suggestionAuthorities,
            int maximumSuggestionResults) {
        this.mode = VirtualLocationProfileSnapshot.mode(mode);
        this.globalSearchEnabled = globalSearchEnabled;
        this.webSearchEnabled = webSearchEnabled;
        this.globalSearchComponent = ContractChecks.optionalText(
                globalSearchComponent, "globalSearchComponent", 256);
        this.webSearchComponent = ContractChecks.optionalText(
                webSearchComponent, "webSearchComponent", 256);
        this.searchableComponents = ContractLists.unique(
                searchableComponents, "searchableComponents", 128, 256, false);
        this.suggestionAuthorities = ContractLists.unique(
                suggestionAuthorities, "suggestionAuthorities", 128, 256, false);
        if (maximumSuggestionResults < 0 || maximumSuggestionResults > 1024) {
            throw new IllegalArgumentException("maximumSuggestionResults must be in [0,1024]");
        }
        this.maximumSuggestionResults = maximumSuggestionResults;
    }

    private VirtualSearchProfileSnapshot(Parcel in) {
        this(in.readString(), in.readInt() != 0, in.readInt() != 0,
                in.readString(), in.readString(), in.createStringArrayList(),
                in.createStringArrayList(), in.readInt());
    }

    public String mode() { return mode; }
    public boolean globalSearchEnabled() { return globalSearchEnabled; }
    public boolean webSearchEnabled() { return webSearchEnabled; }
    public String globalSearchComponent() { return globalSearchComponent; }
    public String webSearchComponent() { return webSearchComponent; }
    public List<String> searchableComponents() { return searchableComponents; }
    public List<String> suggestionAuthorities() { return suggestionAuthorities; }
    public int maximumSuggestionResults() { return maximumSuggestionResults; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(mode);
        out.writeInt(globalSearchEnabled ? 1 : 0);
        out.writeInt(webSearchEnabled ? 1 : 0);
        out.writeString(globalSearchComponent);
        out.writeString(webSearchComponent);
        out.writeStringList(searchableComponents);
        out.writeStringList(suggestionAuthorities);
        out.writeInt(maximumSuggestionResults);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualSearchProfileSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualSearchProfileSnapshot createFromParcel(Parcel in) {
            return new VirtualSearchProfileSnapshot(in);
        }
        @Override public VirtualSearchProfileSnapshot[] newArray(int size) {
            return new VirtualSearchProfileSnapshot[size];
        }
    };
}
