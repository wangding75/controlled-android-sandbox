package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;

import com.warden.controlledsandbox.contract.IRuntimeStorage;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.io.IOException;

/** Host-owned storage transport used when an isolated UID cannot traverse the data label. */
final class GuestStorageBroker {
    private GuestStorageBroker() { }

    static SandboxSharedPreferences.RemoteBackend preferences(GuestPackageSpec spec,
                                                                String physicalName,
                                                                boolean deviceProtected) {
        if (spec == null || spec.runtimeStorageBinder == null) {
            throw new IllegalStateException("ISOLATED_STORAGE_BROKER_MISSING");
        }
        if (physicalName == null || physicalName.isEmpty()
                || physicalName.contains("/") || physicalName.contains("\\")) {
            throw new IllegalArgumentException("ISOLATED_STORAGE_NAME_INVALID");
        }
        return new PreferencesBackend(spec, physicalName, deviceProtected);
    }

    static boolean move(GuestPackageSpec spec, String sourceName, boolean sourceDeviceProtected,
                        String targetName, boolean targetDeviceProtected) {
        if (spec == null || spec.runtimeStorageBinder == null) {
            throw new IllegalStateException("ISOLATED_STORAGE_BROKER_MISSING");
        }
        try {
            IRuntimeStorage storage = IRuntimeStorage.Stub.asInterface(spec.runtimeStorageBinder);
            if (storage == null) throw new IllegalStateException("ISOLATED_STORAGE_BROKER_MISSING");
            Bundle result = storage.move(spec.sessionId, spec.generation, spec.packageName,
                    spec.virtualUserId, sourceName, sourceDeviceProtected, targetName,
                    targetDeviceProtected);
            if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                throw new IllegalStateException("ISOLATED_STORAGE_MOVE_FAILED:"
                        + result.getString(RuntimeKeys.ERROR_MESSAGE, ""));
            }
            return result.getBoolean(RuntimeKeys.STORAGE_SUCCESS, false);
        } catch (Exception error) {
            android.util.Log.e("CS_STORAGE", "isolated storage move failed", error);
            throw new IllegalStateException("ISOLATED_STORAGE_MOVE_FAILED", error);
        }
    }

    private static final class PreferencesBackend implements SandboxSharedPreferences.RemoteBackend {
        private final GuestPackageSpec spec;
        private final String physicalName;
        private final boolean deviceProtected;
        private final IRuntimeStorage storage;

        PreferencesBackend(GuestPackageSpec spec, String physicalName,
                           boolean deviceProtected) {
            this.spec = spec;
            this.physicalName = physicalName;
            this.deviceProtected = deviceProtected;
            this.storage = IRuntimeStorage.Stub.asInterface(spec.runtimeStorageBinder);
            if (storage == null) throw new IllegalStateException("ISOLATED_STORAGE_BROKER_MISSING");
        }

        @Override public byte[] read() throws IOException {
            Bundle result = execute("read", null);
            return result.getBoolean(RuntimeKeys.STORAGE_EXISTS, false)
                    ? result.getByteArray(RuntimeKeys.STORAGE_DATA) : null;
        }

        @Override public boolean write(byte[] bytes) {
            Bundle result = execute("write", bytes);
            return result.getBoolean(RuntimeKeys.STORAGE_SUCCESS, false);
        }

        @Override public boolean delete() {
            Bundle result = execute("delete", null);
            return result.getBoolean(RuntimeKeys.STORAGE_SUCCESS, false);
        }

        private Bundle execute(String operation, byte[] bytes) {
            try {
                Bundle result = storage.execute(operation, spec.sessionId, spec.generation,
                        spec.packageName, spec.virtualUserId, physicalName, deviceProtected, bytes);
                if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                    throw new IllegalStateException("ISOLATED_STORAGE_OPERATION_FAILED:"
                            + result.getString(RuntimeKeys.ERROR_MESSAGE, ""));
                }
                return result;
            } catch (Exception error) {
                android.util.Log.e("CS_STORAGE", "isolated storage " + operation
                        + " failed name=" + physicalName, error);
                throw new IllegalStateException("ISOLATED_STORAGE_OPERATION_FAILED", error);
            }
        }
    }
}
