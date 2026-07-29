package com.warden.controlledsandbox;

import android.os.Parcel;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import java.util.Arrays;

public final class NativeCompanionContractSelfTest {
    private NativeCompanionContractSelfTest() { }

    public static void main(String[] args) {
        byte[] nonce = new byte[32];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) i;
        NativeCompanionRequest request = new NativeCompanionRequest(
                1, "session-32", 7L, 3, "com.example.fixture", "revision-7",
                nonce, "armeabi-v7a", NativeCompanionRequest.OP_PREPARE_GENERATION);
        nonce[0] = 99;
        require(request.capabilityNonce()[0] == 0, "request did not defensively copy nonce");

        Parcel requestParcel = Parcel.obtain();
        request.writeToParcel(requestParcel, 0);
        requestParcel.setDataPosition(0);
        NativeCompanionRequest restoredRequest = NativeCompanionRequest.CREATOR.createFromParcel(requestParcel);
        requestParcel.recycle();
        require(request.equals(restoredRequest), "request Parcelable round-trip failed");
        require(Arrays.equals(request.capabilityNonce(), restoredRequest.capabilityNonce()), "nonce lost");

        NativeCompanionResult result = NativeCompanionResult.success(request, "bitness=32;abi=armeabi-v7a");
        Parcel resultParcel = Parcel.obtain();
        result.writeToParcel(resultParcel, 0);
        resultParcel.setDataPosition(0);
        NativeCompanionResult restoredResult = NativeCompanionResult.CREATOR.createFromParcel(resultParcel);
        resultParcel.recycle();
        require(restoredResult.successful(), "result success lost");
        require(restoredResult.processBitness() == 32, "result bitness lost");
        require(restoredResult.acceptedGeneration() == 7L, "generation lost");

        require(NativeAbiRoutePlanner.route("") == NativeAbiRoutePlanner.Route.HOST_64,
                "Java-only package must stay on Host");
        require(NativeAbiRoutePlanner.route("arm64-v8a") == NativeAbiRoutePlanner.Route.HOST_64,
                "arm64 route incorrect");
        require(NativeAbiRoutePlanner.route("x86_64") == NativeAbiRoutePlanner.Route.HOST_64,
                "x86_64 route incorrect");
        require(NativeAbiRoutePlanner.route("armeabi-v7a") == NativeAbiRoutePlanner.Route.COMPANION_32,
                "armeabi-v7a route incorrect");
        require(NativeAbiRoutePlanner.route("x86") == NativeAbiRoutePlanner.Route.COMPANION_32,
                "x86 route incorrect");
        expectFailure(() -> NativeAbiRoutePlanner.route("legacy-unknown"), "legacy ABI accepted");
        expectFailure(() -> NativeAbiRoutePlanner.route("mips"), "unsupported ABI accepted");
        expectFailure(() -> new NativeCompanionRequest(1, "s", 1L, 0, "p", "r",
                new byte[8], "x86", NativeCompanionRequest.OP_PROBE), "short nonce accepted");
        expectFailure(() -> new NativeCompanionRequest(1, "s", 1L, 0, "p", "r",
                new byte[32], "x86_64", NativeCompanionRequest.OP_PROBE), "64-bit companion ABI accepted");
        System.out.println("PASS native companion typed contract and ABI routing");
    }

    private static void expectFailure(Runnable action, String message) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { failed = true; }
        require(failed, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
