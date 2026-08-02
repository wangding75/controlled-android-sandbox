package com.warden.controlledsandbox;

import android.os.Parcel;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactResult;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import com.warden.controlledsandbox.contract.NativeCompanionIdentity;
import com.warden.controlledsandbox.contract.ControlledReleaseIdentity;
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

        NativeCompanionIdentity identity = NativeCompanionIdentity.current();
        Parcel identityParcel = Parcel.obtain();
        identity.writeToParcel(identityParcel, 0);
        identityParcel.setDataPosition(0);
        NativeCompanionIdentity restoredIdentity =
                NativeCompanionIdentity.CREATOR.createFromParcel(identityParcel);
        identityParcel.recycle();
        require(ControlledReleaseIdentity.RELEASE_TRAIN.equals(restoredIdentity.releaseTrain()),
                "companion release train lost");
        require(restoredIdentity.versionCode() == ControlledReleaseIdentity.VERSION_CODE,
                "companion version code lost");
        require(restoredIdentity.supportsProtocol(ControlledReleaseIdentity.COMPANION_PROTOCOL),
                "companion protocol range lost");

        NativeCompanionResult result = NativeCompanionResult.success(request, "bitness=32;abi=armeabi-v7a");
        Parcel resultParcel = Parcel.obtain();
        result.writeToParcel(resultParcel, 0);
        resultParcel.setDataPosition(0);
        NativeCompanionResult restoredResult = NativeCompanionResult.CREATOR.createFromParcel(resultParcel);
        resultParcel.recycle();
        require(restoredResult.successful(), "result success lost");
        require(restoredResult.processBitness() == 32, "result bitness lost");
        require(restoredResult.acceptedGeneration() == 7L, "generation lost");


        NativeCompanionArtifactRequest artifact = new NativeCompanionArtifactRequest(
                1, "transfer", 1L, 3, "com.example.fixture", "revision-7", "x86",
                NativeCompanionArtifactRequest.SPLIT_APK, "splits/config.en.apk",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                4096L);
        Parcel artifactParcel = Parcel.obtain();
        artifact.writeToParcel(artifactParcel, 0);
        artifactParcel.setDataPosition(0);
        NativeCompanionArtifactRequest restoredArtifact =
                NativeCompanionArtifactRequest.CREATOR.createFromParcel(artifactParcel);
        artifactParcel.recycle();
        require(restoredArtifact.sizeBytes() == 4096L, "artifact size lost");
        require("splits/config.en.apk".equals(restoredArtifact.relativePath()),
                "artifact path lost");
        NativeCompanionArtifactResult artifactResult = NativeCompanionArtifactResult.success(
                "STAGE_ARTIFACT", NativeCompanionArtifactRequest.SPLIT_APK,
                artifact.relativePath(), "/workspace/splits/config.en.apk", "/workspace",
                "/workspace/data", "/workspace/lib");
        Parcel artifactResultParcel = Parcel.obtain();
        artifactResult.writeToParcel(artifactResultParcel, 0);
        artifactResultParcel.setDataPosition(0);
        NativeCompanionArtifactResult restoredArtifactResult =
                NativeCompanionArtifactResult.CREATOR.createFromParcel(artifactResultParcel);
        artifactResultParcel.recycle();
        require(restoredArtifactResult.successful(), "artifact result success lost");
        require("/workspace/lib".equals(restoredArtifactResult.nativeLibraryRoot()),
                "artifact native root lost");

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
        expectFailure(() -> new NativeCompanionArtifactRequest(1, "s", 1L, 0,
                "com.example.fixture", "r", "x86",
                NativeCompanionArtifactRequest.SPLIT_APK, "../escape.apk",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 1L),
                "artifact traversal accepted");
        expectFailure(() -> new NativeCompanionArtifactRequest(1, "s", 1L, 0,
                "com.example.fixture", "r", "x86_64",
                NativeCompanionArtifactRequest.BASE_APK, "base.apk",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 1L),
                "64-bit artifact ABI accepted");

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
