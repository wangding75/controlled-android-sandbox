package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.domain.port.TokenGenerator;
import java.util.UUID;

/** Production opaque-token adapter preserving the existing UUID token format. */
public final class UuidTokenGenerator implements TokenGenerator {
    @Override public String nextToken(String purpose) {
        if (purpose == null || purpose.trim().isEmpty()) {
            throw new IllegalArgumentException("token purpose is required");
        }
        return UUID.randomUUID().toString();
    }
}
