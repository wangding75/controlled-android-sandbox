package com.warden.controlledsandbox.domain;

import com.warden.controlledsandbox.domain.component.provider.ProviderAuthorityRegistration;
import com.warden.controlledsandbox.domain.component.activity.ActivityTaskRegistry;
import com.warden.controlledsandbox.domain.component.activity.LaunchDecision;
import com.warden.controlledsandbox.domain.component.activity.LaunchPolicy;
import com.warden.controlledsandbox.domain.component.provider.CursorLeaseRegistry;
import com.warden.controlledsandbox.domain.component.provider.ProviderAuthorityRegistry;
import com.warden.controlledsandbox.domain.component.provider.ProviderObserverRegistry;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import com.warden.controlledsandbox.domain.component.receiver.BroadcastIntent;
import com.warden.controlledsandbox.domain.component.receiver.DynamicReceiverRegistry;
import com.warden.controlledsandbox.domain.component.receiver.ManifestReceiverRegistry;
import com.warden.controlledsandbox.domain.component.receiver.OrderedBroadcastState;
import com.warden.controlledsandbox.domain.component.service.ServiceRuntimeRegistry;
import com.warden.controlledsandbox.domain.identity.VirtualPathPolicy;
import com.warden.controlledsandbox.domain.identity.VirtualUidAllocator;
import com.warden.controlledsandbox.domain.identity.VirtualUidRegistry;
import com.warden.controlledsandbox.domain.packageinfo.PackageUpgradePolicy;
import com.warden.controlledsandbox.domain.packageinfo.SharedLibraryResolver;
import com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.domain.persistence.DurableAtomicFileSelfTest;
import com.warden.controlledsandbox.domain.persistence.PersistentStateException;
import com.warden.controlledsandbox.domain.persistence.RecoverableFileStore;
import com.warden.controlledsandbox.domain.port.TokenGenerator;
import com.warden.controlledsandbox.domain.process.ComponentProcessPlanner;
import com.warden.controlledsandbox.domain.process.SlotPool;
import com.warden.controlledsandbox.domain.routing.RouteTable;
import com.warden.controlledsandbox.domain.routing.RouteTicket;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.PackageRevision;
import com.warden.controlledsandbox.domain.session.SessionRegistry;
import com.warden.controlledsandbox.domain.session.SessionRevisionPolicy;
import com.warden.controlledsandbox.domain.session.SessionState;

public final class SelfTest {
    public static void main(String[] args) throws Exception {
        testManifestParser();
        testQueriesProviderIsNotComponent();
        testTypedStringComponentNames();
        testMissingApplicationComponentNameFailsClosed();
        testSharedLibraryResolution();
        testSlotPool();
        testLaunchPolicy();
        testSessionRegistry();
        testMultiProcessSessionRegistry();
        testRouteTable();
        testVirtualPathPolicy();
        DurableAtomicFileSelfTest.run();
        testRecoverableFileStore();
        testRecoverableFileStoreFatalBoundary();
        testVirtualUidAllocator();
        testPackageUpgradePolicy();
        testActivityTaskRegistry();
        testServiceRuntimeRegistry();
        testDynamicReceiverRegistry();
        testManifestReceiverRegistry();
        testOrderedBroadcastState();
        testProviderAuthorityRegistry();
        testProviderObserverRegistry();
        testUriGrantRegistry();
        testCursorLeaseRegistry();
        testComponentProcessPlanner();
        System.out.println("PASS sandbox-domain self-test");
    }

    private static void testManifestParser() throws Exception {
        BinaryXmlFixtureBuilder f = new BinaryXmlFixtureBuilder();
        byte[] xml = f
                .start("manifest", BinaryXmlFixtureBuilder.text("package", "com.example.guest"),
                        BinaryXmlFixtureBuilder.integer("versionCode", 42),
                        BinaryXmlFixtureBuilder.text("versionName", "1.2.3"),
                        BinaryXmlFixtureBuilder.integer("compileSdkVersion", 35),
                        BinaryXmlFixtureBuilder.text("sharedUserId", "com.example.shared"),
                        BinaryXmlFixtureBuilder.text("installLocation", "auto"),
                        BinaryXmlFixtureBuilder.bool("isolatedSplits", true))
                .start("uses-sdk", BinaryXmlFixtureBuilder.integer("minSdkVersion", 26), BinaryXmlFixtureBuilder.integer("targetSdkVersion", 35)).end("uses-sdk")
                .start("uses-permission", BinaryXmlFixtureBuilder.text("name", "android.permission.INTERNET")).end("uses-permission")
                .start("uses-feature", BinaryXmlFixtureBuilder.text("name", "android.hardware.camera"),
                        BinaryXmlFixtureBuilder.bool("required", false)).end("uses-feature")
                .start("property", BinaryXmlFixtureBuilder.text("name", "android.cts.PROPERTY"),
                        BinaryXmlFixtureBuilder.text("value", "typed")).end("property")
                .start("permission-group", BinaryXmlFixtureBuilder.text("name", "com.example.PERM_GROUP"),
                        BinaryXmlFixtureBuilder.text("label", "Guest permissions"),
                        BinaryXmlFixtureBuilder.integer("priority", 7)).end("permission-group")
                .start("permission", BinaryXmlFixtureBuilder.text("name", "com.example.CUSTOM"),
                        BinaryXmlFixtureBuilder.text("group", "com.example.PERM_GROUP"),
                        BinaryXmlFixtureBuilder.text("label", "Custom"),
                        BinaryXmlFixtureBuilder.integer("protectionLevel", 1)).end("permission")
                .start("permission-tree", BinaryXmlFixtureBuilder.text("name", "com.example.TREE"),
                        BinaryXmlFixtureBuilder.text("label", "Tree"),
                        BinaryXmlFixtureBuilder.integer("protectionLevel", 2)).end("permission-tree")
                .start("uses-library", BinaryXmlFixtureBuilder.text("name", "org.apache.http.legacy"), BinaryXmlFixtureBuilder.bool("required", false)).end("uses-library")
                .start("uses-native-library", BinaryXmlFixtureBuilder.text("name", "libguest_optional.so"), BinaryXmlFixtureBuilder.bool("required", false)).end("uses-native-library")
                .start("uses-sdk-library", BinaryXmlFixtureBuilder.text("name", "com.example.sdk"), BinaryXmlFixtureBuilder.integer("versionMajor", 3), BinaryXmlFixtureBuilder.text("certDigest", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).end("uses-sdk-library")
                .start("instrumentation", BinaryXmlFixtureBuilder.text("name", ".GuestInstrumentation"), BinaryXmlFixtureBuilder.text("targetPackage", "com.example.guest"), BinaryXmlFixtureBuilder.text("targetProcesses", ":remote"), BinaryXmlFixtureBuilder.bool("handleProfiling", true), BinaryXmlFixtureBuilder.bool("functionalTest", true)).end("instrumentation")
                .start("application", BinaryXmlFixtureBuilder.text("name", ".GuestApp"), BinaryXmlFixtureBuilder.text("permission", "com.example.APP_COMPONENT"), BinaryXmlFixtureBuilder.reference("theme", 0x7f120001))
                .start("activity", BinaryXmlFixtureBuilder.text("name", ".MainActivity"), BinaryXmlFixtureBuilder.bool("exported", true), BinaryXmlFixtureBuilder.reference("theme", 0x7f120002))
                .start("intent-filter")
                .start("action", BinaryXmlFixtureBuilder.text("name", "android.intent.action.MAIN")).end("action")
                .start("category", BinaryXmlFixtureBuilder.text("name", "android.intent.category.LAUNCHER")).end("category")
                .end("intent-filter").end("activity")
                .start("activity-alias", BinaryXmlFixtureBuilder.text("name", ".MainActivity"),
                        BinaryXmlFixtureBuilder.text("targetActivity", ".MainActivity"),
                        BinaryXmlFixtureBuilder.bool("exported", true))
                .start("intent-filter")
                .start("action", BinaryXmlFixtureBuilder.text("name", "com.example.ALIAS")).end("action")
                .end("intent-filter").end("activity-alias")
                .start("service", BinaryXmlFixtureBuilder.text("name", ".SyncService"), BinaryXmlFixtureBuilder.text("process", ":remote"), BinaryXmlFixtureBuilder.bool("isolatedProcess", true)).end("service")
                .start("receiver", BinaryXmlFixtureBuilder.text("name", ".BootReceiver"), BinaryXmlFixtureBuilder.text("permission", "com.example.SEND_BOOT"))
                .start("intent-filter", BinaryXmlFixtureBuilder.integer("priority", 250),
                        BinaryXmlFixtureBuilder.integer("order", 3),
                        BinaryXmlFixtureBuilder.bool("autoVerify", true))
                .start("action", BinaryXmlFixtureBuilder.text("name", "com.example.TEST")).end("action")
                .start("category", BinaryXmlFixtureBuilder.text("name", "android.intent.category.DEFAULT")).end("category")
                .start("data", BinaryXmlFixtureBuilder.text("scheme", "content"),
                        BinaryXmlFixtureBuilder.text("host", "example.test")).end("data")
                .start("data", BinaryXmlFixtureBuilder.text("pathPrefix", "/items"),
                        BinaryXmlFixtureBuilder.text("pathSuffix", ".json"),
                        BinaryXmlFixtureBuilder.text("mimeType", "text/*"),
                        BinaryXmlFixtureBuilder.text("mimeGroup", "image")).end("data")
                .end("intent-filter").end("receiver")
                .start("receiver", BinaryXmlFixtureBuilder.text("name", ".InheritedPermissionReceiver"), BinaryXmlFixtureBuilder.bool("exported", false)).end("receiver")
                .start("provider", BinaryXmlFixtureBuilder.text("name", ".DataProvider"),
                        BinaryXmlFixtureBuilder.text("authorities", "com.example.guest.data"),
                        BinaryXmlFixtureBuilder.text("permission", "com.example.PROVIDER"),
                        BinaryXmlFixtureBuilder.text("readPermission", "com.example.READ"),
                        BinaryXmlFixtureBuilder.text("writePermission", "com.example.WRITE"),
                        BinaryXmlFixtureBuilder.bool("grantUriPermissions", true))
                .start("path-permission", BinaryXmlFixtureBuilder.text("pathPrefix", "/private"),
                        BinaryXmlFixtureBuilder.text("readPermission", "com.example.PRIVATE_READ"),
                        BinaryXmlFixtureBuilder.text("writePermission", "com.example.PRIVATE_WRITE")).end("path-permission")
                .start("grant-uri-permission", BinaryXmlFixtureBuilder.text("pathPattern", "/shared/.*")).end("grant-uri-permission")
                .end("provider")
                .end("application").end("manifest").build();
        ManifestModel model = new BinaryXmlManifestParser().parse(xml);
        require("com.example.guest".equals(model.packageName()), "package");
        require("com.example.guest.GuestApp".equals(model.applicationClass()), "application class");
        require(model.applicationThemeResId() == 0x7f120001, "application theme resource");
        require(model.minSdk() == 26 && model.targetSdk() == 35, "sdk values");
        require(model.versionCode() == 42, "manifest versionCode");
        require("1.2.3".equals(model.versionName()), "manifest versionName");
        require(model.compileSdk() == 35, "manifest compileSdkVersion");
        require("com.example.shared".equals(model.sharedUserId()), "sharedUserId");
        require("auto".equals(model.installLocation()), "installLocation");
        require(model.isolatedSplits(), "isolatedSplits");
        require(model.usesFeatures().size() == 1
                        && "android.hardware.camera".equals(model.usesFeatures().get(0).name())
                        && !model.usesFeatures().get(0).required(),
                "uses-feature typed model");
        require(model.properties().size() == 1
                        && "android.cts.PROPERTY".equals(model.properties().get(0).name()),
                "manifest property typed model");
        require("com.example.guest.MainActivity".equals(model.launcherActivity()), "launcher activity");
        require(model.activities().size() == 1
                        && model.activities().get(0).actions().contains("com.example.ALIAS"),
                "same-name activity alias is merged without losing filters");
        require(model.activities().get(0).themeResId() == 0x7f120002, "activity theme resource");
        require(model.isolatedProcessCount() == 1, "isolated process");
        require(model.permissions().contains("android.permission.INTERNET"), "permission");
        require(model.permissionGroups().size() == 1
                        && "com.example.PERM_GROUP".equals(model.permissionGroups().get(0).name())
                        && model.permissionGroups().get(0).priority() == 7,
                "permission-group declaration");
        require(model.permissionDeclarations().size() == 2
                        && "com.example.CUSTOM".equals(model.permissionDeclarations().get(0).name())
                        && "com.example.PERM_GROUP".equals(model.permissionDeclarations().get(0).group())
                        && model.permissionDeclarations().get(0).protectionLevel() == 1,
                "custom permission declaration");
        require("com.example.TREE".equals(model.permissionDeclarations().get(1).name())
                        && model.permissionDeclarations().get(1).tree()
                        && model.permissionDeclarations().get(1).group().isEmpty(),
                "permission-tree declaration");
        require(model.sharedLibraryDependencies().size() == 3, "typed shared-library declarations");
        require(!model.sharedLibraryDependencies().get(0).required(), "optional Java shared library");
        require(model.sharedLibraryDependencies().get(1).kind() == ManifestModel.SharedLibraryDependency.Kind.NATIVE,
                "native shared library kind");
        require(model.sharedLibraryDependencies().get(2).version() == 3L
                        && model.sharedLibraryDependencies().get(2).certificateDigest().length() == 64,
                "SDK shared library version and certificate");
        require(model.instrumentations().size() == 1
                        && "com.example.guest.GuestInstrumentation".equals(model.instrumentations().get(0).className())
                        && model.instrumentations().get(0).handleProfiling()
                        && model.instrumentations().get(0).functionalTest(),
                "instrumentation declaration");
        require(model.receivers().get(0).actions().contains("com.example.TEST"), "receiver action");
        require(model.receivers().get(0).intentFilters().size() == 1, "receiver intent filter");
        ManifestModel.IntentFilter receiverFilter = model.receivers().get(0).intentFilters().get(0);
        require(receiverFilter.priority() == 250, "receiver priority");
        require(receiverFilter.order() == 3 && receiverFilter.autoVerify(),
                "intent-filter order and autoVerify");
        require(new ManifestModel.Component("com.example.Clamped", "", true, true,
                        false, "", "").addIntentFilter(Integer.MAX_VALUE).priority() == 1000,
                "manifest priority is clamped at the Android upper bound");
        require(receiverFilter.categories().contains("android.intent.category.DEFAULT"), "receiver category");
        require(receiverFilter.dataRules().size() == 2
                        && "content".equals(receiverFilter.dataRules().get(0).scheme())
                        && "example.test".equals(receiverFilter.dataRules().get(0).host())
                        && "/items".equals(receiverFilter.dataRules().get(1).pathPrefix())
                        && ".json".equals(receiverFilter.dataRules().get(1).pathSuffix())
                        && "text/*".equals(receiverFilter.dataRules().get(1).mimeType())
                        && "image".equals(receiverFilter.dataRules().get(1).mimeGroup()),
                "receiver data filter aggregation");
        require("com.example.SEND_BOOT".equals(model.receivers().get(0).permission()), "receiver permission");
        require(model.receivers().get(0).exported(), "legacy intent-filter exported default");
        require(!model.receivers().get(1).exported(), "explicit receiver exported=false");
        ManifestModel.Component provider = model.providers().get(0);
        require("com.example.READ".equals(provider.readPermission())
                        && "com.example.WRITE".equals(provider.writePermission())
                        && provider.grantUriPermissions(),
                "Provider read/write/grant permissions");
        require(provider.providerPathRules().size() == 2
                        && "/private".equals(provider.providerPathRules().get(0).pathPrefix())
                        && "com.example.PRIVATE_READ".equals(
                                provider.providerPathRules().get(0).readPermission())
                        && provider.providerPathRules().get(1).uriGrantRule()
                        && "/shared/.*".equals(provider.providerPathRules().get(1).pathPattern()),
                "Provider path and URI-grant rules");
        require("com.example.APP_COMPONENT".equals(model.receivers().get(1).permission()),
                "application permission inheritance");
        require("com.example.guest.data".equals(model.providers().get(0).authorities()), "provider authority");

        ManifestModel duplicateModel = new ManifestModel();
        ManifestModel.Component first = new ManifestModel.Component(
                "com.example.guest.DuplicateService", "", false, true, false, "", "");
        first.addAction("com.example.DUPLICATE");
        first.addIntentFilter(123).addAction("com.example.DUPLICATE");
        ManifestModel.Component duplicate = new ManifestModel.Component(
                "com.example.guest.DuplicateService", "", false, true, false, "", "");
        duplicate.addAction("com.example.DUPLICATE");
        duplicate.addIntentFilter(123).addAction("com.example.DUPLICATE");
        duplicateModel.addService(first);
        duplicateModel.addService(duplicate);
        require(duplicateModel.services().size() == 1
                        && duplicateModel.services().get(0).actions().size() == 1
                        && duplicateModel.services().get(0).intentFilters().size() == 1,
                "identical duplicate component declarations merge deterministically");
    }

    private static void testQueriesProviderIsNotComponent() throws Exception {
        byte[] xml = new BinaryXmlFixtureBuilder()
                .start("manifest", BinaryXmlFixtureBuilder.text("package", "com.example.guest"))
                .start("queries")
                .start("package", BinaryXmlFixtureBuilder.text("name", "com.oem.calendar")).end("package")
                .start("provider", BinaryXmlFixtureBuilder.text("authorities", "com.oem.privacy.provider")).end("provider")
                .start("provider", BinaryXmlFixtureBuilder.text("authorities", "com.oem.pay.SampleProvider")).end("provider")
                .start("intent")
                .start("action", BinaryXmlFixtureBuilder.text("name", "com.oem.action.SYNC")).end("action")
                .start("category", BinaryXmlFixtureBuilder.text("name", "com.oem.category.DEFAULT")).end("category")
                .start("data", BinaryXmlFixtureBuilder.text("scheme", "oem")).end("data")
                .end("intent")
                .end("queries")
                .start("application")
                .start("provider", BinaryXmlFixtureBuilder.text("name", ".DataProvider"),
                        BinaryXmlFixtureBuilder.text("authorities", "com.example.guest.data")).end("provider")
                .start("activity", BinaryXmlFixtureBuilder.text("name", ".MainActivity")).end("activity")
                .end("application").end("manifest").build();
        ManifestModel model = new BinaryXmlManifestParser().parse(xml);
        require(model.providers().size() == 1, "queries providers are not application components");
        require("com.example.guest.DataProvider".equals(model.providers().get(0).className()),
                "application provider name is preserved");
        require("com.example.guest.data".equals(model.providers().get(0).authorities()),
                "application provider authority is preserved");
        require(model.activities().size() == 1, "application activity is preserved");
        require(model.queryPackages().contains("com.oem.calendar"),
                "queries package declaration is preserved");
        require(model.queryProviderAuthorities().contains("com.oem.privacy.provider")
                        && model.queryProviderAuthorities().contains("com.oem.pay.SampleProvider"),
                "queries provider authorities are preserved");
        require(model.queryIntents().size() == 1
                        && model.queryIntents().get(0).actions().contains("com.oem.action.SYNC")
                        && model.queryIntents().get(0).dataRules().size() == 1,
                "queries intent declaration is preserved");
    }

    private static void testTypedStringComponentNames() throws Exception {
        byte[] xml = new BinaryXmlFixtureBuilder()
                .start("manifest", BinaryXmlFixtureBuilder.text("package", "com.example.guest"))
                .start("application", BinaryXmlFixtureBuilder.text("process", ":global"))
                .start("activity", BinaryXmlFixtureBuilder.text("name", ".TypedActivity")).end("activity")
                .start("activity-alias", BinaryXmlFixtureBuilder.text("name", ".TypedAlias"),
                        BinaryXmlFixtureBuilder.text("targetActivity", ".TypedActivity")).end("activity-alias")
                .start("service", BinaryXmlFixtureBuilder.text("name", "com.example.guest.TypedService")).end("service")
                .start("receiver", BinaryXmlFixtureBuilder.text("name", ".TypedReceiver")).end("receiver")
                .start("provider", BinaryXmlFixtureBuilder.text("name", ".TypedProvider"),
                        BinaryXmlFixtureBuilder.text("authorities", "com.example.guest.typed")).end("provider")
                .end("application").end("manifest").build();
        ManifestModel model = new BinaryXmlManifestParser().parse(xml);
        require(model.activities().size() == 2, "P02/P05 activity and alias keep distinct names");
        require("com.example.guest.TypedActivity".equals(model.activities().get(0).className()), "P02 activity typed name");
        require("com.example.guest.TypedAlias".equals(model.activities().get(1).className()), "P05 alias typed name");
        require("com.example.guest.TypedService".equals(model.services().get(0).className()), "P03/P07 service FQCN");
        require("com.example.guest.TypedReceiver".equals(model.receivers().get(0).className()), "P04 receiver relative");
        require("com.example.guest.TypedProvider".equals(model.providers().get(0).className()), "P01/P06 provider relative");
    }

    private static void testMissingApplicationComponentNameFailsClosed() throws Exception {
        byte[] xml = new BinaryXmlFixtureBuilder()
                .start("manifest", BinaryXmlFixtureBuilder.text("package", "com.example.guest"))
                .start("application")
                .start("provider", BinaryXmlFixtureBuilder.text("authorities", "com.example.guest.orphan")).end("provider")
                .end("application").end("manifest").build();
        try {
            new BinaryXmlManifestParser().parse(xml);
            throw new AssertionError("P08 missing application provider name must fail closed");
        } catch (IllegalArgumentException error) {
            require(error.getMessage() != null && error.getMessage().startsWith("MISSING_COMPONENT_NAME:provider"),
                    "P08 missing name uses MISSING_COMPONENT_NAME");
        }
    }

    private static void testSharedLibraryResolution() {
        String digest = "b".repeat(64);
        SharedLibraryResolver resolver = new SharedLibraryResolver(java.util.List.of(
                new SharedLibraryResolver.AvailableLibrary(
                        ManifestModel.SharedLibraryDependency.Kind.JAVA,
                        "org.apache.http.legacy", 0, "", "android"),
                new SharedLibraryResolver.AvailableLibrary(
                        ManifestModel.SharedLibraryDependency.Kind.SDK,
                        "com.example.sdk", 3, digest, "com.example.provider")));
        SharedLibraryResolver.Resolution success = resolver.resolve(java.util.List.of(
                new ManifestModel.SharedLibraryDependency(
                        ManifestModel.SharedLibraryDependency.Kind.JAVA,
                        "org.apache.http.legacy", true, 0, ""),
                new ManifestModel.SharedLibraryDependency(
                        ManifestModel.SharedLibraryDependency.Kind.NATIVE,
                        "liboptional.so", false, 0, ""),
                new ManifestModel.SharedLibraryDependency(
                        ManifestModel.SharedLibraryDependency.Kind.SDK,
                        "com.example.sdk", true, 3, digest)));
        require(success.successful(), "required shared libraries resolve");
        require(success.resolved().size() == 2 && success.missingOptional().size() == 1,
                "optional shared library is recorded without blocking");

        SharedLibraryResolver.Resolution failure = resolver.resolve(java.util.List.of(
                new ManifestModel.SharedLibraryDependency(
                        ManifestModel.SharedLibraryDependency.Kind.SDK,
                        "com.example.sdk", true, 4, digest)));
        require(!failure.successful() && failure.missingRequired().size() == 1
                        && failure.errors().get(0).contains("version mismatch"),
                "required shared library version mismatch fails closed");
    }

    private static void testSlotPool() {
        SlotPool pool = new SlotPool(2);
        int a = pool.reserve("a.package", 0);
        int aAgain = pool.reserve("a.package", 0);
        int b = pool.reserve("b.package", 0);
        require(a == aAgain, "stable slot");
        require(a != b, "distinct slot");
        require(pool.reserve("c.package", 0) == -1, "full pool");
        pool.release("a.package", 0);
        require(pool.reserve("c.package", 0) >= 0, "reused slot");
    }

    private static void testLaunchPolicy() throws Exception {
        BinaryXmlFixtureBuilder f = new BinaryXmlFixtureBuilder();
        ManifestModel model = new BinaryXmlManifestParser().parse(f
                .start("manifest", BinaryXmlFixtureBuilder.text("package", "p"))
                .start("uses-sdk", BinaryXmlFixtureBuilder.integer("minSdkVersion", 26)).end("uses-sdk")
                .start("application")
                .start("activity", BinaryXmlFixtureBuilder.text("name", ".A"))
                .start("intent-filter")
                .start("action", BinaryXmlFixtureBuilder.text("name", "android.intent.action.MAIN")).end("action")
                .start("category", BinaryXmlFixtureBuilder.text("name", "android.intent.category.LAUNCHER")).end("category")
                .end("intent-filter").end("activity").end("application").end("manifest").build());
        LaunchDecision decision = new LaunchPolicy(30).evaluate(model, 0, true);
        require(decision.status() == LaunchDecision.Status.READY_FOR_PROBE, "launch decision");
    }


    private static void testSessionRegistry() {
        SessionRegistry registry = new SessionRegistry(2, testTokens());
        GuestSession session = registry.allocate("com.example.guest", 0, 1);
        require(session.state() == SessionState.ALLOCATED, "session allocated");
        require(session.sessionId().startsWith("session-test-"), "injected session token");
        require(registry.count() == 1 && registry.used() == 1 && registry.capacity() == 2, "session metrics port");
        session = registry.transition(session.packageName(), 0, session.generation(), SessionState.PREPARING, 2, "");
        session = registry.transition(session.packageName(), 0, session.generation(), SessionState.READY, 3, "");
        session = registry.transition(session.packageName(), 0, session.generation(), SessionState.ACTIVE, 4, "");
        session = registry.markProcessDied(session.packageName(), 0, session.generation(), 5, "test death");
        require(session.state() == SessionState.RECOVERING, "session recovering");
        session = registry.beginRecovery(session.packageName(), 0, session.generation(), 6);
        require(session.generation() == 2 && session.state() == SessionState.PREPARING, "session generation");
        boolean stale = false;
        try { registry.transition(session.packageName(), 0, 1, SessionState.READY, 7, ""); }
        catch (IllegalStateException expected) { stale = expected.getMessage().startsWith("STALE_GENERATION"); }
        require(stale, "stale generation rejected");

        String firstRevision = PackageRevision.of(1L, "a".repeat(64)).canonical();
        String secondRevision = PackageRevision.of(2L, "b".repeat(64)).canonical();
        SessionRegistry revisionRegistry = new SessionRegistry(1, testTokens());
        GuestSession revisionBound = revisionRegistry.allocate(
                "com.example.revision", 0, "com.example.revision", firstRevision, 1);
        require(firstRevision.equals(revisionBound.packageRevision()), "session retains package revision");
        require(revisionRegistry.allocate("com.example.revision", 0,
                "com.example.revision", firstRevision, 2) == revisionBound,
                "same revision reuses active session");
        boolean revisionMismatch = false;
        try {
            revisionRegistry.allocate("com.example.revision", 0,
                    "com.example.revision", secondRevision, 3);
        } catch (IllegalStateException expected) {
            revisionMismatch = expected.getMessage().startsWith("SESSION_REVISION_MISMATCH");
        }
        require(revisionMismatch, "different APK revision cannot reuse active session");
        require(SessionRevisionPolicy.mismatchedLiveSessions(
                java.util.List.of(revisionBound), secondRevision).size() == 1,
                "revision policy selects live mismatched session");
        require(SessionRevisionPolicy.mismatchedLiveSessions(
                java.util.List.of(revisionBound), firstRevision).isEmpty(),
                "revision policy retains matching live session");

        SessionRegistry disconnectRegistry = new SessionRegistry(1, testTokens());
        GuestSession disconnected = disconnectRegistry.allocate("com.example.disconnect", 0, 1);
        disconnected = disconnectRegistry.transition(disconnected.packageName(), 0, disconnected.generation(), SessionState.PREPARING, 2, "");
        disconnected = disconnectRegistry.markSlotDisconnected(disconnected.processSlot(), 3, "binder died");
        require(disconnected.state() == SessionState.FAILED, "prepare disconnect failed");

        GuestSession recoverable = disconnectRegistry.allocate("com.example.disconnect", 0, 4);
        recoverable = disconnectRegistry.transition(recoverable.packageName(), 0, recoverable.generation(), SessionState.PREPARING, 5, "");
        recoverable = disconnectRegistry.transition(recoverable.packageName(), 0, recoverable.generation(), SessionState.READY, 6, "");
        recoverable = disconnectRegistry.markSlotDisconnected(recoverable.processSlot(), 7, "binder died");
        require(recoverable.state() == SessionState.RECOVERING, "ready disconnect recovering");
        require(disconnectRegistry.findByProcessSlot(recoverable.processSlot()) != null, "find session by slot");

        boolean emptyTokenRejected = false;
        try { new SessionRegistry(1, purpose -> "").allocate("com.example.empty", 0, 1); }
        catch (IllegalStateException expected) { emptyTokenRejected = true; }
        require(emptyTokenRejected, "empty session token rejected");

        SessionRegistry collisionRegistry = new SessionRegistry(2, purpose -> "duplicate-session");
        collisionRegistry.allocate("com.example.one", 0, 1);
        boolean collisionRejected = false;
        try { collisionRegistry.allocate("com.example.two", 0, 2); }
        catch (IllegalStateException expected) {
            collisionRejected = "TOKEN_GENERATOR_SESSION_ID_COLLISION".equals(expected.getMessage());
        }
        require(collisionRejected, "duplicate session token rejected");

        SessionRegistry boundedHistory = new SessionRegistry(1, testTokens());
        for (int index = 0; index < 100; index++) {
            GuestSession terminal = boundedHistory.allocate("com.example.history" + index, 0, index + 1L);
            boundedHistory.markSlotDisconnected(terminal.processSlot(), index + 2L, "preparation failed");
        }
        require(boundedHistory.count() <= 64,
                "terminal Session history is pruned before it can grow without bound");
    }

    private static void testMultiProcessSessionRegistry() {
        SessionRegistry registry = new SessionRegistry(4, testTokens());
        GuestSession main = registry.allocate("com.example.guest", 0, "com.example.guest", 1);
        GuestSession remote = registry.allocate("com.example.guest", 0, "com.example.guest:remote", 2);
        require(main.processSlot() != remote.processSlot(), "declared processes use distinct slots");
        require(registry.getAll("com.example.guest", 0).size() == 2, "instance process inventory");
        remote = registry.transition(remote.packageName(), remote.virtualUserId(), remote.processName(),
                remote.generation(), SessionState.PREPARING, 3, "");
        remote = registry.transition(remote.packageName(), remote.virtualUserId(), remote.processName(),
                remote.generation(), SessionState.READY, 4, "");
        require("com.example.guest:remote".equals(registry.findByProcessSlot(remote.processSlot()).processName()),
                "slot resolves declared process");
    }

    private static void testRouteTable() {
        GuestSession ready = new GuestSession("session", "com.example.guest", 0, 1, 4,
                SessionState.READY, 1, "");
        RouteTable routes = new RouteTable();
        RouteTicket ticket = routes.issue(ready, "com.example.guest.MainActivity", 10, 5000);
        RouteTicket consumed = routes.consume(ticket.token(), "session", 4, 11);
        require(consumed.componentClass().endsWith("MainActivity"), "route component");
        boolean replayed = false;
        try { routes.consume(ticket.token(), "session", 4, 12); }
        catch (IllegalStateException expected) { replayed = true; }
        require(replayed, "route replay rejected");
        RouteTicket expired = routes.issue(ready, "com.example.guest.MainActivity", 20, 1);
        boolean expiry = false;
        try { routes.consume(expired.token(), "session", 4, 22); }
        catch (IllegalStateException expected) { expiry = "ROUTE_EXPIRED".equals(expected.getMessage()); }
        require(expiry, "route expiry");
        RouteTicket revoked = routes.issue(ready, "com.example.guest.MainActivity", 30, 5000);
        require(routes.revokeSession(revoked.sessionId(), revoked.generation()) == 1, "route session revocation");
        boolean revokedRejected = false;
        try { routes.consume(revoked.token(), revoked.sessionId(), revoked.generation(), 31); }
        catch (IllegalStateException expected) { revokedRejected = true; }
        require(revokedRejected, "revoked route rejected");
    }

    private static void testVirtualPathPolicy() {
        VirtualPathPolicy policy = new VirtualPathPolicy("/sandbox", "com.example.guest", 3);
        require(policy.mapGuestPath("/data/data/com.example.guest/files/a.txt")
                .equals(policy.dataDir().resolve("files/a.txt")), "data path mapping");
        require(policy.webViewDir().toString().replace('\\', '/').contains("users/3/apps/com.example.guest/webview"), "webview path");
        boolean traversal = false;
        try { policy.mapGuestPath("/data/data/com.example.guest/../../escape"); }
        catch (SecurityException expected) { traversal = true; }
        require(traversal, "path traversal rejected");
    }


    private static void testRecoverableFileStore() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("recoverable-store-test");
        java.nio.file.Path primary = directory.resolve("state.txt");
        try {
            RecoverableFileStore store = new RecoverableFileStore(primary);
            require("empty".equals(store.read(value -> value, "empty")), "recoverable store empty");
            store.write("trusted-state");
            require("trusted-state".equals(store.read(value -> value, "empty")), "recoverable store primary");
            java.nio.file.Files.writeString(primary, "corrupt");
            String recovered = store.read(value -> {
                if (!value.startsWith("trusted")) throw new IllegalArgumentException("invalid");
                return value;
            }, "empty");
            require("trusted-state".equals(recovered), "recoverable store backup recovery");
            require("trusted-state".equals(java.nio.file.Files.readString(primary)), "recoverable store primary repair");
            java.nio.file.Files.writeString(primary, "bad-primary");
            java.nio.file.Files.writeString(store.backup(), "bad-backup");
            boolean blocked = false;
            try {
                store.read(value -> {
                    if (!value.startsWith("trusted")) throw new IllegalArgumentException("invalid");
                    return value;
                }, "empty");
            } catch (PersistentStateException expected) { blocked = true; }
            require(blocked, "recoverable store fails closed");

            java.nio.file.Path firstWriteDirectory = directory.resolve("first-write-target");
            java.nio.file.Files.createDirectory(firstWriteDirectory);
            RecoverableFileStore firstWriteFailure = new RecoverableFileStore(firstWriteDirectory);
            boolean firstWriteBlocked = false;
            try { firstWriteFailure.write("must-not-survive"); }
            catch (java.io.IOException expected) { firstWriteBlocked = true; }
            require(firstWriteBlocked, "recoverable store reports first primary failure");
            require(!java.nio.file.Files.exists(firstWriteFailure.backup()),
                    "failed first write removes uncommitted backup");

            java.nio.file.Path rollbackPrimary = directory.resolve("rollback-state.txt");
            RecoverableFileStore rollbackStore = new RecoverableFileStore(rollbackPrimary);
            rollbackStore.write("old-state");
            java.nio.file.Files.delete(rollbackPrimary);
            java.nio.file.Files.createDirectory(rollbackPrimary);
            boolean updateBlocked = false;
            try { rollbackStore.write("new-state"); }
            catch (java.io.IOException expected) { updateBlocked = true; }
            require(updateBlocked, "recoverable store reports update primary failure");
            require("old-state".equals(java.nio.file.Files.readString(rollbackStore.backup())),
                    "failed update restores previous backup");

            java.nio.file.Path boundedPrimary = directory.resolve("bounded-state.txt");
            RecoverableFileStore boundedStore = new RecoverableFileStore(boundedPrimary, 8);
            boundedStore.write("12345678");
            boolean oversizedWriteBlocked = false;
            try { boundedStore.write("123456789"); }
            catch (java.io.IOException expected) { oversizedWriteBlocked = true; }
            require(oversizedWriteBlocked, "recoverable store bounds writes");
            require("12345678".equals(boundedStore.read(value -> value, "empty")),
                    "oversized write preserves durable state");
            java.nio.file.Files.writeString(boundedPrimary, "123456789");
            java.nio.file.Files.writeString(boundedStore.backup(), "123456789");
            boolean oversizedReadBlocked = false;
            try { boundedStore.read(value -> value, "empty"); }
            catch (PersistentStateException expected) { oversizedReadBlocked = true; }
            require(oversizedReadBlocked, "recoverable store bounds primary and backup reads");
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); }
                    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                });
            }
        }
    }

    private static void testVirtualUidAllocator() throws Exception {
        VirtualUidAllocator allocator = new VirtualUidAllocator();
        int user0 = allocator.compose(10_123, 0);
        int user2 = allocator.compose(10_123, 2);
        require(allocator.appId(user0) == allocator.appId(user2), "stable app id");
        require(allocator.userId(user2) == 2, "virtual uid user");

        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("virtual-uid-test");
        java.nio.file.Path registryFile = directory.resolve("uids.registry");
        try {
            VirtualUidRegistry registry = new VirtualUidRegistry(registryFile);
            java.util.List<String> packages = new java.util.ArrayList<>(90_000);
            for (int index = 0; index < 90_000; index++) packages.add("com.example.bulk" + index);
            java.util.Map<String, Integer> assigned = registry.assignAll(packages);
            require(assigned.size() == 90_000, "virtual uid full range assignment");
            require(new java.util.HashSet<>(assigned.values()).size() == 90_000,
                    "virtual uid collision-free full range");
            int first = registry.uidFor("com.example.bulk0", 3);
            require(allocator.userId(first) == 3, "virtual uid registry user composition");
            VirtualUidRegistry reloaded = new VirtualUidRegistry(registryFile);
            require(reloaded.uidFor("com.example.bulk0", 3) == first, "virtual uid persisted stability");
            boolean exhausted = false;
            try { reloaded.appIdFor("com.example.exhausted"); }
            catch (IllegalStateException expected) { exhausted = true; }
            require(exhausted, "virtual uid range exhaustion fails closed");
            java.nio.file.Files.writeString(registryFile, "corrupt");
            VirtualUidRegistry recovered = new VirtualUidRegistry(registryFile);
            require(recovered.uidFor("com.example.bulk0", 3) == first, "virtual uid backup recovery");
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); }
                    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                });
            }
        }
    }

    private static void testPackageUpgradePolicy() {
        PackageUpgradePolicy policy = new PackageUpgradePolicy();
        policy.validate(2, "signer-a", 3, "signer-a");
        boolean downgrade = false;
        try { policy.validate(3, "signer-a", 2, "signer-a"); }
        catch (SecurityException expected) { downgrade = expected.getMessage().contains("downgrade"); }
        require(downgrade, "package downgrade rejected");
        boolean signer = false;
        try { policy.validate(3, "signer-a", 4, "signer-b"); }
        catch (SecurityException expected) { signer = expected.getMessage().contains("signing"); }
        require(signer, "package signer change rejected");
        policy.validate(0, "", 1, "signer-a");
    }


    private static void testActivityTaskRegistry() {
        ActivityTaskRegistry registry = new ActivityTaskRegistry();
        ActivityTaskRegistry.Snapshot main = registry.launch("instance-a", "pkg.MainActivity", null, 1);
        main = registry.transition(main.token(), 1, ActivityTaskRegistry.State.CREATED);
        main = registry.transition(main.token(), 1, ActivityTaskRegistry.State.STARTED);
        main = registry.transition(main.token(), 1, ActivityTaskRegistry.State.RESUMED);
        ActivityTaskRegistry.Snapshot detail = registry.launch("instance-a", "pkg.DetailActivity", main.taskId(), 1);
        require(registry.task("instance-a", main.taskId()).size() == 2, "activity task stack");
        detail = registry.transition(detail.token(), 1, ActivityTaskRegistry.State.DESTROYED);
        require(registry.top("instance-a", main.taskId()).component().equals("pkg.MainActivity"), "activity top restored");
        boolean invalid = false;
        try { registry.transition(main.token(), 1, ActivityTaskRegistry.State.CREATED); }
        catch (IllegalStateException expected) { invalid = true; }
        require(invalid, "invalid activity transition rejected");
    }

    private static void testServiceRuntimeRegistry() {
        ServiceRuntimeRegistry registry = new ServiceRuntimeRegistry();
        ServiceRuntimeRegistry.Snapshot service = registry.start("instance-a", "pkg.SyncService", "pkg:remote",
                ServiceRuntimeRegistry.RestartMode.REDELIVER_INTENT, 1, "ACTION_SYNC", true);
        require(service.started() && service.lastStartId() == 1 && service.foreground(), "started foreground service");
        service = registry.start("instance-a", "pkg.SyncService", "pkg:remote",
                ServiceRuntimeRegistry.RestartMode.REDELIVER_INTENT, 1, "ACTION_SYNC_2", false);
        require(service.lastStartId() == 2 && "ACTION_SYNC_2".equals(service.lastStartAction()), "latest start metadata");
        service = registry.stopStartId("instance-a", "pkg.SyncService", 1, 1);
        require(service.started(), "stale start id must not stop service");
        service = registry.stopStartId("instance-a", "pkg.SyncService", 99, 1);
        require(service.started(), "future start id must not stop service");
        service = registry.bind("instance-a", "pkg.SyncService", "pkg:remote", "connection-1", 1);
        require(service.bound(), "bound service");
        service = registry.unbind("instance-a", "pkg.SyncService", "connection-1", 1);
        require(service.started() && !service.bound(), "unbind keeps started service");
        ServiceRuntimeRegistry.Snapshot recovering = registry.markProcessDied(
                "instance-a", "pkg:remote", 1).get(0);
        require(recovering.state() == ServiceRuntimeRegistry.State.RECOVERING
                        && !recovering.foreground()
                        && "ACTION_SYNC_2".equals(recovering.lastStartAction()),
                "redeliver service recovery");
        service = registry.completeRecovery("instance-a", "pkg.SyncService", 1, 2);
        require(service.generation() == 2 && service.state() == ServiceRuntimeRegistry.State.ACTIVE,
                "service recovery generation");
        service = registry.stopStarted("instance-a", "pkg.SyncService", 2);
        require(service.state() == ServiceRuntimeRegistry.State.DESTROYED, "service destroyed when unowned");
    }

    private static void testDynamicReceiverRegistry() {
        DynamicReceiverRegistry registry = new DynamicReceiverRegistry();
        registry.register("r1", "pkg.app", "session-a", 1, 0, "pkg.PrivateReceiver",
                java.util.List.of("ACTION_PRIVATE"), false);
        registry.register("r2", "pkg.app", "session-b", 1, 0, "pkg.PublicReceiver",
                java.util.List.of("ACTION_PRIVATE", "ACTION_PUBLIC"), true);
        registry.register("r-empty", "pkg.app", "session-a", 1, 0, "pkg.InertReceiver",
                java.util.List.of(), false);
        require(registry.resolve("ACTION_PRIVATE", 0, "session-a", false).size() == 2,
                "same-session private broadcast");
        require(registry.resolve("ACTION_PRIVATE", 0, "session-b", false).size() == 1,
                "cross-session non-exported hidden");
        require(registry.resolve("ACTION_PRIVATE", 0, "", true).size() == 1,
                "external exported receiver");
        require(registry.resolve("ACTION_NEVER_MATCHES", 0, "session-a", false).isEmpty(),
                "empty dynamic receiver filter must be inert");
        require(registry.removeSession("session-a", 1) == 2 && registry.size() == 1,
                "receiver session cleanup");
    }

    private static void testManifestReceiverRegistry() {
        ManifestReceiverRegistry registry = new ManifestReceiverRegistry();
        registry.registerPackage("com.example.sender", 0,
                java.util.Set.of("com.example.SEND_SECURE"), java.util.List.of());
        registry.registerPackage("com.example.target", 0, java.util.Set.of(), java.util.List.of(
                new ManifestReceiverRegistry.Receiver("com.example.target",
                        "com.example.target.PublicReceiver", "com.example.target:remote",
                        true, true, "com.example.SEND_SECURE", java.util.Set.of("ACTION_PUBLIC")),
                new ManifestReceiverRegistry.Receiver("com.example.target",
                        "com.example.target.PrivateReceiver", "com.example.target",
                        false, true, "", java.util.Set.of("ACTION_PRIVATE"))));
        ManifestReceiverRegistry.Resolution publicRoute = registry.resolveExplicit(
                "com.example.sender", 0, "com.example.target", 0,
                "com.example.target.PublicReceiver");
        require(publicRoute.requiresProcessStart(), "manifest Receiver should request process activation");
        registry.bindSession("com.example.target", 0, "com.example.target:remote", "target-session", 3);
        publicRoute = registry.resolveExplicit("com.example.sender", 0, "com.example.target", 0,
                "com.example.target.PublicReceiver");
        require(publicRoute.binding().isPresent()
                        && publicRoute.binding().get().generation() == 3,
                "manifest Receiver active binding");
        boolean privateDenied = false;
        try {
            registry.resolveExplicit("com.example.sender", 0, "com.example.target", 0,
                    "com.example.target.PrivateReceiver");
        } catch (SecurityException expected) {
            privateDenied = "MANIFEST_RECEIVER_NOT_EXPORTED".equals(expected.getMessage());
        }
        require(privateDenied, "non-exported manifest Receiver allowed cross package");
        boolean crossUserDenied = false;
        try {
            registry.resolveExplicit("com.example.sender", 0, "com.example.target", 1,
                    "com.example.target.PublicReceiver");
        } catch (SecurityException expected) {
            crossUserDenied = "RECEIVER_CROSS_USER_DENIED".equals(expected.getMessage());
        }
        require(crossUserDenied, "manifest Receiver cross-user access allowed");
        registry.registerPackage("com.example.unprivileged", 0, java.util.Set.of(), java.util.List.of());
        boolean permissionDenied = false;
        try {
            registry.resolveExplicit("com.example.unprivileged", 0, "com.example.target", 0,
                    "com.example.target.PublicReceiver");
        } catch (SecurityException expected) {
            permissionDenied = expected.getMessage().startsWith("MANIFEST_RECEIVER_PERMISSION_DENIED");
        }
        require(permissionDenied, "manifest Receiver permission not enforced");
        require(registry.removeSession("target-session", 3) == 1, "manifest Receiver session cleanup");
        require(registry.resolveExplicit("com.example.target", 0, "com.example.target", 0,
                "com.example.target.PrivateReceiver").requiresProcessStart(),
                "same-package private manifest Receiver denied");

        ManifestReceiverRegistry implicit = new ManifestReceiverRegistry();
        implicit.registerPackage("com.example.sender", 0,
                java.util.Set.of("com.example.SEND_SECURE"), java.util.List.of());
        ManifestReceiverRegistry.Filter highFilter = new ManifestReceiverRegistry.Filter(700,
                java.util.Set.of("ACTION_DATA"), java.util.Set.of("CATEGORY_ONE"),
                java.util.List.of(new ManifestReceiverRegistry.DataRule("content", "example.test",
                        "", "/items", "", "text/*")));
        ManifestReceiverRegistry.Filter lowFilter = new ManifestReceiverRegistry.Filter(10,
                java.util.Set.of("ACTION_DATA"), java.util.Set.of("CATEGORY_ONE"),
                java.util.List.of(new ManifestReceiverRegistry.DataRule("content", "example.test",
                        "", "/", "", "*/*")));
        implicit.registerPackage("com.example.high", 0,
                java.util.Set.of("com.example.RECEIVE_SECURE"), java.util.List.of(
                        new ManifestReceiverRegistry.Receiver("com.example.high",
                                "com.example.high.HighReceiver", "com.example.high", true, true,
                                "com.example.SEND_SECURE", java.util.List.of(highFilter))));
        implicit.registerPackage("com.example.low", 0,
                java.util.Set.of("com.example.RECEIVE_SECURE"), java.util.List.of(
                        new ManifestReceiverRegistry.Receiver("com.example.low",
                                "com.example.low.LowReceiver", "com.example.low", true, true,
                                "", java.util.List.of(lowFilter))));
        BroadcastIntent intent = new BroadcastIntent("ACTION_DATA", java.util.Set.of("CATEGORY_ONE"),
                "content", "example.test", "/items/42", "text/plain");
        java.util.List<ManifestReceiverRegistry.Resolution> implicitRoutes = implicit.resolveImplicit(
                "com.example.sender", 0, intent, "com.example.RECEIVE_SECURE");
        require(implicitRoutes.size() == 2, "implicit manifest Receiver count");
        require(implicitRoutes.get(0).priority() == 700
                        && implicitRoutes.get(0).receiver().className().endsWith("HighReceiver")
                        && implicitRoutes.get(1).priority() == 10,
                "implicit manifest Receiver priority ordering");
        require(implicit.resolveImplicit("com.example.sender", 0,
                new BroadcastIntent("ACTION_DATA", java.util.Set.of("MISSING_CATEGORY"),
                        "content", "example.test", "/items/42", "text/plain"),
                "com.example.RECEIVE_SECURE").isEmpty(),
                "implicit category mismatch");
        require(implicit.resolveImplicit("com.example.sender", 0,
                new BroadcastIntent("ACTION_DATA", java.util.Set.of("CATEGORY_ONE"),
                        "content", "wrong.test", "/items/42", "text/plain"),
                "com.example.RECEIVE_SECURE").isEmpty(),
                "implicit data host mismatch");
        require(implicit.resolveImplicit("com.example.sender", 0, intent,
                "com.example.PERMISSION_NOT_HELD").isEmpty(),
                "sender-required receiver permission not enforced");
        require(implicit.actionIndexKeyCount() == 1, "implicit action index key count");
        implicit.registerPackage("com.example.high", 0,
                java.util.Set.of("com.example.RECEIVE_SECURE"), java.util.List.of());
        implicitRoutes = implicit.resolveImplicit("com.example.sender", 0, intent,
                "com.example.RECEIVE_SECURE");
        require(implicitRoutes.size() == 1
                        && implicitRoutes.get(0).receiver().packageName().equals("com.example.low"),
                "package replacement left stale action index");
        implicit.removePackage("com.example.low", 0);
        require(implicit.resolveImplicit("com.example.sender", 0, intent,
                "com.example.RECEIVE_SECURE").isEmpty() && implicit.actionIndexKeyCount() == 0,
                "package removal left stale action index");

        ManifestReceiverRegistry bounded = new ManifestReceiverRegistry();
        bounded.registerPackage("com.example.sender", 0, java.util.Set.of(), java.util.List.of());
        ManifestReceiverRegistry.Filter boundedFilter = new ManifestReceiverRegistry.Filter(0,
                java.util.Set.of("ACTION_LIMIT"), java.util.Set.of(), java.util.List.of());
        for (int index = 0; index <= ManifestReceiverRegistry.MAX_IMPLICIT_MATCHES; index++) {
            String packageName = "com.example.receiver" + index;
            bounded.registerPackage(packageName, 0, java.util.Set.of(), java.util.List.of(
                    new ManifestReceiverRegistry.Receiver(packageName, packageName + ".Receiver",
                            packageName, true, true, "", java.util.List.of(boundedFilter))));
        }
        boolean matchLimit = false;
        try {
            bounded.resolveImplicit("com.example.sender", 0,
                    new BroadcastIntent("ACTION_LIMIT", java.util.Set.of(), "", "", "", ""), "");
        } catch (IllegalStateException expected) {
            matchLimit = "MANIFEST_RECEIVER_MATCH_LIMIT_EXCEEDED".equals(expected.getMessage());
        }
        require(matchLimit, "implicit Receiver match limit");
    }

    private static void testOrderedBroadcastState() {
        OrderedBroadcastState state = OrderedBroadcastState.initial(1, "initial",
                java.util.Map.of("source", "sender"));
        state = state.apply(new OrderedBroadcastState.ResultUpdate()
                .resultCode(2).resultData("receiver-one")
                .resultExtras(java.util.Map.of("step", "one")).abort().clearAbort());
        require(state.resultCode() == 2 && "receiver-one".equals(state.resultData())
                        && "one".equals(state.resultExtras().get("step")) && !state.aborted(),
                "ordered result propagation and clear-abort");
        state = state.apply(new OrderedBroadcastState.ResultUpdate().abort());
        require(state.aborted(), "ordered abort");
        boolean oversized = false;
        try {
            OrderedBroadcastState.initial(0, "x".repeat(OrderedBroadcastState.MAX_RESULT_DATA_CHARS + 1),
                    java.util.Map.of());
        } catch (IllegalArgumentException expected) { oversized = true; }
        require(oversized, "ordered result data limit");
        boolean tooManyCategories = false;
        try {
            java.util.LinkedHashSet<String> categories = new java.util.LinkedHashSet<>();
            for (int index = 0; index <= BroadcastIntent.MAX_CATEGORIES; index++) {
                categories.add("CATEGORY_" + index);
            }
            new BroadcastIntent("ACTION", categories, "", "", "", "");
        } catch (IllegalArgumentException expected) { tooManyCategories = true; }
        require(tooManyCategories, "broadcast category limit");
        ManifestReceiverRegistry.Filter independentDataFilter =
                new ManifestReceiverRegistry.Filter(0, java.util.Set.of("ACTION"), java.util.Set.of(),
                        java.util.List.of(new ManifestReceiverRegistry.DataRule(
                                "", "", "", "/items", "", "")));
        require(independentDataFilter.dataRules().size() == 1,
                "independent data path registration rejected");
    }

    private static void testProviderAuthorityRegistry() {
        ProviderAuthorityRegistry registry = new ProviderAuthorityRegistry();
        ProviderAuthorityRegistration registration = registry.registerSession("instance-a", 0,
                "pkg.data;pkg.files", "pkg.DataProvider", "pkg:provider", true, "session-a", 1);
        ProviderAuthorityRegistry.Entry entry = registration.entries().get(0);
        require(registration.createdAuthorities().size() == 2, "provider authorities not staged atomically");
        require(registry.resolve(0, "instance-a", "pkg.data") != null, "provider resolve instance");
        require(entry.virtualAuthority().contains("u0.instance-a.pkg.data"), "virtual provider authority");
        require(registry.resolveExported(0, "pkg.data") != null, "exported provider resolve");
        require(registry.requireOwned(0, "instance-a", "pkg.data", "session-a", 1) != null,
                "provider owner lookup");
        ProviderAuthorityRegistration duplicate = registry.registerSession("instance-a", 0,
                "pkg.data;pkg.files", "pkg.DataProvider", "pkg:provider", true, "session-a", 1);
        require(!duplicate.createdAny() && registry.size() == 2, "idempotent provider registration");

        boolean collision = false;
        try {
            registry.registerSession("instance-b", 0, "pkg.data", "pkg.OtherProvider",
                    "pkg.other:provider", true, "session-b", 1);
        } catch (IllegalStateException expected) {
            collision = true;
        }
        require(collision, "provider authority collision accepted in same virtual user");
        registry.registerSession("instance-b", 1, "pkg.data", "pkg.OtherProvider",
                "pkg.other:provider", true, "session-b", 1);
        require(registry.size() == 3, "provider authority must be isolated by virtual user");

        require(registry.rebindSession("instance-a", 0, "pkg:provider", "session-a", 1,
                "session-a", 2) == 2, "provider generation recovery");
        boolean staleDenied = false;
        try { registry.requireOwned(0, "instance-a", "pkg.data", "session-a", 1); }
        catch (SecurityException expected) { staleDenied = true; }
        require(staleDenied, "stale provider generation accepted");
        require(registry.requireOwned(0, "instance-a", "pkg.data", "session-a", 2) != null,
                "recovered provider ownership missing");
        require(registry.removeSession("session-a", 2) == 2, "provider session cleanup");
        require(registry.unregisterInstance(1, "instance-b") == 1, "provider instance cleanup");
        require(registry.size() == 0, "provider authority leaked");
    }

    private static void testProviderObserverRegistry() {
        ProviderObserverRegistry registry = new ProviderObserverRegistry();
        ProviderObserverRegistry.Registration registration = registry.register("observer-a",
                "u0:caller", 0, "caller-session", 1, "u0:provider", "provider-session", 3,
                "pkg.data", "content://pkg.data/items", true, false);
        require(registration.created(), "observer registration created");
        require(!registry.register("observer-a", "u0:caller", 0, "caller-session", 1,
                "u0:provider", "provider-session", 3, "pkg.data",
                "content://pkg.data/items/", true, false).created(), "observer idempotence");
        require(registry.resolve(0, "pkg.data", "content://pkg.data/items/1",
                "u0:provider", "provider-session", 3).size() == 1, "observer descendant match");
        require(registry.resolve(1, "pkg.data", "content://pkg.data/items/1",
                "u1:provider", "provider-session", 3).isEmpty(), "observer virtual user isolation");
        boolean wrongOwner = false;
        try { registry.unregister("observer-a", "u0:other", "caller-session", 1); }
        catch (SecurityException expected) { wrongOwner = true; }
        require(wrongOwner, "observer owner mismatch");
        require(registry.removeSession("caller-session", 1) == 1, "observer caller death cleanup");
        require(registry.size() == 0, "observer registry leaked");
    }

    private static void testUriGrantRegistry() throws Exception {
        UriGrantRegistry registry = new UriGrantRegistry();
        UriGrantRegistry.Grant persistent = registry.grant("u0:owner", "owner-session", 3,
                "u0:target", "target-session", 7, 0, "content://pkg.data/items",
                UriGrantRegistry.READ, false, 100, 100);
        UriGrantRegistry.Authorization persistentAuth = registry.beginAuthorization(
                "u0:target", "target-session", 7, 0, 120);
        require(persistentAuth.allows("u0:target", "content://pkg.data/items/1", UriGrantRegistry.READ),
                "persistent URI grant preview");
        require(!persistentAuth.commit(120).oneTimeConsumed(), "persistent grant not consumed");
        require(registry.size(120) == 1, "persistent URI grant retained");

        UriGrantRegistry.Grant packageScoped = registry.grantForTargetInstance(
                "u0:owner", "owner-session", 3, "u0:target", 0,
                "content://pkg.exact/item", UriGrantRegistry.READ, false,
                121, UriGrantRegistry.DURABLE_TTL_MS);
        UriGrantRegistry.Authorization coldTarget = registry.beginAuthorization(
                "u0:target", "replacement-session", 99, 0, 122);
        require(coldTarget.allows("u0:target", "content://pkg.exact/item", UriGrantRegistry.READ),
                "package-scoped grant did not survive target cold start");
        require(!coldTarget.allows("u0:target", "content://pkg.exact/item/child", UriGrantRegistry.READ),
                "exact URI grant widened without prefix flag");
        require(packageScoped.expiresAtMs() == Long.MAX_VALUE,
                "package-scoped Context grant was not durable");
        require(registry.revokeOwned("u0:owner", "content://pkg.exact/item",
                UriGrantRegistry.READ, 123) == 1, "package-scoped grant revocation");

        UriGrantRegistry.Grant oneTime = registry.grant("u0:owner", "owner-session", 3,
                "u0:target", "target-session", 7, 0, "content://pkg.data",
                UriGrantRegistry.READ | UriGrantRegistry.WRITE, true, 130, 100);
        UriGrantRegistry.Authorization oneTimeAuth = registry.beginAuthorization(
                "u0:target", "target-session", 7, 0, 140);
        require(oneTimeAuth.allows("u0:target", "content://pkg.data/a", UriGrantRegistry.WRITE),
                "one-time first URI");
        require(oneTimeAuth.allows("u0:target", "content://pkg.data/b", UriGrantRegistry.READ),
                "one-time batch second URI");
        require(oneTimeAuth.commit(140).oneTimeConsumed(), "one-time grant consumed once");
        require(registry.size(140) == 1, "one-time URI grant removed");
        boolean replayDenied = false;
        try {
            UriGrantRegistry.Authorization replay = registry.beginAuthorization(
                    "u0:target", "target-session", 7, 0, 141);
            require(!replay.allows("u0:target", "content://pkg.data/a", UriGrantRegistry.WRITE),
                    "one-time replay preview denied");
            replay.commit(141);
        } catch (SecurityException expected) { replayDenied = true; }
        require(replayDenied, "one-time replay rejected");

        UriGrantRegistry.Grant revocable = registry.grant("u0:owner", "owner-session", 3,
                "u0:target", "target-session", 7, 0, "content://pkg.private",
                UriGrantRegistry.READ, false, 150, 100);
        boolean wrongOwnerSession = false;
        try { registry.revoke(revocable.id(), "u0:owner", "other-session", 3, 160); }
        catch (SecurityException expected) { wrongOwnerSession = true; }
        require(wrongOwnerSession, "grant owner session mismatch");
        require(registry.revoke(revocable.id(), "u0:owner", "owner-session", 3, 160),
                "active revocation");

        registry.grant("u0:owner", "owner-session", 3, "u0:target", "target-session", 7,
                0, "content://pkg.session", UriGrantRegistry.READ, false, 170, 100);
        require(registry.revokeSession("target-session", 7) == 2, "target session cleanup");
        registry.grant("u0:owner", "owner-session", 3, "u0:target", "target-session", 8,
                0, "content://pkg.expired", UriGrantRegistry.READ, false, 200, 10);
        require(registry.size(211) == 0, "grant expiry cleanup");

        UriGrantRegistry expiryRace = new UriGrantRegistry();
        expiryRace.grant("u0:owner", "owner-session", 1, "u0:target", "target-session", 1,
                0, "content://pkg.race", UriGrantRegistry.READ, true, 220, 5);
        UriGrantRegistry.Authorization expiring = expiryRace.beginAuthorization(
                "u0:target", "target-session", 1, 0, 221);
        require(expiring.allows("u0:target", "content://pkg.race/1", UriGrantRegistry.READ),
                "pre-expiry authorization preview");
        boolean expiredAtCommit = false;
        try { expiring.commit(226); } catch (SecurityException expected) { expiredAtCommit = true; }
        require(expiredAtCommit, "grant expiry revalidated at commit");

        UriGrantRegistry concurrent = new UriGrantRegistry();
        concurrent.grant("u0:owner", "owner-session", 1, "u0:target", "target-session", 1,
                0, "content://pkg.once", UriGrantRegistry.READ, true, 300, 1000);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(16);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                UriGrantRegistry.Authorization auth = concurrent.beginAuthorization(
                        "u0:target", "target-session", 1, 0, 310);
                if (!auth.allows("u0:target", "content://pkg.once/1", UriGrantRegistry.READ)) return false;
                try { return auth.commit(310).oneTimeConsumed(); }
                catch (SecurityException expected) { return false; }
            }));
        }
        ready.await();
        go.countDown();
        int winners = 0;
        for (java.util.concurrent.Future<Boolean> result : results) if (result.get()) winners++;
        pool.shutdownNow();
        require(winners == 1, "one-time grant has exactly one concurrent winner");
        require(concurrent.size(310) == 0, "concurrent one-time grant removed");

        UriGrantRegistry capacity = new UriGrantRegistry();
        for (int i = 0; i < UriGrantRegistry.MAX_ACTIVE_GRANTS; i++) {
            capacity.grant("u0:owner", "owner-session", 1, "u0:target", "target-session", 1,
                    0, "content://pkg.capacity/" + i, UriGrantRegistry.READ, false, 400, 1000);
        }
        boolean capacityDenied = false;
        try {
            capacity.grant("u0:owner", "owner-session", 1, "u0:target", "target-session", 1,
                    0, "content://pkg.capacity/overflow", UriGrantRegistry.READ, false, 400, 1000);
        } catch (IllegalStateException expected) { capacityDenied = true; }
        require(capacityDenied, "URI grant capacity fail-closed");
        require(persistent.id() != null && oneTime.id() != null, "grant identifiers");
    }

    private static void testCursorLeaseRegistry() {
        CursorLeaseRegistry registry = new CursorLeaseRegistry();
        CursorLeaseRegistry.Lease lease = registry.open("session-a", "instance-provider",
                java.util.List.of("id", "name"), 2, 4, 100, 50);
        require(registry.require(lease.token(), "session-a", 4, 120).rowCount() == 2,
                "cursor lease lookup");
        require(registry.requirePage(lease.token(), "session-a", 4, 0, 0, 120).nextOffset() == 0,
                "cursor page reservation");
        CursorLeaseRegistry.Lease advanced = registry.commitPage(lease.token(), "session-a", 4, 0, 1, false);
        require(advanced.nextOffset() == 1 && advanced.nextSequence() == 1,
                "cursor sequence advancement");
        boolean replay = false;
        try { registry.requirePage(lease.token(), "session-a", 4, 0, 0, 120); }
        catch (SecurityException expected) { replay = true; }
        require(replay, "cursor page replay rejected");
        boolean wrongOwner = false;
        try { registry.require(lease.token(), "session-b", 4, 120); }
        catch (SecurityException expected) { wrongOwner = true; }
        require(wrongOwner, "cursor lease owner isolation");
        require(registry.close(lease.token(), "session-a", 4), "cursor lease close");
        CursorLeaseRegistry.Lease expired = registry.open("session-a", "instance-provider",
                java.util.List.of("id"), 1, 4, 200, 10);
        require(registry.size(211) == 0, "cursor lease expiry");
        boolean missing = false;
        try { registry.require(expired.token(), "session-a", 4, 211); }
        catch (IllegalArgumentException expected) { missing = true; }
        require(missing, "expired cursor lease unavailable");
    }

    private static void testComponentProcessPlanner() throws Exception {
        BinaryXmlFixtureBuilder f = new BinaryXmlFixtureBuilder();
        ManifestModel model = new BinaryXmlManifestParser().parse(f
                .start("manifest", BinaryXmlFixtureBuilder.text("package", "com.example.guest"))
                .start("application", BinaryXmlFixtureBuilder.text("process", ":global"))
                .start("activity", BinaryXmlFixtureBuilder.text("name", ".MainActivity")).end("activity")
                .start("service", BinaryXmlFixtureBuilder.text("name", ".RemoteService"),
                        BinaryXmlFixtureBuilder.text("process", ":remote")).end("service")
                .start("service", BinaryXmlFixtureBuilder.text("name", ".IsolatedService"),
                        BinaryXmlFixtureBuilder.bool("isolatedProcess", true)).end("service")
                .end("application").end("manifest").build());
        java.util.List<ComponentProcessPlanner.ProcessPlan> plans = new ComponentProcessPlanner().plan(model);
        require(plans.size() == 3, "component process plan count");
        require(plans.stream().anyMatch(p -> p.normalizedName().equals("com.example.guest:global")),
                "application process inherited by default component");
        require(plans.stream().anyMatch(p -> p.normalizedName().equals("com.example.guest:remote")),
                "remote process normalized");
        require(plans.stream().anyMatch(ComponentProcessPlanner.ProcessPlan::isolated),
                "isolated process planned");
    }

    private static TokenGenerator testTokens() {
        java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
        return purpose -> purpose + "-test-" + sequence.incrementAndGet();
    }

    private static void testRecoverableFileStoreFatalBoundary() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("fatal-store");
        RecoverableFileStore store = new RecoverableFileStore(root.resolve("state.json"));
        store.write("ok");
        boolean escaped = false;
        try { store.read(value -> { throw new AssertionError("fatal-persistence"); }, ""); }
        catch (AssertionError expected) { escaped = true; }
        require(escaped, "persistence boundary converted Error into corruption recovery");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError("Failed: " + name);
    }
}
