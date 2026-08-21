package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import com.warden.controlledsandbox.framework.identity.AttributionSourceChain;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;
import java.util.Set;

/** Focused proof that shared service identity/callback semantics are not service-name hooks. */
public final class SystemServiceSemanticSelfTest {
    public static void main(String[] args) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "guest.pkg";
        info.uid = 12001;
        GuestIdentity identity = new GuestIdentity("guest.pkg", 12001, info, Set.of(),
                "host.pkg", 10001);
        SystemServiceSemanticAdapter adapter = new SystemServiceSemanticAdapter(identity, "appops");

        AttributionSourceFixture next = new AttributionSourceFixture("guest.pkg", 12001, null);
        AttributionSourceFixture source = new AttributionSourceFixture("guest.pkg", 12001, next);
        Object[] arguments = {source};
        IdentityObjectRewriter.RewriteScope scope = adapter.rewriteArguments(arguments);
        require("host.pkg".equals(source.packageName) && source.uid == 10001,
                "outbound AttributionSource head must use Host transport identity");
        require("host.pkg".equals(next.packageName) && next.uid == 10001,
                "outbound AttributionSource next chain must use Host identity");
        scope.close();
        require("guest.pkg".equals(source.packageName) && source.uid == 12001,
                "outbound identity rewrite must restore mutable arguments");

        source.packageName = "host.pkg";
        source.uid = 10001;
        next.packageName = "host.pkg";
        next.uid = 10001;
        Object projected = adapter.projectResult(source);
        require(projected == source && "guest.pkg".equals(source.packageName)
                        && source.uid == 12001 && "guest.pkg".equals(next.packageName)
                        && next.uid == 12001,
                "returned AttributionSource chain must project back to Guest identity");

        require(SystemServiceSemanticCatalog.all().size() >= 20,
                "semantic catalog must cover the P4 service domains");
        require(SystemServiceSemanticCatalog.forService("app-ops") != null,
                "catalog must normalize ServiceManager spellings");
        require(!AttributionSourceChain.contains(new Object[]{new UnrelatedRequest(10001)},
                        "host.pkg", 10001),
                "arbitrary nested request integers must not look like Host attribution");
        require(GmsCompatibilityBoundary.isAllowlistedPackage("com.google.android.gms"),
                "GMS package allowlist");

        Callback callback = new Callback();
        require(AccountAuthenticatorBoundary.completeToken(callback, null, "token"),
                "account token callback");
        require("token".equals(callback.result.getString("authtoken")),
                "account token must stay in Guest callback result");
        AccountAuthenticatorBoundary.completeToken(callback, null, "");
        require(callback.errorCode == AccountAuthenticatorBoundary.ERROR_CODE_TOKEN_MISSING,
                "missing account token must be explicit");
        System.out.println("PASS system-service semantic identity/account boundary self-test");
    }

    public static final class AttributionSourceFixture {
        public String packageName;
        public int uid;
        public AttributionSourceFixture next;

        AttributionSourceFixture(String packageName, int uid, AttributionSourceFixture next) {
            this.packageName = packageName;
            this.uid = uid;
            this.next = next;
        }
    }

    public static final class Callback {
        Bundle result;
        int errorCode = -1;

        public void onResult(Bundle result) { this.result = result; }
        public void onError(int code, String message) { errorCode = code; }
    }

    public static final class UnrelatedRequest {
        public final int value;

        UnrelatedRequest(int value) { this.value = value; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
