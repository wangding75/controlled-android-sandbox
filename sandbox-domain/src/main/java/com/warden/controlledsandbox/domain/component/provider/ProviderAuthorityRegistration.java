package com.warden.controlledsandbox.domain.component.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Result of atomically registering one or more Provider authorities. */
public final class ProviderAuthorityRegistration {
    private final List<ProviderAuthorityRegistry.Entry> entries;
    private final Set<String> createdAuthorities;

    ProviderAuthorityRegistration(List<ProviderAuthorityRegistry.Entry> entries,
                                  Set<String> createdAuthorities) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.createdAuthorities = Collections.unmodifiableSet(new LinkedHashSet<>(createdAuthorities));
    }

    public List<ProviderAuthorityRegistry.Entry> entries() { return entries; }
    public Set<String> createdAuthorities() { return createdAuthorities; }
    public boolean createdAny() { return !createdAuthorities.isEmpty(); }
}
