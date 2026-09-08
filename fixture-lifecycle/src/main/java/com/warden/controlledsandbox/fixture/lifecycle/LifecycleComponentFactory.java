package com.warden.controlledsandbox.fixture.lifecycle;

import android.app.AppComponentFactory;
import android.app.Application;
import android.content.pm.ApplicationInfo;

/** Records the real AppComponentFactory calls without replacing the platform class loader. */
public final class LifecycleComponentFactory extends AppComponentFactory {
    public LifecycleComponentFactory() { LifecycleProbe.record("factory.construct"); }

    @Override public ClassLoader instantiateClassLoader(ClassLoader loader,
                                                         ApplicationInfo applicationInfo) {
        LifecycleProbe.record("factory.classLoader");
        return loader;
    }

    @Override public Application instantiateApplication(ClassLoader loader, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        LifecycleProbe.record("factory.application");
        return super.instantiateApplication(loader, className);
    }
}
