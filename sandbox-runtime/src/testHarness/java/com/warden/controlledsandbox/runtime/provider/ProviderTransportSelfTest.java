package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.database.Cursor;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ProviderTransportSelfTest {
    public static void main(String[] args) throws Exception {
        testCodec();
        testSequentialPagingAndReplayProtection();
        testExpiryCancellationAndCapacity();
        testLargeResultPagingAndMemoryLimits();
        testConcurrentSinglePageWinner();
        testStaleGenerationAndProducerDeath();
        System.out.println("PASS Provider cursor transport self-test");
    }

    private static void testCodec() {
        require(CursorWireCodec.decode(CursorWireCodec.nullValue()) == null, "null codec");
        require(Long.valueOf(7).equals(CursorWireCodec.decode(CursorWireCodec.integer(7))), "integer codec");
        require(Double.valueOf(2.5).equals(CursorWireCodec.decode(CursorWireCodec.floating(2.5))), "float codec");
        require("guest|value".equals(CursorWireCodec.decode(CursorWireCodec.text("guest|value"))), "text codec");
        byte[] blob = (byte[]) CursorWireCodec.decode(CursorWireCodec.blob(new byte[]{1, 2, 3}));
        require(blob.length == 3 && blob[2] == 3, "blob codec");
    }

    private static void testSequentialPagingAndReplayProtection() {
        AtomicLong clock = new AtomicLong(100);
        ProviderCursorTransport transport = transport(clock, 8, 4096, 1024, 10_000, 16);
        FakeCursor cursor = FakeCursor.rows(2);
        Bundle opened = transport.open(cursor, "broker-token", "session-a", "u0:pkg.provider",
                3, 1, 100);
        require("broker-token".equals(opened.getString(RuntimeKeys.CURSOR_TOKEN, "")), "broker token preserved");
        require(opened.getInt(RuntimeKeys.CURSOR_TOTAL_ROWS, -1) == 2, "cursor total rows");
        require(opened.getInt(RuntimeKeys.CURSOR_NEXT_OFFSET, -1) == 1, "initial next offset");
        require(opened.getLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, -1) == 1, "initial next sequence");
        ArrayList<String> first = opened.getStringArrayList(RuntimeKeys.CURSOR_ROW_PREFIX + 0);
        require(first != null && Long.valueOf(0).equals(CursorWireCodec.decode(first.get(0))), "first page id");

        Bundle secondPage = transport.page("broker-token", "session-a", 3, 1, 1, 2);
        ArrayList<String> second = secondPage.getStringArrayList(RuntimeKeys.CURSOR_ROW_PREFIX + 0);
        require(second != null && "row-1".equals(CursorWireCodec.decode(second.get(1))), "second page text");
        require(secondPage.getBoolean(RuntimeKeys.CURSOR_END_REACHED, false), "cursor end");
        require(secondPage.getLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, -1) == 2, "sequence advanced");

        boolean replay = false;
        try { transport.page("broker-token", "session-a", 3, 1, 1, 1); }
        catch (RuntimeException expected) { replay = true; }
        require(replay, "cursor replay rejected");

        boolean wrongOwner = false;
        try { transport.close("broker-token", "session-b", 3); }
        catch (SecurityException expected) { wrongOwner = true; }
        require(wrongOwner, "cursor owner isolation");
        require("CURSOR_CLOSED".equals(transport.close("broker-token", "session-a", 3)
                .getString(RuntimeKeys.STATUS, "")), "cursor close");
        require(cursor.isClosed(), "physical cursor close");
    }

    private static void testExpiryCancellationAndCapacity() {
        AtomicLong clock = new AtomicLong(0);
        ProviderCursorTransport transport = transport(clock, 2, 4096, 1024, 100, 8);
        FakeCursor first = FakeCursor.rows(3);
        transport.open(first, "expiry", "session-a", "provider", 1, 0, 10);
        clock.set(11);
        require(transport.activeLeaseCount() == 0, "expired lease purged");
        require(first.isClosed(), "expired physical cursor closed");

        FakeCursor second = FakeCursor.rows(1);
        transport.open(second, "cancel", "session-a", "provider", 1, 0, 100);
        require("CURSOR_CANCELLED".equals(transport.cancel("cancel", "session-a", 1)
                .getString(RuntimeKeys.STATUS, "")), "cursor cancel");
        require(second.isClosed(), "cancel closes physical cursor");

        FakeCursor capA = FakeCursor.rows(1);
        FakeCursor capB = FakeCursor.rows(1);
        FakeCursor capC = FakeCursor.rows(1);
        transport.open(capA, "cap-a", "session-a", "provider", 1, 0, 100);
        transport.open(capB, "cap-b", "session-a", "provider", 1, 0, 100);
        boolean exhausted = false;
        try { transport.open(capC, "cap-c", "session-a", "provider", 1, 0, 100); }
        catch (IllegalStateException expected) { exhausted = true; }
        require(exhausted, "lease capacity enforced");
        require(capC.isClosed(), "rejected cursor closed");
        transport.closeAll();
    }

    private static void testLargeResultPagingAndMemoryLimits() {
        AtomicLong clock = new AtomicLong(0);
        ProviderCursorTransport transport = transport(clock, 8, 16 * 1024, 1024, 20_000, 8);
        transport.open(FakeCursor.rows(10_000), "large", "session-a", "provider", 1, 0, 100);
        int offset = 0;
        long sequence = 0;
        while (offset < 10_000) {
            Bundle page = transport.page("large", "session-a", 1, offset, sequence, 256);
            require(page.getInt(RuntimeKeys.CURSOR_PAGE_BYTES, -1) <= 16 * 1024, "page byte limit");
            int returned = page.getInt(RuntimeKeys.CURSOR_ROWS_RETURNED, -1);
            require(returned > 0 && returned <= 256, "bounded page rows");
            offset = page.getInt(RuntimeKeys.CURSOR_NEXT_OFFSET, -1);
            sequence = page.getLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, -1);
        }
        require(offset == 10_000, "large result fully paged");
        transport.close("large", "session-a", 1);

        ProviderCursorTransport strict = transport(clock, 4, 128, 16, 100, 8);
        FakeCursor oversized = FakeCursor.singleText("this-cell-is-larger-than-sixteen-bytes");
        boolean rejected = false;
        try { strict.open(oversized, "oversized", "session-a", "provider", 1, 1, 100); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "oversized cell rejected");
        require(oversized.isClosed(), "oversized cursor closed");
    }

    private static void testConcurrentSinglePageWinner() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        ProviderCursorTransport transport = transport(clock, 8, 4096, 1024, 100, 8);
        transport.open(FakeCursor.rows(4), "concurrent", "session-a", "provider", 1, 0, 100);
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    transport.page("concurrent", "session-a", 1, 0, 0, 1);
                    winners.incrementAndGet();
                } catch (RuntimeException expected) { }
                return null;
            }));
        }
        require(ready.await(5, TimeUnit.SECONDS), "concurrent readers ready");
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        require(winners.get() == 1, "one cursor page winner");
        transport.cancel("concurrent", "session-a", 1);
    }

    private static void testStaleGenerationAndProducerDeath() {
        AtomicLong clock = new AtomicLong(100);
        ProviderCursorTransport transport = transport(clock, 8, 4096, 1024, 10_000, 16);
        FakeCursor cursor = FakeCursor.rows(8);
        transport.open(cursor, "death-token", "session-a", "u0:pkg.provider", 4, 2, 100);
        boolean stale = false;
        try {
            transport.page("death-token", "session-a", 5, 2, 1, 2);
        } catch (RuntimeException expected) {
            stale = true;
        }
        require(stale, "stale generation cannot page");
        int closed = transport.closeSession("session-a", 4);
        require(closed == 1, "producer death closes the live lease");
        require(cursor.isClosed(), "producer death closes the Cursor");
        require(transport.activeLeaseCount() == 0, "no leftover cursor lease after death");
    }

    private static ProviderCursorTransport transport(AtomicLong clock, int active, int pageBytes,
                                                       int cellBytes, int rows, int columns) {
        return new ProviderCursorTransport(clock::get, active, pageBytes, cellBytes, rows, columns);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class FakeCursor implements Cursor {
        private final Object[][] rows;
        private int position = -1;
        private boolean closed;

        private FakeCursor(Object[][] rows) { this.rows = rows; }

        static FakeCursor rows(int count) {
            Object[][] values = new Object[count][2];
            for (int index = 0; index < count; index++) {
                values[index][0] = (long) index;
                values[index][1] = "row-" + index;
            }
            return new FakeCursor(values);
        }

        static FakeCursor singleText(String value) { return new FakeCursor(new Object[][]{{1L, value}}); }

        @Override public String[] getColumnNames() { return new String[]{"id", "name"}; }
        @Override public int getCount() { return rows.length; }
        @Override public boolean moveToPosition(int value) { position = value; return value >= 0 && value < rows.length; }
        @Override public boolean moveToNext() { if (position + 1 >= rows.length) return false; position++; return true; }
        @Override public boolean isNull(int column) { return rows[position][column] == null; }
        @Override public int getType(int column) { return column == 0 ? FIELD_TYPE_INTEGER : FIELD_TYPE_STRING; }
        @Override public long getLong(int column) { return ((Number) rows[position][column]).longValue(); }
        @Override public double getDouble(int column) { return ((Number) rows[position][column]).doubleValue(); }
        @Override public String getString(int column) { return String.valueOf(rows[position][column]); }
        @Override public byte[] getBlob(int column) { return (byte[]) rows[position][column]; }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
}
