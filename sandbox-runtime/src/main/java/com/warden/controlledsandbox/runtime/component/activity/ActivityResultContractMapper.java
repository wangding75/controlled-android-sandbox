package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.contract.ActivityResultIntentSnapshot;
import com.warden.controlledsandbox.contract.ActivityResultSnapshot;
import com.warden.controlledsandbox.framework.activity.ActivityResultDelivery;
import com.warden.controlledsandbox.framework.activity.ResultIntentSnapshot;
import java.util.ArrayList;

/** Typed mapping between Binder Activity Result contracts and framework-independent state. */
final class ActivityResultContractMapper {
    private ActivityResultContractMapper() { }

    static ResultIntentSnapshot toFramework(ActivityResultIntentSnapshot source) {
        if (source == null) return ResultIntentSnapshot.EMPTY;
        return new ResultIntentSnapshot(
                source.action(), source.dataUri(), source.mimeType(), source.componentName(),
                source.flags(), source.clipDescription(), source.extras());
    }

    static ActivityResultSnapshot toContract(ActivityResultDelivery source) {
        ResultIntentSnapshot intent = source.resultIntent();
        ActivityResultIntentSnapshot contractIntent = ActivityResultIntentSnapshot.fromMap(
                intent.action(), intent.dataUri(), intent.mimeType(), intent.componentName(),
                intent.flags(), intent.clipDescription(), intent.extras());
        return new ActivityResultSnapshot(
                source.callerActivityToken(), source.calleeActivityToken(), source.resultWho(),
                source.registryKey(), source.requestCode(), source.resultCode(),
                source.intentSenderToken(), source.dataToken(), contractIntent);
    }
}
