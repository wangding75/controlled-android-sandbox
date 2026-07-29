package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;
import java.util.ArrayList;
import java.util.List;

/** Runtime adapter between routing persistence port and Binder-backed system-service state. */
final class PendingIntentPersistenceAdapter implements VirtualPendingIntentRegistry.Persistence {
    private final VirtualSystemServiceState.PendingIntentState state;

    PendingIntentPersistenceAdapter(VirtualSystemServiceState.PendingIntentState state) {
        this.state = java.util.Objects.requireNonNull(state, "state");
    }

    @Override public VirtualPendingIntentRegistry.DurableRecord reserve(
            VirtualPendingIntentRegistry.DurableRecord candidate, boolean noCreate,
            boolean cancelCurrent, boolean updateCurrent) {
        VirtualSystemServiceAuthority.PendingIntentRecord result = state.reserve(
                toAuthority(candidate), noCreate, cancelCurrent, updateCurrent);
        return result == null ? null : fromAuthority(result);
    }

    @Override public VirtualPendingIntentRegistry.DurableRecord markSent(String tokenId) {
        VirtualSystemServiceAuthority.PendingIntentRecord result = state.markSent(tokenId);
        return result == null ? null : fromAuthority(result);
    }

    @Override public boolean cancel(String tokenId) { return state.cancel(tokenId); }

    @Override public List<VirtualPendingIntentRegistry.DurableRecord> records() {
        List<VirtualPendingIntentRegistry.DurableRecord> out = new ArrayList<>();
        for (VirtualSystemServiceAuthority.PendingIntentRecord value : state.records()) {
            out.add(fromAuthority(value));
        }
        return List.copyOf(out);
    }

    private static VirtualSystemServiceAuthority.PendingIntentRecord toAuthority(
            VirtualPendingIntentRegistry.DurableRecord value) {
        return new VirtualSystemServiceAuthority.PendingIntentRecord(value.tokenId(), value.kind(),
                value.requestCode(), value.action(), value.component(), value.data(),
                value.filterIdentity(), value.flags(), value.creatorPackage(), value.creatorUid(),
                value.requiredPermission(), value.ownerProcessName(), value.ownerGeneration(),
                value.packageRevision(), value.payload(), value.sends(), value.cancelled(),
                value.updatedAtMs());
    }

    private static VirtualPendingIntentRegistry.DurableRecord fromAuthority(
            VirtualSystemServiceAuthority.PendingIntentRecord value) {
        return new VirtualPendingIntentRegistry.DurableRecord(value.tokenId(), value.kind(),
                value.requestCode(), value.action(), value.component(), value.data(),
                value.filterIdentity(), value.flags(), value.creatorPackage(), value.creatorUid(),
                value.requiredPermission(), value.ownerProcessName(), value.ownerGeneration(),
                value.packageRevision(), value.payload(), value.sends(), value.cancelled(),
                value.updatedAtMs());
    }
}
