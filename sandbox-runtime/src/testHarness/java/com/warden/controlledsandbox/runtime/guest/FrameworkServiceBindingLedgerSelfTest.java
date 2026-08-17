package com.warden.controlledsandbox.runtime.guest;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage for per-Intent framework Service binding semantics. */
public final class FrameworkServiceBindingLedgerSelfTest {
    private FrameworkServiceBindingLedgerSelfTest() { }

    public static void main(String[] args) throws Exception {
        FrameworkServiceBindingLedger ledger = new FrameworkServiceBindingLedger();
        AtomicInteger binds = new AtomicInteger();
        IBinder first = new Binder();
        IBinder second = new Binder();
        Intent actionA = new Intent("com.example.ACTION_A").setPackage("com.example.service")
                .putExtra("ignored", "one");
        Intent actionAWithDifferentExtras = new Intent(actionA).putExtra("ignored", "two");
        Intent actionB = new Intent("com.example.ACTION_B").setPackage("com.example.service");

        FrameworkServiceBindingLedger.Entry a = ledger.bind(actionA, intent -> {
            require(binds.getAndIncrement() == 0, "first binder factory order");
            return first;
        });
        FrameworkServiceBindingLedger.Entry same = ledger.bind(actionAWithDifferentExtras,
                intent -> { throw new AssertionError("extras changed filter identity"); });
        require(a == same && a.binder() == first && a.bindCount() == 2 && ledger.size() == 1,
                "same filtered Intent must reuse one binding record, binder and client count");

        FrameworkServiceBindingLedger.Entry b = ledger.bind(actionB, intent -> {
            require(binds.getAndIncrement() == 1, "second binder factory order");
            return second;
        });
        require(b != a && b.binder() == second && ledger.size() == 2,
                "different filtered Intents require independent binding records");

        FrameworkServiceBindingLedger.UnbindResult intermediate =
                ledger.unbindAndReport(actionA, true);
        require(intermediate.found() && !intermediate.lastClient() && ledger.size() == 2
                        && a.bindCount() == 1,
                "first client unbind must not invoke Service.onUnbind or mark rebind");
        FrameworkServiceBindingLedger.UnbindResult finalUnbind =
                ledger.unbindAndReport(actionA, true);
        require(finalUnbind.found() && finalUnbind.lastClient() && finalUnbind.rebindPending(),
                "final client onUnbind(true) record was not retained");
        require(ledger.takePendingRebind(actionAWithDifferentExtras) == a,
                "rebind did not consume the matching Intent record");
        require(a.bindCount() == 1, "rebind did not restore one active client");
        require(ledger.unbind(actionA, false) && ledger.size() == 1,
                "onUnbind(false) did not retire the rebound Intent record");
        require(ledger.unbind(actionB, false) && ledger.size() == 0,
                "onUnbind(false) did not retire only its remaining Intent record");
        FrameworkServiceBindingLedger.UnbindResult unknown = ledger.unbindAndReport(
                new Intent("com.example.UNKNOWN"), false);
        require(!unknown.found() && !unknown.lastClient() && !unknown.rebindPending()
                        && ledger.size() == 0,
                "unknown unbind must be an acknowledged no-op and must not mutate the ledger");
        System.out.println("PASS framework service binding ledger self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
