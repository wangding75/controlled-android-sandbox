package com.warden.controlledsandbox.runtime.guest;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;

public final class SandboxSharedPreferencesSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("cspf-test").toFile();
        File file = new File(root, "prefs.cspf");
        SandboxSharedPreferences first = new SandboxSharedPreferences(file);
        require(first.edit().putString("name", "guest").putInt("count", 7)
                .putBoolean("enabled", true).putStringSet("tags", Set.of("a", "b")).commit(), "commit");
        SandboxSharedPreferences second = new SandboxSharedPreferences(file);
        require("guest".equals(second.getString("name", "")), "string");
        require(second.getInt("count", 0) == 7, "int");
        require(second.getBoolean("enabled", false), "boolean");
        require(second.getStringSet("tags", Set.of()).contains("b"), "set");
        require(second.edit().remove("name").commit() && !second.contains("name"), "remove");
        System.out.println("PASS sandbox SharedPreferences self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
