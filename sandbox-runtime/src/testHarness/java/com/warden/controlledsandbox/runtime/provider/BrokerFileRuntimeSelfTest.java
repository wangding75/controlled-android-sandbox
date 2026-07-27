package com.warden.controlledsandbox.runtime.provider;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BrokerFileRuntimeSelfTest {
    public static void main(String[] args) throws Exception {
        modePolicy();
        openCommitAndClose();
        assetMetadataAndInvalidResult();
        concurrentCloseSingleWinner();
        expiryInvalidationAndCapacity();
        System.out.println("PASS broker Provider file lease runtime self-test");
    }

    private static void modePolicy() {
        require(ProviderFileModes.flags("r") == 1, "read mode");
        require(ProviderFileModes.flags("w") == 2, "write mode");
        require(ProviderFileModes.flags("rw") == 3, "read-write mode");
        boolean denied = false;
        try { ProviderFileModes.requireAllowed("rwa"); }
        catch (IllegalArgumentException expected) { denied = true; }
        require(denied, "unknown file mode rejected");
    }

    private static void openCommitAndClose() throws Exception {
        BrokerFileRuntime runtime = new BrokerFileRuntime();
        BrokerFileRuntime.OpenReservation reservation = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 0);
        ParcelFileDescriptor descriptor = new ParcelFileDescriptor();
        Bundle result = fileResult(reservation.token(), descriptor);
        BrokerFileRuntime.Lease lease = runtime.commitOpen(reservation, result, 1);
        require(lease.kind().equals("FILE"), "file kind retained");
        require(lease.flags() == 1 && lease.mode().equals("r"), "file mode retained");
        require(runtime.size() == 1, "committed file counted");

        boolean wrongCaller = false;
        try { runtime.reserveClose(lease.token(), "wrong", 2, "target-session", 5, 1); }
        catch (SecurityException expected) { wrongCaller = true; }
        require(wrongCaller, "wrong caller rejected");

        BrokerFileRuntime.CloseReservation close = runtime.reserveClose(lease.token(),
                "caller-session", 2, "target-session", 5, 1);
        runtime.completeClose(close);
        require(descriptor.isClosed(), "broker descriptor closed");
        require(runtime.size() == 0, "closed lease removed");

        boolean replay = false;
        try { runtime.reserveClose(lease.token(), "caller-session", 2, "target-session", 5, 1); }
        catch (SecurityException expected) { replay = true; }
        require(replay, "close replay rejected");
    }

    private static void assetMetadataAndInvalidResult() throws Exception {
        BrokerFileRuntime runtime = new BrokerFileRuntime();
        BrokerFileRuntime.OpenReservation reservation = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_TYPED_ASSET_FILE, "r", "text/plain", 0);
        ParcelFileDescriptor file = new ParcelFileDescriptor();
        AssetFileDescriptor asset = new AssetFileDescriptor(file, 7L, 99L);
        Bundle result = assetResult(reservation.token(), asset, "TYPED_ASSET", 7L, 99L);
        BrokerFileRuntime.Lease lease = runtime.commitOpen(reservation, result, 1);
        require(lease.startOffset() == 7L && lease.declaredLength() == 99L, "asset range retained");
        require(lease.mimeType().equals("text/plain"), "typed MIME retained");
        runtime.abort(lease.token());
        require(asset.isClosed(), "asset closed on abort");

        BrokerFileRuntime.OpenReservation invalid = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 2);
        ParcelFileDescriptor invalidDescriptor = new ParcelFileDescriptor();
        Bundle wrongToken = fileResult("guest-token", invalidDescriptor);
        boolean denied = false;
        try { runtime.commitOpen(invalid, wrongToken, 3); }
        catch (SecurityException expected) { denied = true; }
        require(denied, "Guest-defined token rejected");
        require(invalidDescriptor.isClosed(), "invalid descriptor closed");

        BrokerFileRuntime.OpenReservation wrongKind = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 4);
        AssetFileDescriptor wrongAsset = new AssetFileDescriptor(new ParcelFileDescriptor(), 0L, 1L);
        boolean kindDenied = false;
        try { runtime.commitOpen(wrongKind, assetResult(wrongKind.token(), wrongAsset, "ASSET", 0L, 1L), 5); }
        catch (IllegalArgumentException expected) { kindDenied = true; }
        require(kindDenied, "descriptor kind mismatch accepted");
        require(wrongAsset.isClosed(), "mismatched asset descriptor closed");

        BrokerFileRuntime.OpenReservation wrongType = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 6);
        AssetFileDescriptor typedAsFile = new AssetFileDescriptor(new ParcelFileDescriptor(), 0L, 1L);
        Bundle malicious = new Bundle();
        malicious.putString(RuntimeKeys.STATUS, "PROVIDER_FILE_OPEN");
        malicious.putString(RuntimeKeys.FILE_TOKEN, wrongType.token());
        malicious.putString(RuntimeKeys.FILE_DESCRIPTOR_KIND, "FILE");
        malicious.putString(RuntimeKeys.PROVIDER_FILE_MODE, "r");
        malicious.putString(RuntimeKeys.PROVIDER_MIME_TYPE, "");
        malicious.putParcelable(RuntimeKeys.FILE_DESCRIPTOR, typedAsFile);
        malicious.putLong(RuntimeKeys.FILE_START_OFFSET, 0L);
        malicious.putLong(RuntimeKeys.FILE_DECLARED_LENGTH, -1L);
        boolean typeDenied = false;
        try { runtime.commitOpen(wrongType, malicious, 7); }
        catch (IllegalArgumentException expected) { typeDenied = true; }
        require(typeDenied, "wrong Parcelable type accepted");
        require(typedAsFile.isClosed(), "wrong Parcelable resource closed");
    }

    private static void concurrentCloseSingleWinner() throws Exception {
        BrokerFileRuntime runtime = new BrokerFileRuntime();
        BrokerFileRuntime.OpenReservation reservation = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "rw", "", 0);
        Bundle concurrentResult = fileResult(reservation.token(), new ParcelFileDescriptor());
        concurrentResult.putString(RuntimeKeys.PROVIDER_FILE_MODE, "rw");
        BrokerFileRuntime.Lease lease = runtime.commitOpen(reservation, concurrentResult, 1);

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<BrokerFileRuntime.CloseReservation> winner = new AtomicReference<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    BrokerFileRuntime.CloseReservation close = runtime.reserveClose(lease.token(),
                            "caller-session", 2, "target-session", 5, 1);
                    winner.set(close);
                    winners.incrementAndGet();
                } catch (RuntimeException expected) { }
                return null;
            }));
        }
        require(ready.await(5, TimeUnit.SECONDS), "close contenders ready");
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        require(winners.get() == 1, "one close reservation winner");
        runtime.completeClose(winner.get());
    }

    private static void expiryInvalidationAndCapacity() {
        BrokerFileRuntime runtime = new BrokerFileRuntime();
        BrokerFileRuntime.OpenReservation expiring = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 0);
        ParcelFileDescriptor descriptor = new ParcelFileDescriptor();
        runtime.commitOpen(expiring, fileResult(expiring.token(), descriptor), 1);
        require(runtime.purgeExpired(BrokerFileRuntime.LEASE_TTL_MS + 1).size() == 1,
                "expired lease removed");
        require(descriptor.isClosed(), "expired broker descriptor closed");

        BrokerFileRuntime.OpenReservation caller = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 10);
        runtime.commitOpen(caller, fileResult(caller.token(), new ParcelFileDescriptor()), 11);
        require(runtime.invalidateSession("caller-session", 2).size() == 1,
                "caller invalidation closes lease");

        BrokerFileRuntime.OpenReservation target = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 12);
        runtime.commitOpen(target, fileResult(target.token(), new ParcelFileDescriptor()), 13);
        require(runtime.invalidateSession("target-session", 5).size() == 1,
                "target invalidation closes lease");

        BrokerFileRuntime.OpenReservation instance = reserve(runtime,
                ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 14);
        runtime.commitOpen(instance, fileResult(instance.token(), new ParcelFileDescriptor()), 15);
        require(runtime.invalidateInstance("u1:target").size() == 1,
                "target instance invalidation closes lease");

        for (int index = 0; index < BrokerFileRuntime.MAX_ACTIVE_LEASES; index++) {
            BrokerFileRuntime.OpenReservation reservation = runtime.reserveOpen(
                    ComponentOperations.PROVIDER_OPEN_FILE, "u1:caller-" + index,
                    "caller-" + index, 1, "u1:target-" + index, "target", 1,
                    "target:provider", "target-" + index, 1,
                    "content://authority/file/" + index, 1, "r", "", 20);
            runtime.commitOpen(reservation,
                    fileResult(reservation.token(), new ParcelFileDescriptor()), 21);
        }
        boolean exhausted = false;
        try { reserve(runtime, ComponentOperations.PROVIDER_OPEN_FILE, "r", "", 22); }
        catch (IllegalStateException expected) { exhausted = true; }
        require(exhausted, "Broker file capacity enforced");
        runtime.invalidateInstance("u1:target");
    }

    private static BrokerFileRuntime.OpenReservation reserve(BrokerFileRuntime runtime,
                                                              String operation, String mode,
                                                              String mimeType, long now) {
        return runtime.reserveOpen(operation, "u1:caller", "caller-session", 2,
                "u1:target", "target", 1, "target:provider", "target-session", 5,
                "content://authority/file", ProviderFileModes.flags(mode), mode, mimeType, now);
    }

    private static Bundle fileResult(String token, ParcelFileDescriptor descriptor) {
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "PROVIDER_FILE_OPEN");
        result.putString(RuntimeKeys.FILE_TOKEN, token);
        result.putString(RuntimeKeys.FILE_DESCRIPTOR_KIND, "FILE");
        result.putString(RuntimeKeys.PROVIDER_FILE_MODE, "r");
        result.putString(RuntimeKeys.PROVIDER_MIME_TYPE, "");
        result.putParcelable(RuntimeKeys.FILE_DESCRIPTOR, descriptor);
        result.putLong(RuntimeKeys.FILE_START_OFFSET, 0L);
        result.putLong(RuntimeKeys.FILE_DECLARED_LENGTH, -1L);
        return result;
    }

    private static Bundle assetResult(String token, AssetFileDescriptor descriptor, String kind,
                                      long startOffset, long length) {
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "PROVIDER_FILE_OPEN");
        result.putString(RuntimeKeys.FILE_TOKEN, token);
        result.putString(RuntimeKeys.FILE_DESCRIPTOR_KIND, kind);
        result.putString(RuntimeKeys.PROVIDER_FILE_MODE, "r");
        result.putString(RuntimeKeys.PROVIDER_MIME_TYPE, "TYPED_ASSET".equals(kind) ? "text/plain" : "");
        result.putParcelable(RuntimeKeys.ASSET_FILE_DESCRIPTOR, descriptor);
        result.putLong(RuntimeKeys.FILE_START_OFFSET, startOffset);
        result.putLong(RuntimeKeys.FILE_DECLARED_LENGTH, length);
        return result;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
