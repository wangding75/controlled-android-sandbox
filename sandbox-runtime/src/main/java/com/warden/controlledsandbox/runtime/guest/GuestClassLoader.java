package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import dalvik.system.DexClassLoader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Child-first loader for Guest code with explicit platform sharing and policy-driven host-internal denial. */
public final class GuestClassLoader extends DexClassLoader {
    private volatile List<String> hiddenClassPrefixes = List.of();
    private volatile int maximumSuspiciousQueries;
    private final AtomicInteger suspiciousQueries = new AtomicInteger();

    GuestClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath,
                     ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }

    void configureDetection(VirtualDetectionPolicySnapshot policy) {
        if (policy == null || VirtualLocationProfileSnapshot.MODE_HOST.equals(policy.mode())) {
            hiddenClassPrefixes = List.of();
            maximumSuspiciousQueries = 0;
            suspiciousQueries.set(0);
            return;
        }
        hiddenClassPrefixes = List.copyOf(policy.hiddenClassPrefixes());
        maximumSuspiciousQueries = policy.maximumSuspiciousQueries();
        suspiciousQueries.set(0);
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if (isDeniedSandboxInternal(name) || isPrivilegedContract(name)) {
                throw new ClassNotFoundException("Sandbox privileged implementation is not a Guest API: " + name);
            }
            if (isPolicyHidden(name)) {
                throw new ClassNotFoundException("Class is hidden by Guest detection policy: " + name);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    loaded = getParent().loadClass(name);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException guestMiss) {
                        loaded = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    private boolean isPolicyHidden(String name) throws ClassNotFoundException {
        if (name == null || isParentFirst(name)) return false;
        for (String prefix : hiddenClassPrefixes) {
            if (!name.startsWith(prefix)) continue;
            int count = suspiciousQueries.incrementAndGet();
            if (maximumSuspiciousQueries > 0 && count > maximumSuspiciousQueries) {
                throw new ClassNotFoundException("Virtual-environment query quota exceeded");
            }
            return true;
        }
        return false;
    }

    int suspiciousQueryCount() { return suspiciousQueries.get(); }

    static boolean isDeniedSandboxInternal(String name) {
        return name != null
                && name.startsWith("com.warden.controlledsandbox.")
                && !name.startsWith("com.warden.controlledsandbox.contract.");
    }

    static boolean isPrivilegedContract(String name) {
        if (name == null) return false;
        return name.equals("com.warden.controlledsandbox.contract.IPackageService")
                || name.startsWith("com.warden.controlledsandbox.contract.IPackageService$")
                || name.equals("com.warden.controlledsandbox.contract.IPackageManagementSession")
                || name.startsWith("com.warden.controlledsandbox.contract.IPackageManagementSession$")
                || name.equals("com.warden.controlledsandbox.contract.IRuntimePermissionSession")
                || name.startsWith("com.warden.controlledsandbox.contract.IRuntimePermissionSession$")
                || name.equals("com.warden.controlledsandbox.contract.IVirtualSystemServiceSession")
                || name.startsWith("com.warden.controlledsandbox.contract.IVirtualSystemServiceSession$")
                || name.equals("com.warden.controlledsandbox.contract.IHostJobCallback")
                || name.startsWith("com.warden.controlledsandbox.contract.IHostJobCallback$")
                || name.equals("com.warden.controlledsandbox.contract.PackageAuthorityCapabilityContract");
    }

    static boolean isParentFirst(String name) {
        if (name == null) return true;
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("kotlin.") || name.startsWith("dalvik.")
                || name.startsWith("sun.") || name.startsWith("com.android.")
                || name.startsWith("com.warden.controlledsandbox.contract.");
    }
}
