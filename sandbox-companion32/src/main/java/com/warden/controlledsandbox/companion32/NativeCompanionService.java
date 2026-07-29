package com.warden.controlledsandbox.companion32;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.INativeAbiCompanion;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Signature-permission protected 32-bit companion endpoint. */
public final class NativeCompanionService extends Service {
    private static final int PROTOCOL = 1;
    private static final int MAX_NONCES = 256;
    private final Map<String, Boolean> consumedNonces = new LinkedHashMap<>();
    private final NativeCompanionGenerationRegistry generations =
            new NativeCompanionGenerationRegistry(256);

    private final INativeAbiCompanion.Stub binder = new INativeAbiCompanion.Stub() {
        @Override public NativeCompanionResult execute(NativeCompanionRequest request) {
            if (request == null) return NativeCompanionResult.failure("", "", "REQUEST_REQUIRED", "request is required");
            if (request.protocol() != PROTOCOL) {
                return NativeCompanionResult.failure(request.operation(), request.requestedAbi(),
                        "PROTOCOL_MISMATCH", "unsupported protocol " + request.protocol());
            }
            if (!consume(request.capabilityNonce())) {
                return NativeCompanionResult.failure(request.operation(), request.requestedAbi(),
                        "CAPABILITY_NONCE_REPLAYED", "capability nonce has already been consumed");
            }
            if (!NativeCompanionBridge.available() || NativeCompanionBridge.processBitness() != 32) {
                return NativeCompanionResult.failure(request.operation(), request.requestedAbi(),
                        "NATIVE_COMPANION_NOT_32_BIT", NativeCompanionBridge.status());
            }
            if (!abiMatches(request.requestedAbi())) {
                return NativeCompanionResult.failure(request.operation(), request.requestedAbi(),
                        "ABI_MISMATCH", NativeCompanionBridge.status());
            }
            String generationError = generations.accept(request);
            if (!generationError.isEmpty()) {
                return NativeCompanionResult.failure(request.operation(), request.requestedAbi(),
                        generationError, "stale or conflicting companion generation");
            }
            return NativeCompanionResult.success(request, NativeCompanionBridge.status());
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    private synchronized boolean consume(byte[] nonce) {
        String key = hex(nonce);
        if (consumedNonces.containsKey(key)) return false;
        trimOldest(consumedNonces, MAX_NONCES);
        consumedNonces.put(key, Boolean.TRUE);
        return true;
    }

    private static <T> void trimOldest(Map<String, T> values, int maximum) {
        if (values.size() < maximum) return;
        String first = values.keySet().iterator().next();
        values.remove(first);
    }

    private static boolean abiMatches(String requestedAbi) {
        String status = NativeCompanionBridge.status();
        return status.contains("abi=" + requestedAbi);
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return out.toString();
    }

}
