package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualAccountSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded account and token operations separated from the system-service lifecycle authority. */
final class VirtualAccountAuthority {
    List<VirtualAccountSummary> summaries(VirtualSystemServiceRecords.ScopeState state,
            String requestedType) {
        String type = VirtualSystemServiceStore.normalize(requestedType);
        List<VirtualAccountSummary> out = new ArrayList<>();
        for (VirtualSystemServiceRecords.AccountKey key : state.accounts.keySet()) {
            if (!type.isEmpty() && !type.equals(key.type())) continue;
            out.add(new VirtualAccountSummary(key.name(), key.type()));
        }
        out.sort(Comparator.comparing(VirtualAccountSummary::type)
                .thenComparing(VirtualAccountSummary::name));
        return Collections.unmodifiableList(out);
    }

    List<VirtualAccountSnapshot> snapshots(VirtualSystemServiceRecords.ScopeState state,
            String requestedType) {
        String type = VirtualSystemServiceStore.normalize(requestedType);
        List<VirtualAccountSnapshot> out = new ArrayList<>();
        for (Map.Entry<VirtualSystemServiceRecords.AccountKey,
                VirtualSystemServiceRecords.AccountRecord> item : state.accounts.entrySet()) {
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

    boolean add(VirtualSystemServiceRecords.ScopeState state, String name,
            String type, String password) {
        VirtualSystemServiceRecords.AccountKey key = VirtualSystemServiceStore.accountKey(name, type);
        if (state.accounts.containsKey(key)) return false;
        if (state.accounts.size() >= VirtualSystemServiceLimits.MAX_ACCOUNTS_PER_SCOPE) {
            throw new IllegalStateException("VIRTUAL_ACCOUNT_LIMIT_EXCEEDED");
        }
        state.accounts.put(key, new VirtualSystemServiceRecords.AccountRecord(password));
        return true;
    }

    boolean remove(VirtualSystemServiceRecords.ScopeState state, String name, String type) {
        return state.accounts.remove(VirtualSystemServiceStore.accountKey(name, type)) != null;
    }

    void setPassword(VirtualSystemServiceRecords.ScopeState state,
            String name, String type, String password) {
        require(state, name, type).password = VirtualSystemServiceStore.safe(password);
    }

    String password(VirtualSystemServiceRecords.ScopeState state, String name, String type) {
        VirtualSystemServiceRecords.AccountRecord record = state.accounts.get(
                VirtualSystemServiceStore.accountKey(name, type));
        return record == null ? null : record.password;
    }

    void setToken(VirtualSystemServiceRecords.ScopeState state, String name, String type,
            String tokenType, String token) {
        VirtualSystemServiceRecords.AccountRecord record = require(state, name, type);
        String normalizedType = VirtualSystemServiceStore.normalizeRequired(tokenType, "tokenType");
        if (!record.tokens.containsKey(normalizedType)
                && record.tokens.size() >= VirtualSystemServiceLimits.MAX_TOKENS_PER_ACCOUNT) {
            throw new IllegalStateException("VIRTUAL_ACCOUNT_TOKEN_LIMIT_EXCEEDED");
        }
        record.tokens.put(normalizedType, VirtualSystemServiceStore.safe(token));
    }

    String token(VirtualSystemServiceRecords.ScopeState state, String name, String type,
            String tokenType) {
        VirtualSystemServiceRecords.AccountRecord record = state.accounts.get(
                VirtualSystemServiceStore.accountKey(name, type));
        return record == null ? null
                : record.tokens.get(VirtualSystemServiceStore.normalize(tokenType));
    }

    int visibility(VirtualSystemServiceRecords.ScopeState state, String name, String type) {
        VirtualSystemServiceRecords.AccountRecord record = state.accounts.get(
                VirtualSystemServiceStore.accountKey(name, type));
        return record == null ? 0 : record.visibility;
    }

    boolean setVisibility(VirtualSystemServiceRecords.ScopeState state, String name, String type,
            int visibility) {
        if (visibility < 0 || visibility > 3) {
            throw new IllegalArgumentException("VIRTUAL_ACCOUNT_VISIBILITY_INVALID");
        }
        VirtualSystemServiceRecords.AccountRecord record = require(state, name, type);
        boolean changed = record.visibility != visibility;
        record.visibility = visibility;
        return changed;
    }

    boolean invalidateToken(VirtualSystemServiceRecords.ScopeState state,
            String accountType, String token) {
        String normalizedType = VirtualSystemServiceStore.normalize(accountType);
        boolean changed = false;
        for (Map.Entry<VirtualSystemServiceRecords.AccountKey,
                VirtualSystemServiceRecords.AccountRecord> item : state.accounts.entrySet()) {
            if (!normalizedType.isEmpty() && !normalizedType.equals(item.getKey().type())) continue;
            changed |= item.getValue().tokens.values().removeIf(value -> Objects.equals(value, token));
        }
        return changed;
    }

    private static VirtualSystemServiceRecords.AccountRecord require(
            VirtualSystemServiceRecords.ScopeState state, String name, String type) {
        VirtualSystemServiceRecords.AccountRecord record = state.accounts.get(
                VirtualSystemServiceStore.accountKey(name, type));
        if (record == null) throw new IllegalArgumentException("VIRTUAL_ACCOUNT_NOT_FOUND");
        return record;
    }
}
