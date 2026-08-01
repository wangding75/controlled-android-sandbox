package com.warden.controlledsandbox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Authoritative reservation and replacement policy for package system-service clients. */
final class VirtualSystemServiceClientRegistry {
    private final Set<VirtualSystemServiceStore.Client> clients = new LinkedHashSet<>();

    List<VirtualSystemServiceStore.Client> register(VirtualSystemServiceStore.Client client) {
        List<VirtualSystemServiceStore.Client> replaced = removeReplacedBy(client);
        clients.add(client);
        return replaced;
    }

    void reserve(VirtualSystemServiceStore.Client client) {
        if (client == null) throw new IllegalArgumentException("client is required");
        clients.add(client);
    }

    List<VirtualSystemServiceStore.Client> commit(VirtualSystemServiceStore.Client client) {
        if (!clients.contains(client) || !client.active()) {
            throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_CLIENT_RESERVATION_LOST");
        }
        return removeReplacedBy(client);
    }

    void remove(VirtualSystemServiceStore.Client client) { clients.remove(client); }

    List<VirtualSystemServiceStore.Client> snapshot() { return new ArrayList<>(clients); }

    void clear() { clients.clear(); }

    private List<VirtualSystemServiceStore.Client> removeReplacedBy(
            VirtualSystemServiceStore.Client client) {
        List<VirtualSystemServiceStore.Client> replaced = new ArrayList<>();
        clients.removeIf(existing -> {
            boolean replace = existing != client && existing.scope().equals(client.scope())
                    && existing.processName().equals(client.processName())
                    && existing.generation() == client.generation();
            if (replace) replaced.add(existing);
            return replace;
        });
        return replaced;
    }
}
