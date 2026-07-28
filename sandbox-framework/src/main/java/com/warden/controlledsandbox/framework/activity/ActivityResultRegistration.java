package com.warden.controlledsandbox.framework.activity;

import java.util.Objects;

/** Stable Activity Result API key mapped to a bounded legacy request code. */
public record ActivityResultRegistration(String key, int requestCode) {
    public ActivityResultRegistration {
        key = Objects.requireNonNull(key, "key").trim();
        if (key.isEmpty() || key.length() > 256) {
            throw new IllegalArgumentException("Activity result registration key is invalid");
        }
        if (requestCode < 0 || requestCode > 0xffff) {
            throw new IllegalArgumentException("Activity result requestCode must be 0..65535");
        }
    }
}
