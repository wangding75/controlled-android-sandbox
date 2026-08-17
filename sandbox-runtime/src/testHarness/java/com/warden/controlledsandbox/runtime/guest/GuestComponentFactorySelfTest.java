package com.warden.controlledsandbox.runtime.guest;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;

public final class GuestComponentFactorySelfTest {
    public static void main(String[] args) throws Exception {
        GuestComponentFactory.clearCacheForTest();
        ClassLoader loader = GuestComponentFactorySelfTest.class.getClassLoader();

        ClassLoader processLoader = GuestComponentFactory.instantiateClassLoader(loader,
                CountingFactory.class.getName(), new ApplicationInfo());
        require(processLoader != loader, "factory may return a derived ClassLoader");
        require(CountingFactory.classLoaders == 1, "LoadedApk wraps ClassLoader through factory first");

        Application first = GuestComponentFactory.instantiateApplication(processLoader,
                CountingFactory.class.getName(), GuestApplication.class.getName());
        Application second = GuestComponentFactory.instantiateApplication(processLoader,
                CountingFactory.class.getName(), GuestApplication.class.getName());
        require(first instanceof GuestApplication && second instanceof GuestApplication,
                "application instantiated through factory");
        require(GuestComponentFactory.cachedFactory(processLoader, CountingFactory.class.getName())
                        == CountingFactory.last,
                "derived ClassLoader reuses the LoadedApk factory singleton");
        require(CountingFactory.instances == 1, "factory constructed once for the same loader");

        Activity activity = GuestComponentFactory.instantiateActivity(processLoader,
                CountingFactory.class.getName(), GuestActivity.class.getName(), new Intent("act"));
        Service service = GuestComponentFactory.instantiateService(processLoader,
                CountingFactory.class.getName(), GuestService.class.getName(), new Intent("svc"));
        BroadcastReceiver receiver = GuestComponentFactory.instantiateReceiver(processLoader,
                CountingFactory.class.getName(), GuestReceiver.class.getName(), new Intent("rcv"));
        ContentProvider provider = GuestComponentFactory.instantiateProvider(processLoader,
                CountingFactory.class.getName(), GuestProvider.class.getName());
        require(activity instanceof GuestActivity, "activity factory path");
        require(service instanceof GuestService, "service factory path");
        require(receiver instanceof GuestReceiver, "receiver factory path");
        require(provider instanceof GuestProvider, "provider factory path");
        require(CountingFactory.applications == 2 && CountingFactory.activities == 1
                        && CountingFactory.services == 1 && CountingFactory.receivers == 1
                        && CountingFactory.providers == 1,
                "factory saw every Sandbox-created component type");
        require(CountingFactory.instances == 1, "later component types reuse the same factory");

        Application fallback = GuestComponentFactory.instantiateApplication(processLoader, "",
                GuestApplication.class.getName());
        require(fallback instanceof GuestApplication, "empty factory class falls back");

        boolean wrongType = false;
        try {
            GuestComponentFactory.instantiateApplication(processLoader,
                    GuestApplication.class.getName(), GuestApplication.class.getName());
        } catch (IllegalArgumentException expected) {
            wrongType = expected.getMessage().contains("wrong type");
        }
        require(wrongType, "non-factory class is rejected");

        ClassLoader secondLoader = new ClassLoader(loader) { };
        Application secondLoaderApplication = GuestComponentFactory.instantiateApplication(
                secondLoader, CountingFactory.class.getName(), GuestApplication.class.getName());
        require(secondLoaderApplication instanceof GuestApplication,
                "a distinct ClassLoader can instantiate the Guest Application");
        require(GuestComponentFactory.cachedFactory(secondLoader, CountingFactory.class.getName())
                        != GuestComponentFactory.cachedFactory(loader, CountingFactory.class.getName()),
                "factory cache is isolated by ClassLoader identity");
        GuestComponentFactory.clearCacheForLoader(secondLoader);
        require(GuestComponentFactory.cachedFactory(secondLoader, CountingFactory.class.getName()) == null,
                "retired ClassLoader factory cache is removed");
        System.out.println("PASS guest AppComponentFactory lifecycle self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    public static final class CountingFactory extends AppComponentFactory {
        static int instances;
        static int classLoaders;
        static int applications;
        static int activities;
        static int services;
        static int receivers;
        static int providers;
        static CountingFactory last;

        public CountingFactory() {
            instances++;
            last = this;
        }

        @Override
        public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
            classLoaders++;
            // Model a production SplitCompat/loader-wrapping factory.  Every later component
            // call receives the returned loader, but must still use this exact Factory object.
            return new ClassLoader(cl) { };
        }

        @Override
        public Application instantiateApplication(ClassLoader cl, String className)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            applications++;
            return super.instantiateApplication(cl, className);
        }

        @Override
        public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            activities++;
            return super.instantiateActivity(cl, className, intent);
        }

        @Override
        public Service instantiateService(ClassLoader cl, String className, Intent intent)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            services++;
            return super.instantiateService(cl, className, intent);
        }

        @Override
        public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            receivers++;
            return super.instantiateReceiver(cl, className, intent);
        }

        @Override
        public ContentProvider instantiateProvider(ClassLoader cl, String className)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            providers++;
            return super.instantiateProvider(cl, className);
        }
    }

    public static final class GuestApplication extends Application { }

    public static final class GuestActivity extends Activity { }

    public static final class GuestService extends Service { }

    public static final class GuestReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) { }
    }

    public static final class GuestProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
}
