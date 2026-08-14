package com.warden.controlledsandbox.runtime.guest;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Instantiates guest components through the APK-declared AppComponentFactory.
 *
 * <p>Android's {@code LoadedApk} keeps one factory per process. Some production factories
 * install process-local bootstrap state in that instance, so constructing a new factory
 * per component is observably different from a normal launch.</p>
 */
public final class GuestComponentFactory {
    private static final ConcurrentHashMap<String, AppComponentFactory> CACHE = new ConcurrentHashMap<>();

    private GuestComponentFactory() { }

    /**
     * LoadedApk wraps the APK ClassLoader through the declared factory before any Application
     * or component is created. Skipping this leaves factory-installed loaders (MultiDex,
     * plugin, class-tracing) out of the process.
     */
    public static ClassLoader instantiateClassLoader(ClassLoader loader, String factoryClass,
                                                     ApplicationInfo applicationInfo)
            throws Exception {
        if (loader == null) throw new IllegalArgumentException("class loader is required");
        AppComponentFactory factory = load(loader, factoryClass);
        if (factory == null) return loader;
        GuestNativeBindingDiagnostic.recordLoader("factory.input", loader);
        ClassLoader installed = factory.instantiateClassLoader(loader, applicationInfo);
        ClassLoader result = installed == null ? loader : installed;
        GuestNativeBindingDiagnostic.recordLoader("factory.output", result);
        return result;
    }

    public static Application instantiateApplication(ClassLoader loader, String factoryClass,
                                                      String applicationClass) throws Exception {
        AppComponentFactory factory = load(loader, factoryClass);
        if (factory != null) return factory.instantiateApplication(loader, applicationClass);
        return newInstance(loader, applicationClass, Application.class);
    }

    public static Activity instantiateActivity(ClassLoader loader, String factoryClass,
                                               String activityClass, Intent intent) throws Exception {
        AppComponentFactory factory = load(loader, factoryClass);
        if (factory != null) {
            return factory.instantiateActivity(loader, activityClass,
                    intent == null ? new Intent() : intent);
        }
        return newInstance(loader, activityClass, Activity.class);
    }

    public static Service instantiateService(ClassLoader loader, String factoryClass,
                                             String serviceClass, Intent intent) throws Exception {
        AppComponentFactory factory = load(loader, factoryClass);
        if (factory != null) {
            return factory.instantiateService(loader, serviceClass,
                    intent == null ? new Intent() : intent);
        }
        return newInstance(loader, serviceClass, Service.class);
    }

    public static BroadcastReceiver instantiateReceiver(ClassLoader loader, String factoryClass,
                                                        String receiverClass, Intent intent)
            throws Exception {
        AppComponentFactory factory = load(loader, factoryClass);
        if (factory != null) {
            return factory.instantiateReceiver(loader, receiverClass,
                    intent == null ? new Intent() : intent);
        }
        return newInstance(loader, receiverClass, BroadcastReceiver.class);
    }

    public static ContentProvider instantiateProvider(ClassLoader loader, String factoryClass,
                                                      String providerClass) throws Exception {
        AppComponentFactory factory = load(loader, factoryClass);
        if (factory != null) return factory.instantiateProvider(loader, providerClass);
        return newInstance(loader, providerClass, ContentProvider.class);
    }

    static void clearCacheForTest() {
        CACHE.clear();
    }

    static AppComponentFactory cachedFactory(ClassLoader loader, String factoryClass) {
        if (factoryClass == null || factoryClass.trim().isEmpty() || loader == null) return null;
        return CACHE.get(cacheKey(loader, factoryClass.trim()));
    }

    private static AppComponentFactory load(ClassLoader loader, String factoryClass) throws Exception {
        if (factoryClass == null || factoryClass.trim().isEmpty()) return null;
        String key = cacheKey(loader, factoryClass.trim());
        AppComponentFactory cached = CACHE.get(key);
        if (cached != null) return cached;
        Class<?> type = loader.loadClass(factoryClass.trim());
        if (!AppComponentFactory.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("AppComponentFactory has wrong type: " + factoryClass);
        }
        AppComponentFactory created = (AppComponentFactory) type.getDeclaredConstructor().newInstance();
        AppComponentFactory previous = CACHE.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }

    private static String cacheKey(ClassLoader loader, String factoryClass) {
        return System.identityHashCode(loader) + "\n" + factoryClass;
    }

    private static <T> T newInstance(ClassLoader loader, String className, Class<T> expected)
            throws Exception {
        Class<?> type = loader.loadClass(className);
        if (!expected.isAssignableFrom(type)) {
            throw new IllegalArgumentException(expected.getSimpleName() + " class has wrong type: "
                    + className);
        }
        return expected.cast(type.getDeclaredConstructor().newInstance());
    }
}
