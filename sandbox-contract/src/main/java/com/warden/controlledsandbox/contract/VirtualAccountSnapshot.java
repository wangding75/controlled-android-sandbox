package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persisted virtual Account record shared by Guest processes of one virtual user. */
public final class VirtualAccountSnapshot implements Parcelable {
    private final String name;
    private final String type;
    private final String password;
    private final List<String> tokenTypes;
    private final List<String> tokens;

    public VirtualAccountSnapshot(String name, String type, String password,
                                  List<String> tokenTypes, List<String> tokens) {
        this.name = required(name, "name");
        this.type = required(type, "type");
        this.password = password == null ? "" : password;
        this.tokenTypes = immutable(tokenTypes);
        this.tokens = immutable(tokens);
        if (this.tokenTypes.size() != this.tokens.size()) {
            throw new IllegalArgumentException("tokenTypes and tokens must have equal sizes");
        }
    }

    private VirtualAccountSnapshot(Parcel in) {
        this(in.readString(), in.readString(), in.readString(),
                in.createStringArrayList(), in.createStringArrayList());
    }

    public String name() { return name; }
    public String type() { return type; }
    public String password() { return password; }
    public List<String> tokenTypes() { return tokenTypes; }
    public List<String> tokens() { return tokens; }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(name); out.writeString(type); out.writeString(password);
        out.writeStringList(tokenTypes); out.writeStringList(tokens);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<VirtualAccountSnapshot> CREATOR = new Creator<>() {
        @Override public VirtualAccountSnapshot createFromParcel(Parcel in) { return new VirtualAccountSnapshot(in); }
        @Override public VirtualAccountSnapshot[] newArray(int size) { return new VirtualAccountSnapshot[size]; }
    };

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(values == null ? List.of() : new ArrayList<>(values));
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
