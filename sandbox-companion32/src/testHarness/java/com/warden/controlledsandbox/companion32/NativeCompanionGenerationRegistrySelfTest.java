package com.warden.controlledsandbox.companion32;

import com.warden.controlledsandbox.contract.NativeCompanionRequest;

public final class NativeCompanionGenerationRegistrySelfTest {
    private NativeCompanionGenerationRegistrySelfTest() { }

    public static void main(String[] args) {
        NativeCompanionGenerationRegistry registry = new NativeCompanionGenerationRegistry(2);
        require("".equals(registry.accept(request("a", 2L, "rev-a", "x86", 0))), "initial generation rejected");
        require("STALE_GENERATION".equals(registry.accept(request("a", 1L, "rev-a", "x86", 0))),
                "stale generation accepted");
        require("GENERATION_IDENTITY_MISMATCH".equals(
                registry.accept(request("other", 2L, "rev-a", "x86", 0))),
                "same generation with different session accepted");
        require("".equals(registry.accept(request("a", 3L, "rev-b", "x86", 0))),
                "new generation rejected");
        require("".equals(registry.accept(request("b", 1L, "rev-b", "armeabi-v7a", 1))),
                "second package rejected");
        require("".equals(registry.accept(request("c", 1L, "rev-c", "x86", 2))),
                "bounded replacement rejected");
        require(registry.size() == 2, "generation registry exceeded capacity");
        NativeCompanionRequest probe = new NativeCompanionRequest(1, "probe", 1L, 0,
                "com.example.probe", "rev", nonce(8), "x86", NativeCompanionRequest.OP_PROBE);
        require("".equals(registry.accept(probe)) && registry.size() == 2,
                "probe mutated generation registry");
        System.out.println("PASS native companion generation ownership registry");
    }

    private static NativeCompanionRequest request(String session, long generation, String revision,
                                                  String abi, int user) {
        return new NativeCompanionRequest(1, session, generation, user, "com.example.fixture",
                revision, nonce((int) generation + user), abi,
                NativeCompanionRequest.OP_PREPARE_GENERATION);
    }

    private static byte[] nonce(int seed) {
        byte[] value = new byte[32];
        for (int i = 0; i < value.length; i++) value[i] = (byte) (seed + i);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
