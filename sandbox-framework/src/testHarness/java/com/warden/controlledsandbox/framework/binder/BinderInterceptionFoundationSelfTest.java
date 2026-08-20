package com.warden.controlledsandbox.framework.binder;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic contract test for root, returned and callback Binder boundaries. */
public final class BinderInterceptionFoundationSelfTest {
    private BinderInterceptionFoundationSelfTest() { }

    public static void main(String[] args) throws Exception {
        testTransactionMetadataAndExceptionPropagation();
        testReturnedAndCallbackBinderWrapping();
        testDeathAndGenerationFence();
        System.out.println("PASS Binder interception foundation self-test");
    }

    private static void testTransactionMetadataAndExceptionPropagation() throws Exception {
        RecordingBinder delegate = new RecordingBinder("com.example.IService");
        List<String> order = new ArrayList<>();
        BinderIdentity identity = identity("session-a", 3L);
        BinderInterceptionFoundation foundation = BinderInterceptionFoundation
                .builder(delegate, identity)
                .serviceName("test")
                .interceptor((transaction, next) -> {
                    order.add(transaction.descriptor() + ":" + transaction.code()
                            + ":" + transaction.flags() + ":" + transaction.oneWay()
                            + ":" + transaction.identity().scopeKey());
                    transaction.input().setDataPosition(0);
                    return next.proceed();
                })
                .interceptor((transaction, next) -> {
                    order.add("second:" + transaction.expectsReply());
                    return next.proceed();
                })
                .build();

        Parcel input = Parcel.obtain();
        input.writeString("guest-request");
        Parcel reply = Parcel.obtain();
        boolean accepted = foundation.binder().transact(17, input, reply,
                BinderTransaction.FLAG_ONEWAY | 0x20);
        require(accepted, "delegate transaction result must be preserved");
        require(delegate.lastFlags == (BinderTransaction.FLAG_ONEWAY | 0x20),
                "Binder flags must reach the delegate unchanged");
        require(order.size() == 2 && order.get(0).startsWith("com.example.IService:17:33:true:"),
                "transaction metadata/order missing: " + order);
        require(order.get(1).equals("second:false"),
                "one-way transaction must not claim a reply");

        delegate.throwRemote = true;
        boolean propagated = false;
        try {
            foundation.binder().transact(18, input, null, 0);
        } catch (RemoteException expected) {
            propagated = "delegate-failure".equals(expected.getMessage());
        }
        require(propagated, "RemoteException must propagate through the substrate");
        foundation.close();
    }

    private static void testReturnedAndCallbackBinderWrapping() {
        RecordingBinder root = new RecordingBinder("com.example.IRoot");
        RecordingBinder returned = new RecordingBinder("com.example.IReturned");
        RecordingBinder callback = new RecordingBinder("com.example.ICallback");
        BinderInterceptionFoundation foundation = BinderInterceptionFoundation
                .builder(root, identity("session-b", 4L))
                .serviceName("root")
                .build();

        IBinder returnedBoundary = foundation.wrapBinder(returned, "nested");
        require(returnedBoundary != returned && BinderInterceptionFoundation.isBoundary(returnedBoundary),
                "returned Binder must cross a CAS boundary");
        require("com.example.IReturned".equals(returnedBoundary.getInterfaceDescriptor()),
                "returned Binder descriptor must be preserved");
        require(foundation.wrapBinder(returned, "nested") == returnedBoundary,
                "returned Binder wrapping must be identity-stable");

        IInterface callbackValue = new CallbackInterface(callback);
        Object callbackBoundary = foundation.wrapCallback(callbackValue, "listener");
        require(callbackBoundary instanceof ICallback,
                "callback interface type must be preserved");
        require(((ICallback) callbackBoundary).asBinder() != callback,
                "callback Binder must not escape to the Host");

        IReturned returnedValue = new ReturnedInterface(returned);
        IReturned returnedProjection = (IReturned) foundation.wrapReturned(returnedValue, "result");
        require(returnedProjection.asBinder() != returned,
                "returned IInterface.asBinder must be virtualized");
        require(returnedProjection.nested() != returned,
                "nested returned Binder must remain behind the boundary");
        foundation.close();
        require(!BinderInterceptionFoundation.isBoundary(returnedBoundary),
                "closing root must retire returned Binder leases");
    }

    private static void testDeathAndGenerationFence() throws Exception {
        RecordingBinder delegate = new RecordingBinder("com.example.IDeath");
        AtomicBoolean active = new AtomicBoolean(true);
        int[] deaths = {0};
        BinderInterceptionFoundation foundation = BinderInterceptionFoundation
                .builder(delegate, identity("session-c", 5L))
                .sessionFence(value -> active.get() && value.generation() == 5L)
                .build();
        IBinder boundary = foundation.binder();
        boundary.linkToDeath(() -> deaths[0]++, 0);
        delegate.die();
        require(deaths[0] == 1 && !boundary.isBinderAlive(),
                "delegate death must invalidate the root and notify once");
        boolean deadRejected = false;
        try {
            boundary.transact(1, Parcel.obtain(), null, 0);
        } catch (RemoteException expected) {
            deadRejected = true;
        }
        require(deadRejected, "stale Binder transact must fail closed");

        RecordingBinder fencedDelegate = new RecordingBinder("com.example.IFenced");
        BinderInterceptionFoundation fenced = BinderInterceptionFoundation
                .builder(fencedDelegate, identity("session-d", 6L))
                .sessionFence(value -> active.get() && value.generation() == 5L)
                .build();
        require(!fenced.binder().isBinderAlive(), "old generation must be fenced before transact");
        fenced.close();
    }

    private static BinderIdentity identity(String session, long generation) {
        return BinderIdentity.forGuest("guest.example", 12001, 7001, "guest.example", "tag",
                0, session, generation, "guest.example:remote");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    interface IReturned extends IInterface {
        IBinder nested();
    }

    interface ICallback extends IInterface { }

    private static final class ReturnedInterface implements IReturned {
        private final IBinder binder;
        ReturnedInterface(IBinder binder) { this.binder = binder; }
        @Override public IBinder asBinder() { return binder; }
        @Override public IBinder nested() { return binder; }
    }

    private static final class CallbackInterface implements ICallback {
        private final IBinder binder;
        CallbackInterface(IBinder binder) { this.binder = binder; }
        @Override public IBinder asBinder() { return binder; }
    }

    private static final class RecordingBinder implements IBinder {
        private final String descriptor;
        private IBinder.DeathRecipient deathRecipient;
        private boolean alive = true;
        private boolean throwRemote;
        private int lastFlags;

        RecordingBinder(String descriptor) { this.descriptor = descriptor; }

        @Override public String getInterfaceDescriptor() { return descriptor; }
        @Override public boolean isBinderAlive() { return alive; }
        @Override public boolean transact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (throwRemote) throw new RemoteException("delegate-failure");
            lastFlags = flags;
            if (reply != null) reply.writeString("reply:" + code);
            return true;
        }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) {
            deathRecipient = recipient;
        }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            if (deathRecipient != recipient) return false;
            deathRecipient = null;
            return true;
        }
        void die() {
            alive = false;
            DeathRecipient recipient = deathRecipient;
            deathRecipient = null;
            if (recipient != null) recipient.binderDied();
        }
    }
}
