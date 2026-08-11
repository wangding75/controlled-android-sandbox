package android.location;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Guest-owned LocationManager boundary for API images whose hidden listener transport is only a
 * Binder marker. It deliberately never delegates location reads or callbacks to the Host.
 */
public final class ControlledLocationManager extends LocationManager {
    private final Supplier<VirtualLocationProfileSnapshot> profileSupplier;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            runnable -> {
                Thread thread = new Thread(runnable, "controlled-sandbox-location-manager");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<LocationListener, ScheduledFuture<?>> listeners = new IdentityHashMap<>();

    public ControlledLocationManager(Supplier<VirtualLocationProfileSnapshot> profileSupplier) {
        super();
        this.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
    }

    @Override public boolean isLocationEnabled() {
        return profile().providerEnabled() && !blocked();
    }

    @Override public boolean isProviderEnabled(String provider) {
        return accepts(provider) && profile().providerEnabled() && !blocked();
    }

    @Override public Location getLastKnownLocation(String provider) {
        VirtualLocationProfileSnapshot current = profile();
        if (!accepts(current, provider) || !current.providerEnabled()
                || VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(current.mode())) return null;
        return location(current);
    }

    @Override public void getCurrentLocation(String provider, CancellationSignal cancellationSignal,
            Executor executor, Consumer<Location> consumer) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(consumer, "consumer");
        if (cancellationSignal != null && cancellationSignal.isCanceled()) return;
        executor.execute(() -> {
            if (cancellationSignal == null || !cancellationSignal.isCanceled()) {
                consumer.accept(getLastKnownLocation(provider));
            }
        });
    }

    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, LocationListener listener) {
        requestLocationUpdates(provider, minTimeMs, minDistanceM, listener,
                Looper.getMainLooper());
    }

    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, LocationListener listener, Looper looper) {
        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        register(provider, minTimeMs, listener, handler::post);
    }

    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, Executor executor, LocationListener listener) {
        register(provider, minTimeMs, listener, executor);
    }

    @Override public void requestLocationUpdates(String provider, LocationRequest request,
            Executor executor, LocationListener listener) {
        long interval = request == null ? profile().minimumUpdateIntervalMs()
                : request.getIntervalMillis();
        register(provider, interval, listener, executor);
    }

    @Override public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            Criteria criteria, LocationListener listener, Looper looper) {
        requestLocationUpdates(profile().provider(), minTimeMs, minDistanceM, listener, looper);
    }

    @Override public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            Criteria criteria, Executor executor, LocationListener listener) {
        requestLocationUpdates(profile().provider(), minTimeMs, minDistanceM, executor, listener);
    }

    @Override public void requestSingleUpdate(String provider, LocationListener listener,
            Looper looper) {
        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        Objects.requireNonNull(listener, "listener");
        LocationListener oneShot = new LocationListener() {
            @Override public void onLocationChanged(Location value) {
                removeUpdates(this);
                listener.onLocationChanged(value);
            }
            @Override public void onProviderEnabled(String value) {
                listener.onProviderEnabled(value);
            }
            @Override public void onProviderDisabled(String value) {
                listener.onProviderDisabled(value);
            }
        };
        register(provider, 0L, oneShot, handler::post);
    }

    @Override public void removeUpdates(LocationListener listener) {
        if (listener == null) return;
        ScheduledFuture<?> future;
        synchronized (listeners) {
            future = listeners.remove(listener);
        }
        if (future != null) future.cancel(false);
    }

    @Override public boolean hasProvider(String provider) {
        return accepts(provider);
    }

    @Override public List<String> getAllProviders() {
        if (blocked()) return Collections.emptyList();
        return Collections.singletonList(profile().provider());
    }

    @Override public List<String> getProviders(boolean enabledOnly) {
        if (blocked() || (enabledOnly && !profile().providerEnabled())) {
            return Collections.emptyList();
        }
        return Collections.singletonList(profile().provider());
    }

    @Override public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        return getProviders(enabledOnly);
    }

    @Override public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        return getProviders(enabledOnly).isEmpty() ? null : profile().provider();
    }

    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, PendingIntent intent) {
        if (intent == null) throw new IllegalArgumentException("intent is required");
        long delay = cadence(minTimeMs);
        scheduler.schedule(() -> send(provider, intent), delay, TimeUnit.MILLISECONDS);
    }

    @Override public void removeUpdates(PendingIntent intent) { }

    private void register(String provider, long interval, LocationListener listener,
            Executor callbackExecutor) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(callbackExecutor, "executor");
        removeUpdates(listener);
        if (!accepts(provider) || blocked() || !profile().providerEnabled()) return;
        long delay = cadence(interval);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            Location value = getLastKnownLocation(provider);
            if (value == null) return;
            try {
                callbackExecutor.execute(() -> listener.onLocationChanged(value));
            } catch (RuntimeException ignored) {
                // A rejected Guest executor ends this registration; it is not a successful
                // callback and must not be converted into a fake readiness signal.
                removeUpdates(listener);
            }
        }, 0L, delay, TimeUnit.MILLISECONDS);
        synchronized (listeners) {
            listeners.put(listener, future);
        }
    }

    private void send(String provider, PendingIntent intent) {
        Location value = getLastKnownLocation(provider);
        if (value == null) return;
        try {
            Intent update = new Intent(LocationManager.KEY_LOCATION_CHANGED);
            update.putExtra(LocationManager.KEY_LOCATION_CHANGED, value);
            intent.send(null, 0, update);
        } catch (PendingIntent.CanceledException ignored) {
            // The Guest-owned pending intent is gone; there is no callback to deliver.
        }
    }

    private VirtualLocationProfileSnapshot profile() {
        VirtualLocationProfileSnapshot value = profileSupplier.get();
        if (value == null) throw new IllegalStateException("VIRTUAL_LOCATION_PROFILE_UNAVAILABLE");
        return value.sampleAt(System.currentTimeMillis(), System.nanoTime());
    }

    private static Location location(VirtualLocationProfileSnapshot value) {
        Location result = new Location(value.provider());
        result.setLatitude(value.latitude());
        result.setLongitude(value.longitude());
        result.setAltitude(value.altitudeMeters());
        result.setAccuracy(value.accuracyMeters());
        result.setSpeed(value.speedMetersPerSecond());
        result.setBearing(value.bearingDegrees());
        result.setTime(value.timeMs() > 0L ? value.timeMs() : System.currentTimeMillis());
        result.setElapsedRealtimeNanos(value.elapsedRealtimeNanos() > 0L
                ? value.elapsedRealtimeNanos() : System.nanoTime());
        return result;
    }

    private boolean accepts(String provider) { return accepts(profile(), provider); }

    private static boolean accepts(VirtualLocationProfileSnapshot value, String provider) {
        return provider == null || provider.isEmpty() || value.provider().equals(provider)
                || (LocationManager.FUSED_PROVIDER.equals(provider)
                && LocationManager.NETWORK_PROVIDER.equals(value.provider()));
    }

    private boolean blocked() {
        return VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile().mode());
    }

    private long cadence(long requested) {
        long configured = profile().minimumUpdateIntervalMs();
        long value = requested > 0L ? requested : configured;
        return Math.max(1L, Math.min(value <= 0L ? 1_000L : value, 24L * 60L * 60L * 1_000L));
    }
}
