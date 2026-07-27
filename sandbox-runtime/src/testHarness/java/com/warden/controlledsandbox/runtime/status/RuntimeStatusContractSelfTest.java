package com.warden.controlledsandbox.runtime.status;

import android.os.Bundle;
import android.os.Parcel;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeStatusSnapshot;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

public final class RuntimeStatusContractSelfTest {
    public static void main(String[] args) {
        requestParcelableRoundTrip();
        malformedParcelableRejected();
        protocolValidation();
        resultInvariants();
        legacyAdapter();
        System.out.println("PASS typed runtime-status contract");
    }

    private static void requestParcelableRoundTrip() {
        RuntimeStatusRequest request = new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "request-1");
        Parcel parcel = Parcel.obtain();
        request.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        RuntimeStatusRequest restored = RuntimeStatusRequest.CREATOR.createFromParcel(parcel);
        require(restored.protocolVersion() == RuntimeProtocol.CURRENT, "protocol round trip");
        require("request-1".equals(restored.requestId()), "request id round trip");
        parcel.recycle();
    }

    private static void malformedParcelableRejected() {
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(RuntimeProtocol.CURRENT);
        parcel.writeString("   ");
        parcel.setDataPosition(0);
        expectThrows(IllegalArgumentException.class,
                () -> RuntimeStatusRequest.CREATOR.createFromParcel(parcel),
                "blank requestId from Parcel must fail");
        parcel.recycle();
        expectThrows(IllegalArgumentException.class,
                () -> new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "x".repeat(129)),
                "oversized requestId must fail");
    }

    private static void protocolValidation() {
        require(RuntimeStatusContract.validate(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "valid")) == null,
                "current protocol must pass");
        SandboxError missing = RuntimeStatusContract.validate(null);
        require("INVALID_REQUEST".equals(missing.code()), "null request error");
        SandboxError unsupported = RuntimeStatusContract.validate(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT + 1, "future"));
        require("UNSUPPORTED_PROTOCOL".equals(unsupported.code()), "future protocol error");
        RuntimeStatusResult failed = RuntimeStatusContract.failure(null, missing);
        require(!failed.successful(), "failure result");
        require("invalid-runtime-status-request".equals(failed.requestId()), "failure request id");
        RuntimeStatusResult internal = RuntimeStatusContract.internalFailure(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, "internal"),
                new IllegalStateException("x".repeat(700)));
        require(internal.error().message().length() == 512, "internal errors must be bounded");
    }

    private static void resultInvariants() {
        RuntimeStatusResult result = successResult();
        require(result.successful(), "success result");
        require(result.error() == null, "success error absent");
        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        RuntimeStatusResult restored = RuntimeStatusResult.CREATOR.createFromParcel(parcel);
        require(restored.snapshot().providerResourceCount() == 5, "result parcel count");
        require(restored.snapshot().providerAuditSuccessCount() == 8L, "result parcel audit");
        require(restored.snapshot().receiverResourceCount() == 18, "receiver resource parcel count");
        require(restored.snapshot().orderedReceiverPendingCount() == 2, "ordered Receiver parcel count");
        parcel.recycle();
        expectThrows(IllegalArgumentException.class,
                () -> RuntimeStatusResult.success(RuntimeProtocol.CURRENT, "bad", "", "", "", null),
                "success without snapshot must fail");
        expectThrows(IllegalArgumentException.class,
                () -> RuntimeStatusSnapshot.builder().slots(1, 2).build(),
                "used slots beyond capacity must fail");
        expectThrows(IllegalArgumentException.class,
                () -> RuntimeStatusSnapshot.builder()
                        .providerResources(1, 1, 1, 1, 1, 4)
                        .build(),
                "provider total mismatch must fail");
        expectThrows(IllegalArgumentException.class,
                () -> RuntimeStatusSnapshot.builder()
                        .receiverResources(1, 1, 1, 1, 1, 1, 1, 1, 1, 8)
                        .build(),
                "Receiver total mismatch must fail");
    }

    private static void legacyAdapter() {
        RuntimeStatusResult result = successResult();
        Bundle legacy = RuntimeStatusLegacyAdapter.toBundle(result);
        require("READY".equals(legacy.getString(RuntimeKeys.STATUS)), "legacy status");
        require(legacy.getInt("providerResourceCount", -1) == 5, "legacy provider count");
        require(legacy.getLong("providerAuditSuccessCount", -1L) == 8L, "legacy audit count");
        require(legacy.getInt("receiverResourceCount", -1) == 18, "legacy Receiver total");
        require(legacy.getInt("orderedReceiverPendingCount", -1) == 2,
                "legacy ordered Receiver count");

        RuntimeStatusResult failed = RuntimeStatusResult.failure(RuntimeProtocol.CURRENT, "failed",
                new SandboxError("UNSUPPORTED_PROTOCOL", "unsupported", false));
        Bundle failedLegacy = RuntimeStatusLegacyAdapter.toBundle(failed);
        require("UNSUPPORTED_PROTOCOL".equals(failedLegacy.getString(RuntimeKeys.ERROR_TYPE)),
                "legacy error type");
    }

    private static RuntimeStatusResult successResult() {
        RuntimeStatusSnapshot snapshot = RuntimeStatusSnapshot.builder()
                .slots(8, 2)
                .sessions(2)
                .activity(1, 3, 4)
                .services(5)
                .providerResources(1, 1, 1, 1, 1, 5)
                .providerAudit(7, 8L, 9L)
                .receiverResources(2, 3, 1, 3, 1, 2, 3, 1, 2, 18)
                .build();
        return RuntimeStatusResult.success(RuntimeProtocol.CURRENT, "result-1",
                "READY", "CAPABILITY", "warning", snapshot);
    }

    private static void expectThrows(Class<? extends Throwable> type, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError(message + ": wrong exception " + error, error);
        }
        throw new AssertionError(message + ": no exception");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private RuntimeStatusContractSelfTest() { }
}
