package com.warden.controlledsandbox.framework.identity;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/** Version-tolerant factory for hidden/public SharedLibraryInfo constructor variants. */
final class SharedLibraryInfoFactory {
    private SharedLibraryInfoFactory() { }

    static Object create(VirtualPackageMetadata.SharedLibrary library) {
        try {
            Class<?> type = Class.forName("android.content.pm.SharedLibraryInfo");
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Object[] arguments = arguments(constructor.getParameterTypes(), library);
                if (arguments == null) continue;
                try {
                    constructor.setAccessible(true);
                    Object value = constructor.newInstance(arguments);
                    setField(value, "mName", library.name());
                    setField(value, "name", library.name());
                    setField(value, "mVersion", library.version() > 0 ? library.version() : -1L);
                    setField(value, "version", library.version() > 0 ? library.version() : -1L);
                    return value;
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        } catch (ClassNotFoundException ignored) { }
        return null;
    }

    private static Object[] arguments(Class<?>[] types,
                                      VirtualPackageMetadata.SharedLibrary library) {
        Object[] values = new Object[types.length];
        int stringIndex = 0;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (type == String.class) {
                if (stringIndex == 0) values[index] = null; // path
                else if (stringIndex == 1) values[index] = library.providerPackage();
                else values[index] = library.name();
                stringIndex++;
            } else if (type == long.class || type == Long.class) {
                values[index] = library.version() > 0 ? library.version() : -1L;
            } else if (type == int.class || type == Integer.class) {
                values[index] = libraryType(library.kind());
            } else if (type == boolean.class || type == Boolean.class) {
                values[index] = "NATIVE".equals(library.kind());
            } else if (List.class.isAssignableFrom(type)) {
                values[index] = new ArrayList<>();
            } else if (type.getName().equals("android.content.pm.VersionedPackage")) {
                values[index] = versionedPackage(type, library.providerPackage(), library.version());
            } else {
                values[index] = null;
            }
        }
        return values;
    }

    private static Object versionedPackage(Class<?> type, String packageName, long version) {
        if (packageName == null || packageName.isEmpty()) return null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            try {
                constructor.setAccessible(true);
                if (parameters.length == 2 && parameters[0] == String.class
                        && (parameters[1] == long.class || parameters[1] == Long.class)) {
                    return constructor.newInstance(packageName, version);
                }
                if (parameters.length == 2 && parameters[0] == String.class
                        && (parameters[1] == int.class || parameters[1] == Integer.class)) {
                    return constructor.newInstance(packageName,
                            version > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) version);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return null;
    }

    private static int libraryType(String kind) {
        if ("STATIC".equals(kind)) return 2;
        if ("SDK".equals(kind)) return 3;
        return 0;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
