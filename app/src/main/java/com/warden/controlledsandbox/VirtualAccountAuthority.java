package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded account and token operations separated from the system-service lifecycle authority. */
final class VirtualAccountAuthority {
    List<VirtualAccountSnapshot> snapshots(VirtualSystemServiceStore.ScopeState state,
            String requestedType) {
        String type = VirtualSystemServiceStore.normalize(requestedType);
        List<VirtualAccountSnapshot> out = new ArrayList<>();
        for (Map.Entry<VirtualSystemServiceStore.AccountKey,
                VirtualSystemServiceStore.AccountRecord> item : state.accounts.entrySet()) {
            if (!type.isEmpty() && !type.equals(item.getKey().type())) continue;
            List<String> tokenTypes = new ArrayList<>(item.getValue().tokens.keySet());
            List<String> tokens = new ArrayList<>();
            for (String tokenType : tokenTypes) tokens.add(item.getValue().tokens.get(tokenType));
            out.add(new VirtualAccountSnapshot(item.getKey().name(), item.getKey().type(),
                    item.getValue().password, tokenTypes, tokens));
        }
        out.sort(Comparator.comparing(VirtualAccountSnapshot::type)
                .thenComparing(VirtualAccountSnapshot::name));
        return Collections.unmodifiableList(out);
    }

    boolean add(VirtualSystemServiceStore.ScopeState state, String name,
            String type, String password) {
        VirtualSystemServiceStore.AccountKey key = VirtualSystemServiceStore.accountKey(name, type);
        if (state.accounts.containsKey(key)) return false;
        if (state.accounts.size() >= VirtualSystemServiceStore.MAX_ACCOUNTS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_ACCOUNT_LIMIT_EXCEEDED");
        }
        state.accounts.put(key, new VirtualSystemServiceStore.AccountRecord(password));
        return true;
    }

    boolean remove(VirtualSystemServiceStore.ScopeState state, String name, String type) {
        return state.accounts.remove(VirtualSystemServiceStore.accountKey(name, type)) != null;
    }

    void setPassword(VirtualSystemServiceStore.ScopeState state,
            String name, String type, String password) {
        require(state, name, type).password = VirtualSystemServiceStore.safe(password);
    }

    String password(VirtualSystemServiceStore.ScopeState state, String name, String type) {
        VirtualSystemServiceStore.AccountRecord record = state.accounts.get(
                VirtualSystemServiceStore.accountKey(name, type));
        return record == null ? null : record.password;
    }

    void setToken(VirtualSystemServiceStore.ScopeState state, String name, String type,
            String tokenType, String token) {
        VirtualSystemServiceStore.AccountRecord record = require(state, name, type);
        String normalizedType = VirtualSystemServiceStore.normalizeRequired(tokenType, "tokenType");
        if (!record.tokens.containsKey(normalizedType)
                && record.tokens.size() >= VirtualSystemServiceStore.MAX_TOKENS_PER_ACCOUNT) {
            throw new IllegalStateException("VIRTUAL_ACCOUNT_TOKEN_LIMIT_EXCEEDED");
        }
        record.tokens.put(normalizedType, VirtualSystemServiceStore.safe(token));
    }

    String token(VirtualSystemServiceStore.ScopeState state, String name, String type,
            String tokenType) {
        VirtualSystemServiceStore.AccountRecord record = state.accounts.get(
                VirtualSystemServiceStore.accountKey(name, type));
        return record == null ? null
                : record.tokens.get(VirtualSystemServiceStore.normalize(tokenType));
    }

    boolean invalidateToken(VirtualSystemServiceStore.ScopeState state,
            String accountType, String token) {
        String normalizedType = VirtualSystemServiceStore.normalize(accountType);
        boolean changed = false;
        for (Map.Entry<VirtualSystemServiceStore.AccountKey,
                VirtualSystemServiceStore.AccountRecord> item : state.accounts.entrySet()) {
            if (!normalizedType.isEmpty() && !normalizedType.equals(item.getKey().type())) continue;
            changed |= item.getValue().tokens.values().removeIf(value -> Objects.equals(value, token));
        }
        return changed;
    }

    private static VirtualSystemServiceStore.AccountRecord require(
            VirtualSystemServiceStore.ScopeState state, String name, String type) {
        VirtualSystemServiceStore.AccountRecord record = state.accounts.get(
                VirtualSystemServiceStore.accountKey(name, type));
        if (record == null) throw new IllegalArgumentException("VIRTUAL_ACCOUNT_NOT_FOUND");
        return record;
    }
}
