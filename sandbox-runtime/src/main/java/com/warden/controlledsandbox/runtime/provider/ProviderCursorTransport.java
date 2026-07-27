package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.database.Cursor;
import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.provider.CursorLeaseRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Session-owned cursor paging, cancellation and close transport. Cursor objects never cross Binder directly. */
public final class ProviderCursorTransport {
    public static final long DEFAULT_LEASE_TTL_MS = 120_000L;
    static final int MAX_PAGE_SIZE = 256;
    static final int MAX_ACTIVE_LEASES = 64;
    static final int MAX_COLUMNS = 128;
    static final int MAX_TOTAL_ROWS = 1_000_000;
    static final int MAX_PAGE_BYTES = 512 * 1024;
    static final int MAX_CELL_BYTES = 64 * 1024;

    private final CursorLeaseRegistry leases = new CursorLeaseRegistry();
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final int maxActiveLeases;
    private final int maxPageBytes;
    private final int maxCellBytes;
    private final int maxTotalRows;
    private final int maxColumns;

    public ProviderCursorTransport() {
        this(android.os.SystemClock::elapsedRealtime, MAX_ACTIVE_LEASES, MAX_PAGE_BYTES,
                MAX_CELL_BYTES, MAX_TOTAL_ROWS, MAX_COLUMNS);
    }

    public ProviderCursorTransport(LongSupplier clock, int maxActiveLeases, int maxPageBytes,
                            int maxCellBytes, int maxTotalRows, int maxColumns) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (maxActiveLeases < 1 || maxPageBytes < 1 || maxCellBytes < 1
                || maxTotalRows < 1 || maxColumns < 1) {
            throw new IllegalArgumentException("Cursor transport limits must be positive");
        }
        this.maxActiveLeases = maxActiveLeases;
        this.maxPageBytes = maxPageBytes;
        this.maxCellBytes = maxCellBytes;
        this.maxTotalRows = maxTotalRows;
        this.maxColumns = maxColumns;
    }

    public synchronized Bundle open(Cursor cursor, String brokerToken, String sessionId, String providerInstanceId,
                             long generation, int requestedPageSize, long requestedTtlMs) {
        cleanupExpired();
        if (cursor == null) throw new IllegalArgumentException("Provider returned null Cursor");
        try {
            requireText(brokerToken, "cursorToken");
            long ttlMs = normalizeTtl(requestedTtlMs);
            String[] columnArray = cursor.getColumnNames();
            ArrayList<String> columns = new ArrayList<>();
            if (columnArray != null) {
                if (columnArray.length > maxColumns) throw new IllegalArgumentException("CURSOR_COLUMN_LIMIT_EXCEEDED");
                for (String column : columnArray) columns.add(column == null ? "" : column);
            }
            int rowCount = cursor.getCount();
            if (rowCount < 0 || rowCount > maxTotalRows) {
                throw new IllegalArgumentException("CURSOR_ROW_LIMIT_EXCEEDED");
            }
            CursorLeaseRegistry.Lease lease = leases.open(brokerToken, sessionId, providerInstanceId, columns,
                    rowCount, generation, now(), ttlMs, maxActiveLeases);
            cursors.put(lease.token(), cursor);
            Bundle out = metadata(lease);
            int pageSize = normalizePageSize(requestedPageSize);
            if (pageSize > 0 && rowCount > 0) {
                try {
                    out.putAll(pageInternal(lease.token(), sessionId, generation, 0, 0, pageSize));
                } catch (Throwable error) {
                    removeAndClose(lease.token());
                    throw error;
                }
            }
            return out;
        } catch (Throwable error) {
            if (!cursors.containsValue(cursor)) closeQuietly(cursor);
            throw error;
        }
    }

    public synchronized Bundle page(String token, String sessionId, long generation, int offset,
                             long sequence, int requestedLimit) {
        cleanupExpired();
        return pageInternal(token, sessionId, generation, offset, sequence, requestedLimit);
    }

    public synchronized Bundle close(String token, String sessionId, long generation) {
        cleanupExpired();
        return terminal(token, sessionId, generation, "CURSOR_CLOSED", "CURSOR_ALREADY_CLOSED");
    }

    public synchronized Bundle cancel(String token, String sessionId, long generation) {
        cleanupExpired();
        return terminal(token, sessionId, generation, "CURSOR_CANCELLED", "CURSOR_ALREADY_TERMINAL");
    }

    synchronized int closeSession(String sessionId, long generation) {
        cleanupExpired();
        List<String> tokens = leases.closeSessionTokens(sessionId, generation);
        closeTokens(tokens);
        return tokens.size();
    }

    synchronized int closeProvider(String providerInstanceId) {
        cleanupExpired();
        List<String> tokens = leases.closeProviderTokens(providerInstanceId);
        closeTokens(tokens);
        return tokens.size();
    }

    public synchronized void closeAll() {
        closeTokens(leases.closeAllTokens());
        for (Cursor cursor : cursors.values()) closeQuietly(cursor);
        cursors.clear();
    }

    synchronized int activeLeaseCount() {
        cleanupExpired();
        return leases.size(now());
    }

    private Bundle pageInternal(String token, String sessionId, long generation, int offset,
                                long sequence, int requestedLimit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        int limit = normalizePageSize(requestedLimit);
        if (limit < 1) throw new IllegalArgumentException("page size must be positive");
        CursorLeaseRegistry.Lease lease = leases.requirePage(token, sessionId, generation, offset, sequence, now());
        Cursor cursor = cursors.get(token);
        if (cursor == null || cursor.isClosed()) {
            removeAndClose(token);
            throw new IllegalStateException("CURSOR_TRANSPORT_MISSING");
        }

        Bundle out = metadata(lease);
        out.putInt(RuntimeKeys.CURSOR_OFFSET, offset);
        out.putInt(RuntimeKeys.CURSOR_PAGE_SIZE, limit);
        out.putLong(RuntimeKeys.CURSOR_PAGE_SEQUENCE, sequence);
        int emitted = 0;
        int pageBytes = 0;
        try {
            cursor.moveToPosition(offset - 1);
            while (emitted < limit && cursor.moveToNext()) {
                ArrayList<String> row = new ArrayList<>();
                int rowBytes = 0;
                for (int column = 0; column < lease.columns().size(); column++) {
                    String cell = readCell(cursor, column);
                    int cellBytes = cell.getBytes(StandardCharsets.UTF_8).length;
                    if (cellBytes > maxCellBytes) throw new IllegalStateException("CURSOR_CELL_LIMIT_EXCEEDED");
                    rowBytes = Math.addExact(rowBytes, cellBytes);
                    row.add(cell);
                }
                if (pageBytes + rowBytes > maxPageBytes) {
                    if (emitted == 0) throw new IllegalStateException("CURSOR_ROW_EXCEEDS_PAGE_LIMIT");
                    break;
                }
                pageBytes += rowBytes;
                out.putStringArrayList(RuntimeKeys.CURSOR_ROW_PREFIX + emitted, row);
                emitted++;
            }
            boolean endReached = offset + emitted >= lease.rowCount();
            CursorLeaseRegistry.Lease committed = leases.commitPage(token, sessionId, generation,
                    sequence, emitted, endReached);
            out.putInt(RuntimeKeys.CURSOR_ROWS_RETURNED, emitted);
            out.putInt(RuntimeKeys.CURSOR_PAGE_BYTES, pageBytes);
            out.putBoolean(RuntimeKeys.CURSOR_END_REACHED, committed.endReached());
            out.putInt(RuntimeKeys.CURSOR_NEXT_OFFSET, committed.nextOffset());
            out.putLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, committed.nextSequence());
            return out;
        } catch (Throwable error) {
            if ("CURSOR_CELL_LIMIT_EXCEEDED".equals(error.getMessage())
                    || "CURSOR_ROW_EXCEEDS_PAGE_LIMIT".equals(error.getMessage())
                    || error instanceof ArithmeticException) {
                removeAndClose(token);
            }
            throw error;
        }
    }

    private Bundle terminal(String token, String sessionId, long generation,
                            String removedStatus, String absentStatus) {
        boolean removed = leases.close(token, sessionId, generation);
        Cursor cursor = cursors.remove(token);
        closeQuietly(cursor);
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, removed ? removedStatus : absentStatus);
        out.putString(RuntimeKeys.CURSOR_TOKEN, token);
        return out;
    }

    private void cleanupExpired() {
        closeTokens(leases.purgeExpiredTokens(now()));
    }

    private void closeTokens(List<String> tokens) {
        for (String token : tokens) {
            Cursor cursor = cursors.remove(token);
            closeQuietly(cursor);
        }
    }

    private void removeAndClose(String token) {
        leases.forceClose(token);
        Cursor cursor = cursors.remove(token);
        closeQuietly(cursor);
    }

    private static Bundle metadata(CursorLeaseRegistry.Lease lease) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, "CURSOR_READY");
        out.putString(RuntimeKeys.CURSOR_TOKEN, lease.token());
        out.putStringArrayList(RuntimeKeys.CURSOR_COLUMNS, new ArrayList<>(lease.columns()));
        out.putInt(RuntimeKeys.CURSOR_TOTAL_ROWS, lease.rowCount());
        out.putLong(RuntimeKeys.CURSOR_EXPIRES_AT, lease.expiresAtMs());
        out.putInt(RuntimeKeys.CURSOR_NEXT_OFFSET, lease.nextOffset());
        out.putLong(RuntimeKeys.CURSOR_NEXT_SEQUENCE, lease.nextSequence());
        out.putBoolean(RuntimeKeys.CURSOR_END_REACHED, lease.endReached());
        return out;
    }

    private static int normalizePageSize(int requested) {
        if (requested < 0) throw new IllegalArgumentException("page size must be non-negative");
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static long normalizeTtl(long requested) {
        if (requested < 1) return DEFAULT_LEASE_TTL_MS;
        return Math.min(requested, DEFAULT_LEASE_TTL_MS);
    }

    private static String readCell(Cursor cursor, int column) {
        if (cursor.isNull(column)) return CursorWireCodec.nullValue();
        switch (cursor.getType(column)) {
            case Cursor.FIELD_TYPE_INTEGER: return CursorWireCodec.integer(cursor.getLong(column));
            case Cursor.FIELD_TYPE_FLOAT: return CursorWireCodec.floating(cursor.getDouble(column));
            case Cursor.FIELD_TYPE_BLOB: return CursorWireCodec.blob(cursor.getBlob(column));
            case Cursor.FIELD_TYPE_STRING: return CursorWireCodec.text(cursor.getString(column));
            case Cursor.FIELD_TYPE_NULL: return CursorWireCodec.nullValue();
            default: throw new IllegalStateException("Unsupported cursor field type");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }

    private static void closeQuietly(Cursor cursor) {
        if (cursor == null) return;
        try { cursor.close(); } catch (Throwable ignored) { }
    }

    private long now() { return clock.getAsLong(); }
}
