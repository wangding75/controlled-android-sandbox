package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Parcel;
import com.warden.controlledsandbox.contract.ActivityTaskRequest;
import com.warden.controlledsandbox.contract.ActivityTaskResult;
import com.warden.controlledsandbox.contract.ActivityTaskSnapshot;
import com.warden.controlledsandbox.contract.SandboxError;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import java.util.List;

/** Parcelable and invariant checks for the typed M4-T15 Activity/Task contract. */
public final class ActivityTaskContractSelfTest {
    private ActivityTaskContractSelfTest() { }

    public static void main(String[] args) {
        ActivityTaskRequest request = roundTripRequest(new ActivityTaskRequest(
                RuntimeProtocol.CURRENT, "req-1", "session-1", 4, 2, "com.example",
                ActivityTaskRequest.QUERY_RECENT, 0, 25));
        check(request.protocolVersion() == RuntimeProtocol.CURRENT, "request protocol changed");
        check(ActivityTaskRequest.QUERY_RECENT.equals(request.operation()), "request operation changed");
        check(request.maxCount() == 25, "request query bound changed");

        ActivityTaskSnapshot task = new ActivityTaskSnapshot(
                7, 2, "com.example", "com.example.task", true, false,
                false, true, 1, "com.example.Root", "com.example.Top", 12, 3);
        ActivityTaskResult result = roundTripResult(ActivityTaskResult.success(
                RuntimeProtocol.CURRENT, "req-1", ActivityTaskRequest.QUERY_RECENT,
                false, "RESTORED", 1, 1, 1, 1, 2, List.of(task)));
        check(result.successful() && result.tasks().size() == 1, "typed task result lost payload");
        check(result.tasks().get(0).taskId() == 7, "typed task projection changed identity");
        check(result.droppedDeliveryCount() == 2, "restore metadata changed");

        ActivityTaskResult failure = roundTripResult(ActivityTaskResult.failure(
                RuntimeProtocol.CURRENT, "req-2",
                new SandboxError("ACTIVITY_TASK_FORBIDDEN", "owner mismatch", false)));
        check(!failure.successful() && failure.error() != null,
                "typed task failure lost stable error");

        expectFailure(() -> new ActivityTaskRequest(
                RuntimeProtocol.CURRENT, "bad", "session-1", 4, 2, "com.example",
                ActivityTaskRequest.MOVE_TO_FRONT, 0, 0),
                "task mutation without taskId must fail");
        System.out.println("PASS typed Activity task Binder contract self-test");
    }

    private static ActivityTaskRequest roundTripRequest(ActivityTaskRequest value) {
        Parcel parcel = Parcel.obtain();
        value.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ActivityTaskRequest restored = ActivityTaskRequest.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return restored;
    }

    private static ActivityTaskResult roundTripResult(ActivityTaskResult value) {
        Parcel parcel = Parcel.obtain();
        value.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ActivityTaskResult restored = ActivityTaskResult.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return restored;
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
