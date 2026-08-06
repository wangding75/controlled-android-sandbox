package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualProviderPathRuleSnapshot;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class BrokerProviderRuntimeSelfTest {
    private BrokerProviderRuntimeSelfTest() { }

    public static void main(String[] args) throws Exception {
        check(RuntimeBrokerService.ownerKey("com.target", 3).equals(
                BrokerProviderRuntime.instanceId("com.target", 3)), "runtime/provider instance key agreement");
        reservationOwnershipAndRollback();
        recoveryAndVirtualUserIsolation();
        concurrentAuthorityReservation();
        transactionRoutingAuthorizationAndAudit();
        operationWhitelistAndCallValidation();
        providerFilePermissionClassification();
        callerSessionIdentityValidation();
        uriGrantOwnerValidation();
        batchPermissionAndValidation();
        declaredProviderPermissionRouting();
        System.out.println("PASS broker Provider authority lifecycle self-test");
    }

    private static void reservationOwnershipAndRollback() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession owner = session("s-owner", "com.example", 0, 1);
        Bundle prepare = prepare("com.example.data;com.example.files", "com.example.DataProvider");
        BrokerProviderRuntime.Reservation reservation = runtime.reservePrepare(prepare, owner);
        check(runtime.size() == 2, "provider authorities not reserved");
        runtime.requireOwned(operation("com.example.data", "content://com.example.data/items"), owner);

        GuestSession wrongSession = session("s-wrong", "com.example", 0, 1);
        boolean denied = false;
        try {
            runtime.requireOwned(operation("com.example.data", "content://com.example.data/items"), wrongSession);
        } catch (SecurityException expected) {
            denied = true;
        }
        check(denied, "wrong Provider session accepted");

        boolean uriMismatch = false;
        try {
            runtime.requireOwned(operation("com.example.data", "content://com.example.files/items"), owner);
        } catch (SecurityException expected) {
            uriMismatch = true;
        }
        check(uriMismatch, "Provider URI authority mismatch accepted");
        runtime.rollbackPrepare(reservation);
        check(runtime.size() == 0, "failed Provider prepare was not rolled back");
        runtime.rollbackPrepare(reservation);
    }

    private static void recoveryAndVirtualUserIsolation() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession first = session("s-u0", "com.example", 0, 1);
        GuestSession otherUser = session("s-u1", "com.example", 1, 1);
        runtime.reservePrepare(prepare("com.example.data", "com.example.DataProvider"), first);
        runtime.reservePrepare(prepare("com.example.data", "com.example.DataProvider"), otherUser);
        check(runtime.size() == 2, "Provider authority crossed virtual-user boundary");

        GuestSession recovered = session("s-u0", "com.example", 0, 2);
        check(runtime.processRecovered(first, recovered) == 1, "Provider generation was not rebound");
        boolean staleDenied = false;
        try {
            runtime.requireOwned(operation("com.example.data", "content://com.example.data/items"), first);
        } catch (SecurityException expected) {
            staleDenied = true;
        }
        check(staleDenied, "stale Provider generation retained authority");
        runtime.requireOwned(operation("com.example.data", "content://com.example.data/items"), recovered);
        runtime.requireOwned(operation("com.example.data", "content://com.example.data/items"), otherUser);
        check(runtime.invalidate(recovered) == 1 && runtime.size() == 1,
                "Provider session cleanup crossed virtual-user boundary");
        check(runtime.invalidateInstance("com.example", 1) == 1 && runtime.size() == 0,
                "Provider instance cleanup leaked authority");
    }

    private static void concurrentAuthorityReservation() throws Exception {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger successes = new AtomicInteger();
        List<BrokerProviderRuntime.Reservation> reservations =
                java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < workers; i++) {
            final int index = i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    GuestSession owner = session("s-" + index, "com.example" + index, 2, 1);
                    BrokerProviderRuntime.Reservation reservation = runtime.reservePrepare(
                            prepare("shared.authority", "com.example.Provider" + index), owner);
                    reservations.add(reservation);
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    // The authority namespace has exactly one owner per virtual user.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        ready.await();
        start.countDown();
        done.await();
        check(successes.get() == 1 && runtime.size() == 1,
                "concurrent Provider authority collision was accepted");
        runtime.rollbackPrepare(reservations.get(0));
        check(runtime.size() == 0, "concurrent Provider reservation cleanup failed");
    }

    private static void transactionRoutingAuthorizationAndAudit() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession owner = session("provider-owner", "com.target", 3, 7);
        Bundle privatePrepare = prepare("private.authority", "com.target.PrivateProvider");
        runtime.reservePrepare(privatePrepare, owner);

        Bundle ownerQuery = operation("private.authority", "content://private.authority/items");
        BrokerProviderRuntime.OperationRoute ownerRoute = runtime.routeOperation(ownerQuery,
                ComponentOperations.PROVIDER_QUERY, BrokerProviderRuntime.instanceId("com.target", 3), 3,
                BrokerProviderRuntime.instanceId("com.target", 3), (caller, uri, flags) -> false, 10);
        check(ownerRoute.entry().sessionId().equals("provider-owner"), "authority did not route to owner session");
        check("OWNER".equals(ownerRoute.permissionBasis()), "owner route lost permission basis");
        Bundle ownerResult = new Bundle();
        ownerResult.putString(RuntimeKeys.STATUS, "PROVIDER_CURSOR_OPEN");
        runtime.completeOperation(ownerRoute, ownerResult, 11);
        check(ownerResult.getString(RuntimeKeys.PROVIDER_AUDIT_ID, "").equals(ownerRoute.auditId()),
                "Provider audit id not returned");
        check(runtime.auditSuccessCount() == 1 && runtime.auditFailureCount() == 0,
                "successful Provider operation not audited");

        boolean denied = false;
        try {
            runtime.routeOperation(ownerQuery, ComponentOperations.PROVIDER_QUERY,
                    BrokerProviderRuntime.instanceId("com.caller", 3), 3,
                    BrokerProviderRuntime.instanceId("com.target", 3),
                    (caller, uri, flags) -> false, 12);
        } catch (SecurityException expected) {
            denied = true;
        }
        check(denied, "private Provider accepted cross-instance caller without grant");
        check(runtime.auditFailureCount() == 1, "denied Provider operation not audited");

        BrokerProviderRuntime.OperationRoute granted = runtime.routeOperation(ownerQuery,
                ComponentOperations.PROVIDER_QUERY, BrokerProviderRuntime.instanceId("com.caller", 3), 3,
                BrokerProviderRuntime.instanceId("com.target", 3),
                (caller, uri, flags) -> uri.equals("content://private.authority/items") && flags == 1, 13);
        check("URI_GRANT".equals(granted.permissionBasis()), "URI grant basis not retained");
        Bundle failed = new Bundle();
        failed.putString(RuntimeKeys.STATUS, "FAILED");
        failed.putString(RuntimeKeys.ERROR_TYPE, "GuestFailure");
        runtime.completeOperation(granted, failed, 14);
        check(runtime.auditFailureCount() == 2, "Guest failure not recorded as failed Provider transaction");

        GuestSession exportedOwner = session("exported-owner", "com.exported", 3, 1);
        Bundle exportedPrepare = prepare("exported.authority", "com.exported.Provider");
        exportedPrepare.putBoolean(RuntimeKeys.PROVIDER_EXPORTED, true);
        runtime.reservePrepare(exportedPrepare, exportedOwner);
        BrokerProviderRuntime.OperationRoute exported = runtime.routeOperation(
                operation("exported.authority", "content://exported.authority/items"),
                ComponentOperations.PROVIDER_GET_TYPE, BrokerProviderRuntime.instanceId("com.caller", 3), 3,
                BrokerProviderRuntime.instanceId("com.exported", 3), (caller, uri, flags) -> false, 15);
        check("EXPORTED".equals(exported.permissionBasis()), "exported Provider was not routed");

        boolean wrongTarget = false;
        try {
            runtime.routeOperation(ownerQuery, ComponentOperations.PROVIDER_QUERY,
                    BrokerProviderRuntime.instanceId("com.target", 3), 3,
                    BrokerProviderRuntime.instanceId("com.wrong", 3), (caller, uri, flags) -> true, 16);
        } catch (SecurityException expected) {
            wrongTarget = true;
        }
        check(wrongTarget, "request target was allowed to disagree with authority owner");

        boolean wrongUser = false;
        try {
            runtime.routeOperation(ownerQuery, ComponentOperations.PROVIDER_QUERY,
                    BrokerProviderRuntime.instanceId("com.target", 4), 4,
                    BrokerProviderRuntime.instanceId("com.target", 4), (caller, uri, flags) -> true, 17);
        } catch (IllegalArgumentException expected) {
            wrongUser = true;
        }
        check(wrongUser, "Provider authority crossed virtual-user namespace");
    }

    private static void operationWhitelistAndCallValidation() {
        check(!ComponentOperations.isProviderOperation("PROVIDER_DROP_DATABASE"),
                "unknown Provider operation entered whitelist");
        boolean unknownRejected = false;
        try {
            ComponentOperations.requireKnownProviderOperation("PROVIDER_DROP_DATABASE");
        } catch (IllegalArgumentException expected) {
            unknownRejected = true;
        }
        check(unknownRejected, "unknown Provider operation not rejected");

        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession owner = session("call-owner", "com.call", 0, 1);
        runtime.reservePrepare(prepare("call.authority", "com.call.Provider"), owner);
        Bundle call = operation("call.authority", "");
        call.putString(RuntimeKeys.PROVIDER_METHOD, "refresh");
        BrokerProviderRuntime.OperationRoute route = runtime.routeOperation(call,
                ComponentOperations.PROVIDER_CALL, BrokerProviderRuntime.instanceId("com.call", 0), 0,
                BrokerProviderRuntime.instanceId("com.call", 0), (caller, uri, flags) -> false, 20);
        check(route.flags() == 3, "Provider call did not require read and write permission");
        check("content://call.authority".equals(route.uri()), "Provider call did not receive canonical URI");

        boolean methodRequired = false;
        try {
            runtime.routeOperation(operation("call.authority", ""), ComponentOperations.PROVIDER_CALL,
                    BrokerProviderRuntime.instanceId("com.call", 0), 0,
                    BrokerProviderRuntime.instanceId("com.call", 0), (caller, uri, flags) -> false, 21);
        } catch (IllegalArgumentException expected) {
            methodRequired = true;
        }
        check(methodRequired, "Provider call accepted missing method");

        for (int i = 0; i < 300; i++) {
            Bundle result = new Bundle();
            result.putString(RuntimeKeys.STATUS, "PROVIDER_CALLED");
            runtime.completeOperation(route, result, 30 + i);
        }
        check(runtime.auditSize() == 256, "Provider audit ledger is not bounded");
    }

    private static void providerFilePermissionClassification() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession owner = session("file-owner", "com.files", 2, 1);
        runtime.reservePrepare(prepare("files.authority", "com.files.Provider"), owner);

        Bundle read = operation("files.authority", "content://files.authority/read");
        read.putString(RuntimeKeys.PROVIDER_FILE_MODE, "r");
        BrokerProviderRuntime.OperationRoute readRoute = runtime.routeOperation(read,
                ComponentOperations.PROVIDER_OPEN_FILE, BrokerProviderRuntime.instanceId("com.files", 2), 2,
                BrokerProviderRuntime.instanceId("com.files", 2), (caller, uri, flags) -> false, 40);
        check(readRoute.flags() == 1, "openFile read mode permission");

        Bundle grantedWrite = operation("files.authority", "content://files.authority/granted");
        grantedWrite.putString(RuntimeKeys.PROVIDER_FILE_MODE, "w");
        BrokerProviderRuntime.OperationRoute grantedRoute = runtime.routeOperation(grantedWrite,
                ComponentOperations.PROVIDER_OPEN_FILE, BrokerProviderRuntime.instanceId("com.caller", 2), 2,
                BrokerProviderRuntime.instanceId("com.files", 2),
                (caller, uri, flags) -> caller.equals(BrokerProviderRuntime.instanceId("com.caller", 2))
                        && flags == 2, 40);
        check(grantedRoute.flags() == 2 && "URI_GRANT".equals(grantedRoute.permissionBasis()),
                "file write URI grant classification");

        Bundle write = operation("files.authority", "content://files.authority/write");
        write.putString(RuntimeKeys.PROVIDER_FILE_MODE, "rwt");
        BrokerProviderRuntime.OperationRoute writeRoute = runtime.routeOperation(write,
                ComponentOperations.PROVIDER_OPEN_ASSET_FILE, BrokerProviderRuntime.instanceId("com.files", 2), 2,
                BrokerProviderRuntime.instanceId("com.files", 2), (caller, uri, flags) -> false, 41);
        check(writeRoute.flags() == 3, "openAssetFile read-write permission");

        Bundle typed = operation("files.authority", "content://files.authority/typed");
        typed.putString(RuntimeKeys.PROVIDER_MIME_TYPE, "text/plain");
        BrokerProviderRuntime.OperationRoute typedRoute = runtime.routeOperation(typed,
                ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE,
                BrokerProviderRuntime.instanceId("com.files", 2), 2,
                BrokerProviderRuntime.instanceId("com.files", 2), (caller, uri, flags) -> false, 42);
        check(typedRoute.flags() == 1, "typed asset read permission");

        boolean invalidMode = false;
        Bundle invalid = operation("files.authority", "content://files.authority/invalid");
        invalid.putString(RuntimeKeys.PROVIDER_FILE_MODE, "rwa");
        try {
            runtime.routeOperation(invalid, ComponentOperations.PROVIDER_OPEN_FILE,
                    BrokerProviderRuntime.instanceId("com.files", 2), 2,
                    BrokerProviderRuntime.instanceId("com.files", 2), (caller, uri, flags) -> false, 43);
        } catch (IllegalArgumentException expected) { invalidMode = true; }
        check(invalidMode, "invalid Provider file mode accepted");

        boolean missingMime = false;
        try {
            runtime.routeOperation(operation("files.authority", "content://files.authority/typed"),
                    ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE,
                    BrokerProviderRuntime.instanceId("com.files", 2), 2,
                    BrokerProviderRuntime.instanceId("com.files", 2), (caller, uri, flags) -> false, 44);
        } catch (IllegalArgumentException expected) { missingMime = true; }
        check(missingMime, "typed asset accepted missing MIME type");
        check(!ComponentOperations.isProviderTransactionOperation(ComponentOperations.PROVIDER_FILE_CLOSE),
                "file close incorrectly entered authority transaction routing");
    }

    private static void callerSessionIdentityValidation() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.CALLER_PACKAGE_NAME, "com.caller");
        request.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, 5);
        request.putString(RuntimeKeys.CALLER_SESSION_ID, "caller-session");
        request.putLong(RuntimeKeys.CALLER_GENERATION, 4);
        GuestSession caller = session("caller-session", "com.caller", 5, 4);
        String instance = runtime.requireCallerInstance(request, "com.target", 5,
                (sessionId, generation) -> sessionId.equals(caller.sessionId())
                        && generation == caller.generation() ? caller : null);
        check(instance.equals(BrokerProviderRuntime.instanceId("com.caller", 5)),
                "cross-instance caller session was not bound to identity");

        boolean spoofDenied = false;
        try {
            runtime.requireCallerInstance(request, "com.target", 5,
                    (sessionId, generation) -> session("caller-session", "com.spoof", 5, 4));
        } catch (SecurityException expected) {
            spoofDenied = true;
        }
        check(spoofDenied, "caller package spoof was accepted");

        boolean staleDenied = false;
        try {
            runtime.requireCallerInstance(request, "com.target", 5, (sessionId, generation) -> null);
        } catch (SecurityException expected) {
            staleDenied = true;
        }
        check(staleDenied, "stale caller session was accepted");

        Bundle owner = new Bundle();
        String ownerInstance = runtime.requireCallerInstance(owner, "com.target", 5,
                (sessionId, generation) -> null);
        check(ownerInstance.equals(BrokerProviderRuntime.instanceId("com.target", 5)),
                "same-instance Provider operation unexpectedly required caller lease");
    }

    private static void uriGrantOwnerValidation() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession owner = session("grant-owner", "com.owner", 6, 1);
        runtime.reservePrepare(prepare("grant.authority", "com.owner.Provider"), owner);
        runtime.requireGrantOwner("content://grant.authority/items", 6,
                BrokerProviderRuntime.instanceId("com.owner", 6));

        boolean foreignDenied = false;
        try {
            runtime.requireGrantOwner("content://grant.authority/items", 6,
                    BrokerProviderRuntime.instanceId("com.foreign", 6));
        } catch (SecurityException expected) {
            foreignDenied = true;
        }
        check(foreignDenied, "foreign App was allowed to issue Provider URI grant");

        boolean crossUserDenied = false;
        try {
            runtime.requireGrantOwner("content://grant.authority/items", 7,
                    BrokerProviderRuntime.instanceId("com.owner", 7));
        } catch (IllegalArgumentException expected) {
            crossUserDenied = true;
        }
        check(crossUserDenied, "URI grant owner crossed virtual-user namespace");
    }


    private static void batchPermissionAndValidation() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession owner = session("batch-owner", "com.batch", 8, 1);
        runtime.reservePrepare(prepare("batch.authority", "com.batch.Provider"), owner);

        Bundle batch = operation("batch.authority", "content://batch.authority");
        batch.putInt(RuntimeKeys.PROVIDER_BATCH_COUNT, 2);
        Bundle read = new Bundle();
        read.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, ProviderBatchRuntime.ASSERT);
        read.putString(RuntimeKeys.URI, "content://batch.authority/read");
        read.putInt(RuntimeKeys.PROVIDER_BATCH_EXPECTED_COUNT, 0);
        Bundle write = new Bundle();
        write.putString(RuntimeKeys.PROVIDER_BATCH_TYPE, ProviderBatchRuntime.DELETE);
        write.putString(RuntimeKeys.URI, "content://batch.authority/write");
        batch.putBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + 0, read);
        batch.putBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + 1, write);

        BrokerProviderRuntime.OperationRoute ownerRoute = runtime.routeOperation(batch,
                ComponentOperations.PROVIDER_APPLY_BATCH, BrokerProviderRuntime.instanceId("com.batch", 8), 8,
                BrokerProviderRuntime.instanceId("com.batch", 8), (caller, uri, flags) -> false, 70);
        check(ownerRoute.flags() == 3, "batch did not combine read and write flags");

        AtomicInteger permissionChecks = new AtomicInteger();
        BrokerProviderRuntime.OperationRoute granted = runtime.routeOperation(batch,
                ComponentOperations.PROVIDER_APPLY_BATCH, BrokerProviderRuntime.instanceId("com.caller", 8), 8,
                BrokerProviderRuntime.instanceId("com.batch", 8), (caller, uri, flags) -> {
                    permissionChecks.incrementAndGet();
                    return (uri.endsWith("/read") && flags == 1) || (uri.endsWith("/write") && flags == 2);
                }, 71);
        check("URI_GRANT".equals(granted.permissionBasis()) && permissionChecks.get() == 2,
                "batch URI grants were not checked per operation");

        boolean partialGrantDenied = false;
        try {
            runtime.routeOperation(batch, ComponentOperations.PROVIDER_APPLY_BATCH,
                    BrokerProviderRuntime.instanceId("com.caller", 8), 8,
                    BrokerProviderRuntime.instanceId("com.batch", 8),
                    (caller, uri, flags) -> uri.endsWith("/read"), 72);
        } catch (SecurityException expected) { partialGrantDenied = true; }
        check(partialGrantDenied, "batch accepted a partial URI grant set");

        Bundle wrongAuthority = new Bundle(batch);
        Bundle bad = new Bundle(write);
        bad.putString(RuntimeKeys.URI, "content://other.authority/items");
        wrongAuthority.putBundle(RuntimeKeys.PROVIDER_BATCH_OPERATION_PREFIX + 1, bad);
        boolean mismatchDenied = false;
        try {
            runtime.routeOperation(wrongAuthority, ComponentOperations.PROVIDER_APPLY_BATCH,
                    BrokerProviderRuntime.instanceId("com.batch", 8), 8,
                    BrokerProviderRuntime.instanceId("com.batch", 8), (caller, uri, flags) -> true, 73);
        } catch (IllegalArgumentException expected) { mismatchDenied = true; }
        check(mismatchDenied, "batch accepted an operation from another authority");
        List<BrokerProviderRuntime.AuditEntry> invalidAudit = runtime.auditSnapshot();
        check(invalidAudit.get(invalidAudit.size() - 1).failureIndex() == 1,
                "batch validation failure index missing from audit");

        Bundle failed = new Bundle();
        failed.putString(RuntimeKeys.STATUS, "FAILED");
        failed.putString(RuntimeKeys.ERROR_TYPE, "OperationApplicationException");
        failed.putInt(RuntimeKeys.PROVIDER_BATCH_FAILURE_INDEX, 1);
        runtime.completeOperation(ownerRoute, failed, 74);
        List<BrokerProviderRuntime.AuditEntry> completedAudit = runtime.auditSnapshot();
        check(completedAudit.get(completedAudit.size() - 1).failureIndex() == 1,
                "Guest batch failure index missing from audit");
    }

    private static Bundle prepare(String authorities, String component) {
        Bundle value = new Bundle();
        value.putString(ComponentOperations.AUTHORITY, authorities);
        value.putString(RuntimeKeys.COMPONENT_CLASS, component);
        value.putBoolean(RuntimeKeys.PROVIDER_EXPORTED, false);
        return value;
    }

    private static Bundle operation(String authority, String uri) {
        Bundle value = new Bundle();
        value.putString(ComponentOperations.AUTHORITY, authority);
        value.putString(RuntimeKeys.URI, uri);
        return value;
    }

    private static void declaredProviderPermissionRouting() {
        BrokerProviderRuntime runtime = new BrokerProviderRuntime();
        GuestSession owner = session("secure-owner", "com.secure", 0, 1);
        Bundle prepare = securePrepare("secure.authority", "com.secure.Provider", true,
                "perm.READ", "perm.WRITE", false, List.of(
                        new VirtualProviderPathRuleSnapshot("", "/private", "",
                                "perm.PRIVATE", "perm.PRIVATE_WRITE", false)));
        runtime.reservePrepare(prepare, owner);
        String caller = BrokerProviderRuntime.instanceId("com.caller", 0);
        String target = BrokerProviderRuntime.instanceId("com.secure", 0);

        BrokerProviderRuntime.OperationRoute read = runtime.routeOperation(
                operation("secure.authority", "content://secure.authority/public"),
                ComponentOperations.PROVIDER_QUERY, caller, 0, target, null,
                permission -> "perm.READ".equals(permission), 1);
        check("DECLARED_PERMISSION".equals(read.permissionBasis()),
                "Provider read permission not enforced as declared permission");

        boolean writeDenied = false;
        try {
            runtime.routeOperation(operation("secure.authority", "content://secure.authority/public"),
                    ComponentOperations.PROVIDER_UPDATE, caller, 0, target, null,
                    permission -> "perm.READ".equals(permission), 2);
        } catch (SecurityException expected) { writeDenied = true; }
        check(writeDenied, "Provider write permission accepted with read-only grant");

        boolean pathDenied = false;
        try {
            runtime.routeOperation(operation("secure.authority", "content://secure.authority/private/item"),
                    ComponentOperations.PROVIDER_QUERY, caller, 0, target, null,
                    permission -> "perm.READ".equals(permission), 3);
        } catch (SecurityException expected) { pathDenied = true; }
        check(pathDenied, "Provider path-specific read permission was ignored");

        BrokerProviderRuntime.OperationRoute pathAllowed = runtime.routeOperation(
                operation("secure.authority", "content://secure.authority/private/item"),
                ComponentOperations.PROVIDER_QUERY, caller, 0, target, null,
                permission -> "perm.PRIVATE".equals(permission), 4);
        check("DECLARED_PERMISSION".equals(pathAllowed.permissionBasis()),
                "Provider path-specific permission was not accepted");

        boolean uriGrantDenied = false;
        try {
            runtime.routeOperation(operation("secure.authority", "content://secure.authority/public"),
                    ComponentOperations.PROVIDER_QUERY, caller, 0, target,
                    (instance, uri, flags) -> true, permission -> false, 5);
        } catch (SecurityException expected) { uriGrantDenied = true; }
        check(uriGrantDenied, "Provider without grantUriPermissions accepted URI grant");

        GuestSession grantOwner = session("grant-owner", "com.grant", 0, 1);
        runtime.reservePrepare(securePrepare("grant.authority", "com.grant.Provider",
                false, "", "", true, List.of()), grantOwner);
        BrokerProviderRuntime.OperationRoute uriGrant = runtime.routeOperation(
                operation("grant.authority", "content://grant.authority/item"),
                ComponentOperations.PROVIDER_QUERY, caller, 0,
                BrokerProviderRuntime.instanceId("com.grant", 0),
                (instance, uri, flags) -> true, permission -> false, 6);
        check("URI_GRANT".equals(uriGrant.permissionBasis()),
                "grantUriPermissions Provider rejected valid URI grant");
    }

    private static Bundle securePrepare(String authority, String component, boolean exported,
                                        String readPermission, String writePermission,
                                        boolean grantUriPermissions,
                                        List<VirtualProviderPathRuleSnapshot> pathRules) {
        String packageName = component.substring(0, component.lastIndexOf('.'));
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        out.putString(ComponentOperations.AUTHORITY, authority);
        out.putString(RuntimeKeys.COMPONENT_CLASS, component);
        VirtualComponentSnapshot provider = new VirtualComponentSnapshot("PROVIDER", component,
                packageName, exported, true, false, authority, "", readPermission, writePermission,
                grantUriPermissions, "DEFAULT", List.of(), List.of(), pathRules);
        String digest = "a".repeat(64);
        out.putParcelable(RuntimeKeys.PACKAGE_STATE, new VirtualPackageStateSnapshot(packageName, 0,
                "Secure", "1", 1L, digest, digest, "", "", true,
                List.of(provider), List.of(), List.of()));
        return out;
    }

    private static GuestSession session(String sessionId, String packageName, int userId, long generation) {
        return new GuestSession(sessionId, packageName, userId, packageName + ":provider", 0,
                generation, SessionState.READY, 0, "");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
