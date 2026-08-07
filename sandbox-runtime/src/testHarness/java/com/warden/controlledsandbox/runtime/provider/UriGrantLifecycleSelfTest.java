package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;

public final class UriGrantLifecycleSelfTest {
    private UriGrantLifecycleSelfTest() { }

    public static void main(String[] args) {
        oneTimeProviderRoute();
        batchUsesOneTimeGrantOnce();
        persistentGrantRevocationAndSessionCleanup();
        System.out.println("PASS URI grant lifecycle integration self-test");
    }

    private static void oneTimeProviderRoute() {
        BrokerProviderRuntime providers = new BrokerProviderRuntime();
        GuestSession owner = session("owner-session", "com.owner", 0, 1);
        providers.reservePrepare(prepare("owner.data", "com.owner.Provider"), owner);
        UriGrantRegistry grants = new UriGrantRegistry();
        grants.grant("u0:com.owner", owner.sessionId(), owner.generation(),
                "u0:com.target", "target-session", 2, 0, "content://owner.data/items",
                UriGrantRegistry.READ, true, 100, 1_000);

        Bundle query = operation("owner.data", "content://owner.data/items/1");
        UriGrantRegistry.Authorization authorization = grants.beginAuthorization(
                "u0:com.target", "target-session", 2, 0, 110);
        BrokerProviderRuntime.OperationRoute route = providers.routeOperation(query,
                ComponentOperations.PROVIDER_QUERY, "u0:com.target", 0, "u0:com.owner",
                authorization::allows, 110);
        check("URI_GRANT".equals(route.permissionBasis()), "one-time grant did not route Provider operation");
        check(authorization.commit(110).oneTimeConsumed(), "one-time Provider grant not consumed");

        UriGrantRegistry.Authorization replay = grants.beginAuthorization(
                "u0:com.target", "target-session", 2, 0, 111);
        boolean denied = false;
        try {
            providers.routeOperation(query, ComponentOperations.PROVIDER_QUERY,
                    "u0:com.target", 0, "u0:com.owner", replay::allows, 111);
        } catch (SecurityException expected) { denied = true; }
        check(denied, "one-time Provider grant replay accepted");
    }

    private static void batchUsesOneTimeGrantOnce() {
        BrokerProviderRuntime providers = new BrokerProviderRuntime();
        GuestSession owner = session("owner-batch", "com.owner", 0, 1);
        providers.reservePrepare(prepare("owner.batch", "com.owner.BatchProvider"), owner);
        UriGrantRegistry grants = new UriGrantRegistry();
        grants.grant("u0:com.owner", owner.sessionId(), owner.generation(),
                "u0:com.target", "target-batch", 3, 0, "content://owner.batch",
                UriGrantRegistry.READ | UriGrantRegistry.WRITE, true, 200, 1_000);

        Bundle batch = operation("owner.batch", "content://owner.batch");
        batch.putInt(RuntimeKeys.PROVIDER_BATCH_COUNT, 2);
        Bundle first = new Bundle();
        first.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, ProviderBatchRuntime.UPDATE);
        first.putString(RuntimeKeys.URI, "content://owner.batch/a");
        Bundle values = new Bundle();
        values.putString("name", "value");
        first.putBundle(RuntimeKeys.PROVIDER_VALUES, values);
        Bundle second = new Bundle();
        second.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, ProviderBatchRuntime.ASSERT);
        second.putString(RuntimeKeys.URI, "content://owner.batch/b");
        second.putInt(RuntimeKeys.PROVIDER_BATCH_EXPECTED_COUNT, 0);
        batch.putBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + 0, first);
        batch.putBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + 1, second);

        UriGrantRegistry.Authorization authorization = grants.beginAuthorization(
                "u0:com.target", "target-batch", 3, 0, 210);
        BrokerProviderRuntime.OperationRoute route = providers.routeOperation(batch,
                ComponentOperations.PROVIDER_APPLY_BATCH, "u0:com.target", 0, "u0:com.owner",
                authorization::allows, 210);
        check("URI_GRANT".equals(route.permissionBasis()), "batch grant route denied");
        UriGrantRegistry.AuthorizationResult result = authorization.commit(210);
        check(result.oneTimeConsumed() && result.grantIds().size() == 1,
                "one-time batch grant was not consumed exactly once");
    }

    private static void persistentGrantRevocationAndSessionCleanup() {
        UriGrantRegistry grants = new UriGrantRegistry();
        UriGrantRegistry.Grant grant = grants.grant("u4:owner", "owner-session", 5,
                "u4:target", "target-session", 6, 4, "content://owner.private",
                UriGrantRegistry.READ, false, 300, 1_000);
        UriGrantRegistry.Authorization authorization = grants.beginAuthorization(
                "u4:target", "target-session", 6, 4, 310);
        check(authorization.allows("u4:target", "content://owner.private/a", UriGrantRegistry.READ),
                "persistent grant preview failed");
        check(!authorization.commit(310).oneTimeConsumed(), "persistent grant consumed");
        check(grants.revoke(grant.id(), "u4:owner", "owner-session", 5, 320),
                "persistent grant revoke failed");
        grants.grant("u4:owner", "owner-session", 5, "u4:target", "target-session", 6,
                4, "content://owner.cleanup", UriGrantRegistry.WRITE, false, 330, 1_000);
        check(grants.revokeSession("owner-session", 5) == 1, "owner Session cleanup failed");
        check(grants.size(331) == 0, "URI grant leaked after Session cleanup");
    }

    private static GuestSession session(String id, String packageName, int user, long generation) {
        return new GuestSession(id, packageName, user, packageName, 0, generation,
                SessionState.READY, 1, "");
    }

    private static Bundle prepare(String authority, String component) {
        Bundle out = new Bundle();
        out.putString(ComponentOperations.AUTHORITY, authority);
        out.putString(RuntimeKeys.COMPONENT_CLASS, component);
        return out;
    }

    private static Bundle operation(String authority, String uri) {
        Bundle out = new Bundle();
        out.putString(ComponentOperations.AUTHORITY, authority);
        if (uri != null && !uri.isEmpty()) out.putString(RuntimeKeys.URI, uri);
        return out;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
