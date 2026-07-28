package com.warden.controlledsandbox.runtime.component.activity;

import android.os.Parcel;
import com.warden.controlledsandbox.contract.ActivityResultIntentSnapshot;
import com.warden.controlledsandbox.contract.ActivityResultRequest;
import com.warden.controlledsandbox.contract.ActivityResultResult;
import com.warden.controlledsandbox.contract.ActivityResultSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import java.util.List;
import java.util.Map;

/** Parcelable and invariant checks for the typed M4-T15 Activity Result contract. */
public final class ActivityResultContractSelfTest {
    private ActivityResultContractSelfTest() { }

    public static void main(String[] args) {
        ActivityResultIntentSnapshot intent = ActivityResultIntentSnapshot.fromMap(
                "guest.RESULT", "content://guest/42", "text/plain",
                "guest.example/Caller", 7, "clip", Map.of("name", "Ada"));
        ActivityResultRequest request = roundTripRequest(new ActivityResultRequest(
                RuntimeProtocol.CURRENT, "result-request-1", "session-1", 4, 2,
                "guest.example", ActivityResultRequest.FINISH, "activity-1", "",
                -1, intent));
        check(request.resultIntent().extras().get("name").equals("Ada"),
                "typed Result Intent extras changed during parceling");

        ActivityResultSnapshot delivery = new ActivityResultSnapshot(
                "caller-1", "callee-1", "fragment:main", "registry-key", 12, -1,
                "sender-token", "route-token", intent);
        ActivityResultResult result = roundTripResult(ActivityResultResult.success(
                RuntimeProtocol.CURRENT, "result-request-2", ActivityResultRequest.DRAIN,
                true, -1, List.of(delivery)));
        check(result.successful() && result.results().size() == 1,
                "typed Activity result delivery was lost");
        check(result.results().get(0).registryKey().equals("registry-key")
                        && result.results().get(0).intentSenderToken().equals("sender-token"),
                "result ownership metadata changed during parceling");

        expectFailure(() -> new ActivityResultRequest(
                RuntimeProtocol.CURRENT, "bad", "session-1", 4, 2, "guest.example",
                ActivityResultRequest.REGISTER, "activity-1", "", 0,
                ActivityResultIntentSnapshot.empty()),
                "registration without key must fail");
        expectFailure(() -> ActivityResultIntentSnapshot.fromMap(
                "", "", "", "", 0, "", java.util.stream.IntStream.range(0, 65)
                        .boxed().collect(java.util.stream.Collectors.toMap(
                                value -> "k" + value, value -> "v" + value))),
                "oversized Result Intent extras must fail");
        System.out.println("PASS typed Activity result Binder contract self-test");
    }

    private static ActivityResultRequest roundTripRequest(ActivityResultRequest value) {
        Parcel parcel = Parcel.obtain();
        value.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ActivityResultRequest restored = ActivityResultRequest.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return restored;
    }

    private static ActivityResultResult roundTripResult(ActivityResultResult value) {
        Parcel parcel = Parcel.obtain();
        value.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ActivityResultResult restored = ActivityResultResult.CREATOR.createFromParcel(parcel);
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
