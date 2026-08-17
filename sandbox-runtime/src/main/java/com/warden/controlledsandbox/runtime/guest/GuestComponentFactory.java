package com.warden.controlledsandbox.runtime.guest;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instantiates guest components through the APK-declared AppComponentFactory.
 *
 * <p>Android's {@code LoadedApk} keeps one factory per process. Some production factories
 * install process-local bootstrap state in that instance, so constructing a new factory
 * per component is observably different from a normal launch.</p>
 */
public final class GuestComponentFactory {
    /**
     * The platform keeps one AppComponentFactory per LoadedApk/ClassLoader.  Do not collapse
     * that identity to identityHashCode(): a retired Guest loader can have its hash reused by a
     * later generation, and the old factory would then instantiate components with the wrong
     * loader/state.  The outer key is the actual loader object and the cache is explicitly
     * retired with the Guest Session below.
     */
    private static final ConcurrentHashMap<ClassLoader,
            ConcurrentHashMap<String, AppComponentFactory>> CACHE = new ConcurrentHashMap<>();
    /**
     * Tracks the LoadedApk factory family.  AppComponentFactory.instantiateClassLoader() is
     * allowed to return a child/rewritten loader; that loader must still reuse the Factory
     * selected for the original LoadedApk.  The map is deliberately explicit so teardown can
     * retire aliases together with the root instead of retaining a derived loader.
     */
    private static final ConcurrentHashMap<ClassLoader, ClassLoader> FAMILY_ROOTS =
            new ConcurrentHashMap<>();

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
        bindFactoryAlias(loader, result, factoryClass, factory);
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
        FAMILY_ROOTS.clear();
    }

    static void clearCacheForLoader(ClassLoader loader) {
        if (loader == null) return;
        clearFactoryFamily(loader);
        if (loader instanceof GuestClassLoader guestLoader) {
            clearFactoryFamily(guestLoader.definingLoader());
        }
    }

    static AppComponentFactory cachedFactory(ClassLoader loader, String factoryClass) {
        if (factoryClass == null || factoryClass.trim().isEmpty() || loader == null) return null;
        ConcurrentHashMap<String, AppComponentFactory> factories = CACHE.get(loader);
        if (factories == null) return null;
        return factories.get(factoryClass.trim());
    }

    private static AppComponentFactory load(ClassLoader loader, String factoryClass) throws Exception {
        if (factoryClass == null || factoryClass.trim().isEmpty()) return null;
        String key = factoryClass.trim();
        familyRoot(loader);
        ConcurrentHashMap<String, AppComponentFactory> factories = CACHE.computeIfAbsent(
                loader, ignored -> new ConcurrentHashMap<>());
        AppComponentFactory cached = factories.get(key);
        if (cached != null) return cached;
        Class<?> type = loader.loadClass(factoryClass.trim());
        if (!AppComponentFactory.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("AppComponentFactory has wrong type: " + factoryClass);
        }
        AppComponentFactory created = (AppComponentFactory) type.getDeclaredConstructor().newInstance();
        AppComponentFactory previous = factories.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }

    private static void bindFactoryAlias(ClassLoader source, ClassLoader alias,
                                         String factoryClass, AppComponentFactory factory) {
        if (source == null || alias == null || factoryClass == null
                || factoryClass.trim().isEmpty()) return;
        ClassLoader root = familyRoot(source);
        ClassLoader existingRoot = FAMILY_ROOTS.putIfAbsent(alias, root);
        if (existingRoot != null && existingRoot != root) {
            throw new IllegalStateException("GUEST_COMPONENT_FACTORY_LOADER_FAMILY_CONFLICT");
        }
        ConcurrentHashMap<String, AppComponentFactory> factories = CACHE.computeIfAbsent(
                alias, ignored -> new ConcurrentHashMap<>());
        String key = factoryClass.trim();
        AppComponentFactory previous = factories.putIfAbsent(key, factory);
        if (previous != null && previous != factory) {
            throw new IllegalStateException("GUEST_COMPONENT_FACTORY_ALIAS_CONFLICT:" + key);
        }
    }

    private static ClassLoader familyRoot(ClassLoader loader) {
        return FAMILY_ROOTS.computeIfAbsent(loader, ignored -> loader);
    }

    private static void clearFactoryFamily(ClassLoader loader) {
        if (loader == null) return;
        ClassLoader root = FAMILY_ROOTS.get(loader);
        if (root == null) root = loader;
        for (Map.Entry<ClassLoader, ClassLoader> entry : FAMILY_ROOTS.entrySet()) {
            if (entry.getValue() == root) {
                CACHE.remove(entry.getKey());
                FAMILY_ROOTS.remove(entry.getKey(), entry.getValue());
            }
        }
        CACHE.remove(loader);
        FAMILY_ROOTS.remove(loader);
    }

    private static <T> T newInstance(ClassLoader loader, String className, Class<T> expected)
            throws Exception {
        Class<?> type = GuestDefiningLoader.loadComponent(loader, className);
        if (!expected.isAssignableFrom(type)) {
            throw new IllegalArgumentException(expected.getSimpleName() + " class has wrong type: "
                    + className);
        }
        return expected.cast(type.getDeclaredConstructor().newInstance());
    }
}
