package com.warden.controlledsandbox;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Small lifecycle-owned executor used by Activities in this Java-only build. */
final class SandboxViewModel implements AutoCloseable {
    private final SandboxApplicationLayer application;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    SandboxViewModel(android.content.Context context) {
        application = new SandboxApplicationLayer(context);
    }

    SandboxApplicationLayer application() { return application; }

    <T> void execute(Callable<T> task, Consumer<T> success, Consumer<Exception> failure) {
        worker.execute(() -> {
            try {
                T value = task.call();
                success.accept(value);
            } catch (Exception error) {
                failure.accept(error);
            }
        });
    }

    void execute(Runnable task, Consumer<Exception> failure) {
        worker.execute(() -> {
            try { task.run(); }
            catch (Exception error) { failure.accept(error); }
        });
    }

    @Override public void close() {
        worker.shutdownNow();
        application.close();
    }
}
