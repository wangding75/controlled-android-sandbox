package com.warden.controlledsandbox.runtime.guest;

import android.content.Intent;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Framework-owned Service binding records keyed by Android's {@link Intent#filterEquals(Intent)}
 * contract.
 *
 * <p>ActivityThread receives one bind/unbind transaction per framework binding intent, while a
 * Service can be bound with more than one filtered intent.  A single {@code lastBinder} field
 * loses that distinction and makes {@code onUnbind(true)}/ {@code onRebind()} depend on whichever
 * intent happened to arrive last.  This ledger keeps the transport state separate from the Guest
 * Service object and deliberately ignores extras, matching the platform's binding identity.</p>
 */
final class FrameworkServiceBindingLedger {
    @FunctionalInterface
    interface BinderFactory {
        IBinder create(Intent intent) throws Exception;
    }

    static final class Entry {
        private final Intent intent;
        private final IBinder binder;
        /** Number of framework clients currently attached to this filtered interface. */
        private int bindCount = 1;
        private boolean rebindPending;

        private Entry(Intent intent, IBinder binder) {
            this.intent = new Intent(intent == null ? new Intent() : intent);
            this.binder = binder;
        }

        Intent intent() { return new Intent(intent); }
        IBinder binder() { return binder; }
        int bindCount() { return bindCount; }
        boolean rebindPending() { return rebindPending; }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Returns the record for a filtered binding intent, or {@code null}. */
    synchronized Entry find(Intent intent) {
        Intent candidate = normalize(intent);
        for (Entry entry : entries) {
            if (filterEquals(entry.intent, candidate)) return entry;
        }
        return null;
    }

    /**
     * Returns an existing record without invoking {@code factory}; otherwise creates one.
     * A normal bind clears a pending rebind marker because the framework supplied a fresh bind
     * transaction rather than the rebind transaction used after {@code onUnbind(true)}.
     */
    synchronized Entry bind(Intent intent, BinderFactory factory) throws Exception {
        if (factory == null) throw new IllegalArgumentException("binder factory is required");
        Intent candidate = normalize(intent);
        Entry existing = find(candidate);
        if (existing != null) {
            if (existing.bindCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("FRAMEWORK_SERVICE_BIND_COUNT_OVERFLOW");
            }
            existing.bindCount++;
            existing.rebindPending = false;
            return existing;
        }
        Entry created = new Entry(candidate, factory.create(new Intent(candidate)));
        entries.add(created);
        return created;
    }

    /** Consumes the pending rebind marker for one filtered intent. */
    synchronized Entry takePendingRebind(Intent intent) {
        Entry entry = find(intent);
        if (entry == null || !entry.rebindPending) return null;
        entry.bindCount = 1;
        entry.rebindPending = false;
        return entry;
    }

    /**
     * Removes one framework client and reports whether the callback is for the final client.
     *
     * <p>VA/NBB keep one {@code IntentBindRecord} per filtered interface and a separate
     * connection count.  Android may deliver an unbind transaction for each client on older
     * framework paths and OEM variants, even though {@link android.app.Service#onUnbind(Intent)}
     * is defined to run only after the final client leaves.  Keeping the count here makes both
     * delivery shapes converge on the platform contract.</p>
     */
    synchronized UnbindResult unbindAndReport(Intent intent, boolean rebind) {
        Entry entry = find(intent);
        if (entry == null) return UnbindResult.notFound();
        if (entry.bindCount > 1) {
            entry.bindCount--;
            return new UnbindResult(true, false, false);
        }
        entry.bindCount = 0;
        if (rebind) {
            entry.rebindPending = true;
            return new UnbindResult(true, true, true);
        }
        entries.remove(entry);
        return new UnbindResult(true, true, false);
    }

    /** Compatibility helper for source tests and callers that only need ownership status. */
    synchronized boolean unbind(Intent intent, boolean rebind) {
        return unbindAndReport(intent, rebind).found();
    }

    synchronized int size() { return entries.size(); }

    synchronized List<Entry> snapshot() { return List.copyOf(entries); }

    synchronized void clear() { entries.clear(); }

    record UnbindResult(boolean found, boolean lastClient, boolean rebindPending) {
        static UnbindResult notFound() { return new UnbindResult(false, false, false); }
    }

    private static Intent normalize(Intent intent) {
        return intent == null ? new Intent() : new Intent(intent);
    }

    /**
     * API 26+ exposes Intent.filterEquals(), while the compact source stubs used by the host
     * gate intentionally omit hidden/late framework helpers.  Prefer the framework contract on
     * device and keep a field-equivalent fallback for source/API compatibility.
     */
    private static boolean filterEquals(Intent left, Intent right) {
        try {
            Method method = Intent.class.getMethod("filterEquals", Intent.class);
            Object value = method.invoke(left, right);
            if (value instanceof Boolean result) return result;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall through to the stable public identity fields below.
        }
        return filterKey(left).equals(filterKey(right));
    }

    private static String filterKey(Intent intent) {
        if (intent == null) return "";
        StringBuilder out = new StringBuilder(256);
        out.append(value(intent.getAction())).append('|')
                .append(intent.getData() == null ? "" : intent.getData()).append('|')
                .append(value(intent.getType())).append('|')
                .append(value(intent.getPackage())).append('|');
        android.content.ComponentName component = intent.getComponent();
        if (component != null) {
            out.append(value(component.getPackageName())).append('/')
                    .append(value(component.getClassName()));
        }
        out.append('|');
        if (intent.getCategories() != null) {
            ArrayList<String> categories = new ArrayList<>(intent.getCategories());
            Collections.sort(categories);
            for (String category : categories) out.append(value(category)).append(',');
        }
        appendOptionalIdentity(out, intent, "getIdentifier");
        return out.toString();
    }

    private static void appendOptionalIdentity(StringBuilder out, Intent intent, String methodName) {
        try {
            Method method = Intent.class.getMethod(methodName);
            Object value = method.invoke(intent);
            out.append('|').append(value == null ? "" : value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // API 26-28 and compact stubs do not expose the identifier field.
        }
    }

    private static String value(String value) { return value == null ? "" : value; }
}
