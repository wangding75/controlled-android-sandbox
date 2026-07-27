package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BrokerStateStoreSelfTest {
    public static void main(String[] args) throws Exception {
        testDefensiveCopies();
        testSingleConsume();
        testConcurrentRouteLoad();
        System.out.println("PASS broker concurrent state self-test");
    }

    private static void testDefensiveCopies() {
        BrokerStateStore store = new BrokerStateStore();
        Bundle source = new Bundle();
        source.putString("value", "trusted");
        store.putPrepared("spec", source);
        source.putString("value", "mutated-source");
        Bundle first = store.prepared("spec");
        require("trusted".equals(first.getString("value", "")), "prepared source copy");
        first.putString("value", "mutated-reader");
        require("trusted".equals(store.prepared("spec").getString("value", "")), "prepared reader copy");
    }

    private static void testSingleConsume() throws Exception {
        BrokerStateStore store = new BrokerStateStore();
        Bundle payload = route("session-a", 1);
        store.putRoute("shared", payload);
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
                if (store.consumeRoute("shared") != null) winners.incrementAndGet();
                return null;
            }));
        }
        require(ready.await(5, TimeUnit.SECONDS), "consumers ready");
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        require(winners.get() == 1, "one-time route single winner");
    }

    private static void testConcurrentRouteLoad() throws Exception {
        BrokerStateStore store = new BrokerStateStore();
        int threads = 12;
        int operations = 600;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            int owner = thread;
            futures.add(executor.submit(() -> {
                start.await();
                String instance = "u" + owner + ":pkg";
                for (int index = 0; index < operations; index++) {
                    String token = owner + "-" + index;
                    store.putRoute(token, route("session-" + owner, owner + 1));
                    require(store.consumeRoute(token) != null, "unique route consumed");
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        require(store.pendingRoutes() == 0, "route state leak");
    }

    private static Bundle route(String sessionId, long generation) {
        Bundle value = new Bundle();
        value.putString(RuntimeKeys.SESSION_ID, sessionId);
        value.putLong(RuntimeKeys.GENERATION, generation);
        return value;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
