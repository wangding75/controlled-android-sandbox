package com.warden.controlledsandbox.domain.port;

/** Generates opaque capability and identity tokens without coupling domain code to a concrete RNG. */
@FunctionalInterface
public interface TokenGenerator {
    String nextToken(String purpose);
}
